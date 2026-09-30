package eu.kanade.tachiyomi.extension.es.ikigaimangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

// Versión iOS/Tachimanga: sin ConfigurableSource ni SharedPreferences (rompen el
// verificador J9), y sin búsqueda por texto (el sitio la hace por RPC de Qwik
// que requiere ejecutar JavaScript/WebView).
@Source
abstract class IkigaiMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(::nsfwCookieInterceptor)
        .rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }

    private val domainMutex = Mutex()
    private var domainFetched = false
    private var activeBaseUrl = baseUrl

    // Ikigai rota dominios: ikigaimangas.com publica el dominio vigente en su
    // JS de landing. Sin SharedPreferences el dominio se refresca en memoria.
    private suspend fun fetchDomainUrl() {
        if (domainFetched) return
        domainMutex.withLock {
            if (domainFetched) return

            try {
                val initClient = network.client
                val headers = super.headersBuilder().build()
                val document = initClient.get("https://ikigaimangas.com", headers).asJsoup()
                val scriptUrl = document.selectFirst("button[on:click]:containsOwn(Ir al sitio)")?.attr("on:click")
                    ?: return
                initClient.get("https://ikigaimangas.com/build/$scriptUrl", headers).use { response ->
                    val script = response.body.string()
                    val domain = script.substringAfter("i(\"").substringBefore("\"")
                    val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
                    activeBaseUrl = "https://$host"
                    domainFetched = true
                }
            } catch (_: Exception) {}
        }
    }

    private fun nsfwCookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return request.header(ENABLE_NSFW_HEADER)?.let { value ->
            val newRequest = request.newBuilder()
                .removeHeader(ENABLE_NSFW_HEADER)
                .setCookie("is-adult-enabled", value)
                .build()
            chain.proceed(newRequest)
        } ?: chain.proceed(request)
    }

    private fun Request.Builder.setCookie(name: String, value: String): Request.Builder {
        val existingHeader = this.build().header("Cookie") ?: ""

        val cookies = existingHeader
            .split(";")
            .mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap().toMutableMap()

        cookies[name] = value

        val mergedHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        return this.header("Cookie", mergedHeader)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "cross-site")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH)

    override suspend fun getPopularManga(page: Int): MangasPage {
        fetchDomainUrl()

        val headers = headersBuilder()
            .enableNsfw(NSFW_ENABLED)
            .build()

        val document = client.get("$activeBaseUrl/clasificacion/", headers).asJsoup()

        val mangaList = document.select("div.grid > div.card").map { element ->
            SManga.create().apply {
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                title = element.selectFirst(".card-body .card-title")!!.text()
                val seriesUrl = element.selectFirst(".card-actions > a.btn[href]")!!.attr("href")
                url = seriesUrl.substringAfterLast("/series/").substringBefore("/")
            }
        }
        return MangasPage(mangaList, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        fetchDomainUrl()

        val headers = headersBuilder()
            .enableNsfw(NSFW_ENABLED)
            .build()

        val document = client.get("$activeBaseUrl/?pagina=$page", headers).asJsoup()

        val mangaList = document.select("section[aria-labelledby=new-chapters-heading] > ul.grid:last-of-type a.card").map { element ->
            SManga.create().apply {
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                title = element.selectFirst(".card-body .card-title")!!.text()
                url = element.attr("href").substringAfterLast("/series/").substringBefore("/")
            }
        }
        val hasNextPage = document.selectFirst("nav[aria-label=pagination] > a:last-child:not([class*=btn-disabled])") != null
        return MangasPage(mangaList, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        fetchDomainUrl()

        // La búsqueda por texto del sitio usa RPC de Qwik que requiere ejecutar
        // JavaScript; en Tachimanga solo queda la exploración por filtros.
        if (query.isNotEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val headers = headersBuilder()
            .enableNsfw(NSFW_ENABLED)
            .build()

        val searchUrl = "$activeBaseUrl/series/".toHttpUrl().newBuilder()
            .addQueryParameter("tipos[]", "comic")

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            searchUrl.addQueryParameter("generos[]", genre.id.toString())
                        }
                    }
                }
                is StatusFilter -> {
                    filter.state.forEach { status ->
                        if (status.state) {
                            searchUrl.addQueryParameter("estados[]", status.id.toString())
                        }
                    }
                }
                is SortByFilter -> {
                    searchUrl.addQueryParameter("ordenar", filter.selected)
                    searchUrl.addQueryParameter("direccion", if (filter.state?.ascending == true) "asc" else "desc")
                }
                else -> {}
            }
        }

        searchUrl.addQueryParameter("pagina", page.toString())

        val document = client.get(searchUrl.build(), headers).asJsoup()

        val mangaList = document.select("section[aria-labelledby=archive-heading] > ul.grid a.card").map { element ->
            SManga.create().apply {
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                title = element.selectFirst("h3")!!.text()
                url = element.attr("href").substringAfterLast("/series/").substringBefore("/")
            }
        }
        val hasNextPage = document.selectFirst("nav[aria-label=pagination] > a:last-child:not([class*=btn-disabled])") != null
        return MangasPage(mangaList, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}/"
    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        fetchDomainUrl()

        val document = client.get("$activeBaseUrl/series/${manga.url}/").asJsoup()
        val mainContent = document.selectFirst("main")!!
        val updatedManga = SManga.create().apply {
            title = mainContent.selectFirst(".card-body .card-title")!!.text()
            thumbnail_url = mainContent.selectFirst("article.card figure > img")?.attr("abs:src")
            description = mainContent.selectFirst(".card-body > p")?.text()
            status = parseStatus(mainContent.selectFirst("figure > ul a[href*=?estados]")?.text())
            genre = mainContent.select(".card-body > ul > li > a[href*=?generos]").joinToString { it.text().trim() }
        }

        if (!fetchChapters) {
            return SMangaUpdate(
                manga = updatedManga,
                chapters = chapters,
            )
        }

        val firstPageChapters = document
            .select("section.card > ul.grid a.card")
            .map(::chapterFromElement)

        val lastPage = document.select("nav[aria-label=pagination] > a[q:key^=page-]").lastOrNull()
            ?.attr("q:key")?.let {
                it.substringAfter("-").toIntOrNull()
            } ?: 1

        val remainingChapters = coroutineScope {
            (2..lastPage).map { page ->
                async {
                    val url = "$activeBaseUrl/series/${manga.url}/"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("pagina", page.toString())
                        .build()

                    client.get(url).asJsoup()
                        .select("section.card > ul.grid a.card")
                        .map(::chapterFromElement)
                }
            }.awaitAll()
        }.flatten()

        val chapterList = firstPageChapters + remainingChapters

        return SMangaUpdate(
            manga = updatedManga,
            chapters = chapterList,
        )
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "cancelada" -> SManga.CANCELLED
        "completa" -> SManga.COMPLETED
        "en curso" -> SManga.ONGOING
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.selectFirst(".card-body .card-title")!!.text()
        val dateString = element.selectFirst("time")?.attr("datetime")?.substringBeforeLast("(")?.trim()
        date_upload = dateFormat.tryParseDate(dateString)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        fetchDomainUrl()

        val headers = headersBuilder()
            .enableNsfw(NSFW_ENABLED)
            .build()

        var document = client.get(activeBaseUrl + chapter.url, headers).asJsoup()
        document.selectFirst("button > span:contains(permitir nsfw)")?.let {
            val newRequest = GET(activeBaseUrl + chapter.url, headers)
                .newBuilder()
                .enableNsfw(true)
                .build()
            document = client.newCall(newRequest).awaitSuccess().asJsoup()
        }
        return document.select("section div > img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "cross-site")
        .build()

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Nota: la búsqueda por texto no está disponible en iOS; usa los filtros."),
        Filter.Separator(),
        SortByFilter("Ordenar por", getSortProperties()),
        StatusFilter("Estados", getStatusFilters()),
        GenreFilter("Géneros", getGenreFilters()),
    )

    private fun Headers.Builder.enableNsfw(flag: Boolean) = this.set(ENABLE_NSFW_HEADER, flag.toString())
    private fun Request.Builder.enableNsfw(flag: Boolean) = this.header(ENABLE_NSFW_HEADER, flag.toString())

    companion object {
        private const val NSFW_ENABLED = true
        private const val ENABLE_NSFW_HEADER = "X-Add-Nsfw-Cookie"
    }
}
