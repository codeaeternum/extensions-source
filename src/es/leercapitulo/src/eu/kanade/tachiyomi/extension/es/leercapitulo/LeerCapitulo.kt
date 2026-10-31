package eu.kanade.tachiyomi.extension.es.leercapitulo

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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapitulo : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 3.seconds) { it.host == baseUrl.toHttpUrl().host }

    // The site has no dedicated popular listing; the homepage slider is its "Tendencias" section.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangas = client.get(baseUrl).asJsoup()
            .select(".lc-slide")
            .map { it.toSManga("a.lc-slide-name") }

        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get(baseUrl).asJsoup()
            .select("article.lc-release")
            .map { it.toSManga("a.lc-release-title") }

        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.filterIsInstance<UriPartFilter>().forEach { filter ->
                filter.toUriPart().takeIf { it.isNotEmpty() }?.let { addQueryParameter(filter.param, it) }
            }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select("article.lc-card").map { it.toSManga("a.lc-card-name") }

        return MangasPage(mangas, document.selectFirst("a.page-link[rel=next]") != null)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Los filtros se pueden combinar con la búsqueda por texto."),
        GenreFilter(),
        ThemeFilter(),
        DemographicFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val mangaPath = when {
            // /manga/<id>/<slug>/
            segments.size >= 3 && segments[0] == "manga" -> "/manga/${segments[1]}/${segments[2]}/"
            // /leer/<id>/<slug>/<chapter>/ resolves to the manga page
            segments.size >= 4 && segments[0] == "leer" -> "/manga/${segments[1]}/${segments[2]}/"
            else -> return null
        }

        val response = client.get(baseUrl + mangaPath)
        // The site redirects to the canonical slug when it doesn't match the id.
        return response.asJsoup().mangaDetails().apply {
            this.url = response.request.url.encodedPath
        }
    }

    // Manga details and the chapter list live on the same page, so both flags are
    // served from a single request.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(document.mangaDetails(), document.chapterList())
    }

    private fun Element.toSManga(nameSelector: String) = SManga.create().apply {
        val name = selectFirst(nameSelector)!!
        setUrlWithoutDomain(name.absUrl("href"))
        title = name.text()
        thumbnail_url = selectFirst("img")?.absUrl("src")
    }

    private fun Document.mangaDetails() = SManga.create().apply {
        title = selectFirst("h1")!!.text()
        thumbnail_url = selectFirst(".lc-cover-lg img")?.absUrl("src")

        val altNames = selectFirst("h1 + p.lc-muted")?.text()
            ?.split(" · ")
            ?.filter { it.isNotBlank() && it != title }
            ?.joinToString(" · ")

        // #sinopsis falls back to a placeholder p.lc-muted when empty.
        description = buildString {
            selectFirst("#sinopsis p:not(.lc-muted)")?.text()?.takeIf { it.isNotEmpty() }?.let(::append)
            if (!altNames.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alt name(s): ").append(altNames)
            }
        }

        genre = (
            select("a.badge[href^='/manga/?genre='], a.badge[href^='/manga/?theme=']")
                .map { it.text() } + listOfNotNull(fact("Demografia"))
            )
            .joinToString()
        author = fact("Autor")
        artist = fact("Dibujo")
        status = fact("Estado").toStatus()
    }

    private fun Document.fact(label: String): String? = select(".lc-facts li")
        .firstOrNull { it.selectFirst(".k")?.text() == label }
        ?.children()?.lastOrNull()?.text()

    private fun Document.chapterList() = select("a.lc-chapter-row").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".n")!!.text()
            date_upload = DATE_FORMAT.tryParseDate(element.selectFirst(".d")?.text(), ZoneOffset.UTC)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select("#lcPages img[data-src]")
        .mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("data-src")) }

    private fun String?.toStatus() = when (this) {
        "Ongoing" -> SManga.ONGOING
        "Paused" -> SManga.ON_HIATUS
        "Completed" -> SManga.COMPLETED
        "Cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
