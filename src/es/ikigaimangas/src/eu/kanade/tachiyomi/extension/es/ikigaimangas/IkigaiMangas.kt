package eu.kanade.tachiyomi.extension.es.ikigaimangas

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
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

// iOS/Tachimanga variant: no SharedPreferences/ConfigurableSource, so the
// rotating domain is cached in memory only. Text search is not possible: the
// site implements it as a Qwik RPC that requires executing JavaScript, so only
// filter-based browsing is supported.
@Source
abstract class IkigaiMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(::nsfwCookieInterceptor)
        .rateLimit(1, 2.seconds) { it.host == activeBaseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "cross-site")
    }

    // Ikigai rotates its site domain constantly. The only stable entry point is
    // the ikigaimangas.com landing page: its "Ir al sitio" button references a
    // JS chunk holding i("https://<redirector>/"), which bounces through
    // several redirects before landing on the currently active domain.
    private val domainMutex = Mutex()
    private var domainDiscovered = false
    private var activeBaseUrl = baseUrl

    private suspend fun discoverActiveDomain(force: Boolean = false) {
        if (domainDiscovered && !force) return
        domainMutex.withLock {
            if (domainDiscovered && !force) return@withLock
            domainDiscovered = false

            runCatching {
                val landing = client.get(LANDING_URL, headers).asJsoup()
                val scriptName = landing.selectFirst("""button[on:click]:containsOwn(Ir al sitio)""")
                    ?.attr("on:click")
                    ?.substringBefore('#')
                    ?: error("landing JS chunk not found")
                val redirector = client.get("$LANDING_URL/build/$scriptName", headers).use { response ->
                    DOMAIN_REGEX.find(response.body.string())?.groupValues?.get(1)
                } ?: error("redirector domain not found in $scriptName")
                client.get(redirector, headers).use { response ->
                    activeBaseUrl = "https://${response.request.url.host}"
                }
                domainDiscovered = true
            }
        }
    }

    // Requests a document on the active domain; if the domain rotated
    // mid-session, re-discovers it and retries once.
    private suspend fun fetchDocument(url: HttpUrl): Document {
        discoverActiveDomain()
        return try {
            client.get(url.onActiveDomain(), siteHeaders()).asJsoup()
        } catch (e: Exception) {
            discoverActiveDomain(force = true)
            client.get(url.onActiveDomain(), siteHeaders()).asJsoup()
        }
    }

    private fun HttpUrl.onActiveDomain(): HttpUrl = newBuilder().host(activeBaseUrl.toHttpUrl().host).build()

    private fun siteHeaders(): Headers = headersBuilder()
        .set("Referer", "$activeBaseUrl/")
        .set("Origin", activeBaseUrl)
        .set(NSFW_HEADER, "true")
        .build()

    // The adult-content cookie must ride along even through mid-request domain
    // redirects, so requests carry a marker header that this interceptor turns
    // into the real cookie, merged with any cookies already present.
    private fun nsfwCookieInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        request.header(NSFW_HEADER) ?: return chain.proceed(request)

        val cookies = request.header("Cookie")
            ?.split(";")
            ?.mapNotNull { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()
        cookies[NSFW_COOKIE] = "true"

        return chain.proceed(
            request.newBuilder()
                .removeHeader(NSFW_HEADER)
                .header("Cookie", cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" })
                .build(),
        )
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = fetchDocument("$activeBaseUrl/clasificacion/".toHttpUrl())
        return MangasPage(
            document.select("div.grid > div.card").map(::rankingCard),
            hasNextPage = false,
        )
    }

    private fun rankingCard(element: Element): SManga = SManga.create().apply {
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        title = element.selectFirst(".card-title")!!.text()
        url = element.selectFirst(""".card-actions a[href^="/series/"]""")!!.seriesSlug()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$activeBaseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .build()
        val document = fetchDocument(url)
        return MangasPage(
            // The section holds two grids: pinned series first, new chapters last.
            document.select("section[aria-labelledby=new-chapters-heading] > ul.grid:last-of-type a.card")
                .map { linkCard(it, ".card-title") },
            document.hasNextPage(),
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) return MangasPage(emptyList(), hasNextPage = false)

        val url = "$activeBaseUrl/series/".toHttpUrl().newBuilder().apply {
            addQueryParameter("tipos[]", "comic")
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> filter.state.forEach {
                        if (it.state) addQueryParameter("generos[]", it.id.toString())
                    }
                    is StatusFilter -> filter.state.forEach {
                        if (it.state) addQueryParameter("estados[]", it.id.toString())
                    }
                    is SortByFilter -> {
                        addQueryParameter("ordenar", filter.selected)
                        addQueryParameter("direccion", if (filter.state?.ascending == true) "asc" else "desc")
                    }
                    else -> {}
                }
            }
            addQueryParameter("pagina", page.toString())
        }.build()

        val document = fetchDocument(url)
        return MangasPage(
            document.select("section[aria-labelledby=archive-heading] > ul.grid a.card")
                .map { linkCard(it, "h3") },
            document.hasNextPage(),
        )
    }

    private fun linkCard(element: Element, titleSelector: String): SManga = SManga.create().apply {
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        title = element.selectFirst(titleSelector)!!.text()
        url = element.seriesSlug()
    }

    private fun Element.seriesSlug(): String = attr("href").substringAfter("/series/").substringBefore("/")

    private fun Document.hasNextPage(): Boolean = selectFirst("nav[aria-label=pagination] > a:last-child:not([class*=btn-disabled])") != null

    override fun getMangaUrl(manga: SManga): String = "$activeBaseUrl/series/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = activeBaseUrl + chapter.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val slug = when {
            segments.getOrNull(0) == "series" -> segments.getOrNull(1)
            // Chapter pages have no series backlink; the slug only exists in the
            // serialized Qwik state as "<series name>","<slug>","<type>".
            segments.getOrNull(0) == "capitulo" ->
                SERIES_SLUG_REGEX
                    .find(fetchDocument("$activeBaseUrl${url.encodedPath}".toHttpUrl()).html())
                    ?.groupValues?.get(1)
            else -> null
        }?.takeIf(String::isNotEmpty) ?: return null

        return fetchDocument("$activeBaseUrl/series/$slug/".toHttpUrl()).parseMangaDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = "$activeBaseUrl/series/${manga.url}/".toHttpUrl()
        val document = fetchDocument(mangaUrl)

        // Details and chapters share the series page; long series paginate
        // the chapter list with ?pagina=N.
        val lastPage = document.select("nav[aria-label=pagination] > a[q:key^=page-]")
            .lastOrNull()
            ?.attr("q:key")
            ?.substringAfter('-')
            ?.toIntOrNull()
            ?: 1

        val chapterList = coroutineScope {
            val remainingPages = (2..lastPage).map { page ->
                async {
                    fetchDocument(mangaUrl.newBuilder().setQueryParameter("pagina", page.toString()).build())
                        .select(CHAPTER_SELECTOR)
                        .map(::chapterFromElement)
                }
            }
            document.select(CHAPTER_SELECTOR).map(::chapterFromElement) + remainingPages.awaitAll().flatten()
        }

        return SMangaUpdate(
            manga = document.parseMangaDetails(manga.url),
            chapters = chapterList,
        )
    }

    private fun Document.parseMangaDetails(slug: String): SManga {
        val main = selectFirst("main")!!
        return SManga.create().apply {
            url = slug
            title = main.selectFirst(".card-body .card-title")!!.text()
            thumbnail_url = main.selectFirst("article.card figure > img")?.attr("abs:src")
            description = main.selectFirst(".card-body > p")?.text()
            genre = main.select(""".card-body > ul > li > a[href*="?generos"]""").joinToString { it.text() }
            status = parseStatus(main.selectFirst("""figure > ul a[href*="?estados"]""")?.text())
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "completa" -> SManga.COMPLETED
        "en curso" -> SManga.ONGOING
        "hiatus" -> SManga.ON_HIATUS
        "cancelada", "abandonada" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.selectFirst(".card-title")!!.text()
        date_upload = DATE_FORMAT.tryParseZonedDateTime(
            element.selectFirst("time")?.attr("datetime")?.substringBeforeLast('(')?.trim(),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        var document = fetchDocument("$activeBaseUrl${chapter.url}".toHttpUrl())
        if (document.selectFirst("button > span:contains(permitir nsfw)") != null) {
            // Gated chapter served despite the cookie (stale domain hop): retry once.
            document = fetchDocument("$activeBaseUrl${chapter.url}".toHttpUrl())
        }
        // Pages are never SSR'd as <img>; their CDN URLs only appear inside the
        // serialized Qwik state blob, in order. The blob can carry previews of
        // adjacent chapters, so prefer URLs scoped to this chapter's id.
        val images = PAGE_URL_REGEX.findAll(document.html())
            .map { it.value }
            .distinct()
            .toList()
        val chapterId = chapter.url.substringAfter("/capitulo/").substringBefore("/")
        val pages = images.filter { "/$chapterId/" in it }.ifEmpty { images }
        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", "$activeBaseUrl/")
        .header("Origin", activeBaseUrl)
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "cross-site")
        .build()

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("La búsqueda por texto no está disponible; usa los filtros"),
        Filter.Separator(),
        SortByFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    companion object {
        private const val LANDING_URL = "https://ikigaimangas.com"
        private const val NSFW_HEADER = "X-Ikigai-Nsfw"
        private const val NSFW_COOKIE = "is-adult-enabled"
        private const val CHAPTER_SELECTOR = "section.card > ul.grid a.card"
        private val DOMAIN_REGEX = Regex("""i\("(https://[^"]+)""")
        private val PAGE_URL_REGEX = Regex("""https://[a-z0-9]+\.ikigaimangas\.cloud/series/\d+/\d+/[^"\\\s]+""")
        private val SERIES_SLUG_REGEX = Regex("""","([a-z0-9]+(?:-[a-z0-9]+)*)","(comic|manga|novel)""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH)
    }
}
