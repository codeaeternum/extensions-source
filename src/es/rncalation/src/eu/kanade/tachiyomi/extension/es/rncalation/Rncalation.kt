package eu.kanade.tachiyomi.extension.es.rncalation

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Rncalation (Astro SSR) sits behind two gates: Cloudflare, resolved by the
// app's WebView, and the site's own "mv_ck" check — a stub page ("Verificando
// tu navegador…", mv-verifying) that sets the cookie and reloads. The
// interceptor below detects that stub and retries once with the cookie.
//
// Some chapters are paid/early-access (coins, subscription, ad-unlock). Two
// ways to unlock them without shipping credentials in the jar: sign in through
// the WebView (Google OAuth, shared cookie jar), or paste the session Cookie
// header in the source settings — the value lives on-device only.
@Source
abstract class Rncalation : KeiSource(), ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_SESSION_COOKIE
            title = "Cookie de sesión"
            summary = "Para capítulos con 🔒: copia el header Cookie de rncalation.online desde tu navegador con sesión iniciada."
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun sessionCookie(): String? = preferences.getString(PREF_SESSION_COOKIE, "").orEmpty()
        .trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
        .ifEmpty { null }

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
        .addInterceptor { chain ->
            val request = chain.request()
            val cookie = sessionCookie()
            if (cookie == null || request.url.host != baseUrl.toHttpUrl().host) {
                chain.proceed(request)
            } else {
                val merged = request.header("Cookie")?.let { "$it; $cookie" } ?: cookie
                chain.proceed(request.newBuilder().header("Cookie", merged).build())
            }
        }
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (
                response.headers("Set-Cookie").any { it.startsWith("mv_ck=") } &&
                response.peekBody(4096).string().contains("mv-verifying")
            ) {
                response.close()
                chain.proceed(chain.request())
            } else {
                response
            }
        }

    override suspend fun getPopularManga(page: Int): MangasPage = libraryPage(sort = "views", page = page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = libraryPage(sort = "updated", page = page)

    // /library?q= is the site's own search form backend; it combines with the
    // sidebar filters (type/status/genre) and pagination.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/library".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.filterIsInstance<UriPartFilter>().forEach { filter ->
                filter.toUriPart().takeIf { it.isNotEmpty() }?.let { addQueryParameter(filter.param, it) }
            }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    private suspend fun libraryPage(sort: String, page: Int): MangasPage {
        val url = "$baseUrl/library".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select(".lib-grid a.comic-card").mapNotNull { element ->
            // "Novel" entries are text-only and would render as empty readers.
            if (element.selectFirst("span.absolute.top-2.left-2")?.text()?.contains("Novel", ignoreCase = true) == true) {
                return@mapNotNull null
            }
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("p.leading-snug")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        // The "next" arrow is the last .page-btn--nav and is an <a> only while
        // a following page exists (a disabled <span> on the last page).
        val hasNextPage = document.select("nav.lib-pagination .page-btn--nav").lastOrNull()?.tagName() == "a"
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Los filtros se pueden combinar con la búsqueda por texto."),
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val mangaPath = when {
            segments.getOrNull(0) == "comics" -> segments.getOrNull(1)?.let { "/comics/$it" }
            // Reader pages carry a "Volver" backlink to the series ficha.
            segments.getOrNull(0) == "leer" -> client.get(baseUrl + url.encodedPath).asJsoup()
                .selectFirst("a.bar-back-link[href^='/comics/']")
                ?.attr("href")
            else -> null
        } ?: return null

        return fetchMangaDetails(SManga.create().apply { this.url = mangaPath })
    }

    // Details live on the ficha; the full chapter list comes from the
    // /chapters fragment endpoint (the ficha only embeds the first ~30).
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async { if (fetchDetails) fetchMangaDetails(manga) else manga }
        val chaptersDeferred = async { if (fetchChapters) fetchChapterList(manga) else chapters }
        SMangaUpdate(manga = detailsDeferred.await(), chapters = chaptersDeferred.await())
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga = client.get(baseUrl + manga.url).asJsoup().let { document ->
        manga.apply {
            title = document.selectFirst("h1")!!.text().trim()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
            description = document.selectFirst("#comic-description")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            status = document.fact("Estado").toStatus()
            genre = document.fact("Géneros")
            author = document.fact("Autor") ?: document.selectFirst("a[href^='/groups/']")?.text()?.trim()
            artist = document.fact("Arte")
        }
    }

    // Sidebar rows are "<label> <value>" pairs without stable classes beyond the
    // flex container, so the row is matched by its label text.
    private fun Document.fact(label: String): String? = select("div.flex.items-baseline.justify-between")
        .firstOrNull { it.children().firstOrNull()?.text()?.trim() == label }
        ?.children()?.lastOrNull()?.text()?.trim()

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val result = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = client.get("$baseUrl/comics/$slug/chapters?page=$page")
            val totalPages = response.header("x-pages")?.toIntOrNull() ?: 1
            val batch = response.asJsoup().select("[data-chapter-num]").map { it.toSChapter(manga.url) }
            result += batch
            if (batch.isEmpty() || page >= totalPages) break
            page++
        }
        // A configured session cookie should unlock entitled chapters; if locked
        // rows still appear, probe /user/profile once — an anonymous view
        // (login redirect or login form in the body) means the cookie expired,
        // which is reported instead of silent locks.
        if (sessionCookie() != null && result.any { it.url == manga.url }) {
            val expired = client.get("$baseUrl/user/profile").use { probe ->
                probe.request.url.encodedPath.startsWith("/auth/login") ||
                    probe.asJsoup().selectFirst("form[action*='login'], a[href^='/auth/login']") != null
            }
            if (expired) {
                throw Exception("Sesión de RNCALATION expirada — renueva la cookie en los ajustes de la fuente")
            }
        }
        return result
    }

    // Row variants carrying data-chapter-*: readable = <a href="/leer/hash">,
    // paywalled = <a href="/auth/login?redirect=…"> (lock icon), early-access =
    // <div> with a subscription link (clock icon). Only /leer/ links are
    // readable; gated rows keep the manga URL so opening them lands on the
    // ficha where the chapter can be unlocked in WebView.
    private fun Element.toSChapter(mangaUrl: String): SChapter {
        val label = attr("data-chapter-label").trim()
        val link = takeIf { tagName() == "a" && attr("href").startsWith("/leer/") }
            ?: selectFirst("a[href^='/leer/']")

        return SChapter.create().apply {
            if (link != null) {
                setUrlWithoutDomain(link.absUrl("href"))
                name = label
            } else {
                url = mangaUrl
                val icon = if (selectFirst(".lucide-clock") != null) "⏳ " else "🔒 "
                name = icon + label
            }
            chapter_number = attr("data-chapter-num").toFloatOrNull() ?: -1f
            val ts = attr("data-ts").ifEmpty { selectFirst("[data-ts]")?.attr("data-ts").orEmpty() }
            date_upload = Instant.tryParse(ts)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select("img.page-img")
        .mapIndexed { i, img ->
            // First ~2 pages are eager (src), the rest lazy (data-src).
            Page(i, imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") })
        }

    private fun String?.toStatus() = when (this?.lowercase()) {
        "en emisión", "en emision", "ongoing" -> SManga.ONGOING
        "completado", "completed" -> SManga.COMPLETED
        "en pausa", "hiatus" -> SManga.ON_HIATUS
        "cancelado", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    companion object {
        private const val PREF_SESSION_COOKIE = "session_cookie"
    }
}
