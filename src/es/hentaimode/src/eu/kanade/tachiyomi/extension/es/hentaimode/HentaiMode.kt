package eu.kanade.tachiyomi.extension.es.hentaimode

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HentaiMode : KeiSource() {

    private val host get() = baseUrl.toHttpUrl().host

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2) { it.host == host }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(fetchMangas(baseUrl), hasNextPage = false)

    private fun parseEntries(document: Document): List<SManga> = document.select("""div[class*="book-list"] > a""").map { element ->
        SManga.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            title = element.selectFirst(".book-description > p")!!.text()
            thumbnail_url = element.selectFirst("img")?.absUrl("src")
        }
    }

    private suspend fun fetchMangas(url: String): List<SManga> = client.get(url, ensureSuccess = false).use { response ->
        if (!response.isSuccessful) emptyList() else parseEntries(response.asJsoup())
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // =============================== Search ===============================

    // El buscador del sitio (/buscar?s=) está roto: redirige a la portada.
    // Lo único que responde a texto libre son los endpoints por nombre
    // (/artist?s=, /group?s=) y los índices /lista/* que resuelven a
    // /etiqueta/N, /serie/N y /personaje/N.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = query.trim()
        if (q.isNotEmpty()) return MangasPage(searchByName(q), hasNextPage = false)

        val url = filterUrl(filters) ?: return MangasPage(emptyList(), hasNextPage = false)
        return MangasPage(fetchMangas(url), hasNextPage = false)
    }

    private suspend fun searchByName(query: String): List<SManga> = coroutineScope {
        awaitAll(
            async { fetchMangas(nameUrl("artist", query)) },
            async { fetchMangas(nameUrl("group", query)) },
            async { entityMangas("etiquetas", "etiqueta", query) },
            async { entityMangas("series", "serie", query) },
            async { entityMangas("personajes", "personaje", query) },
        ).flatten().distinctBy { it.url }
    }

    private fun nameUrl(route: String, value: String): String = "$baseUrl/$route".toHttpUrl().newBuilder()
        .addQueryParameter("s", value)
        .toString()

    // Resolves a free-text query against a /lista/<index> name index and fetches
    // the matched entity listing (e.g. /lista/etiquetas -> /etiqueta/42).
    private suspend fun entityMangas(index: String, prefix: String, query: String): List<SManga> {
        val links = client.get("$baseUrl/lista/$index", ensureSuccess = false).use { res ->
            if (res.isSuccessful) res.asJsoup().select("a[href^=/$prefix/]") else null
        } ?: return emptyList()

        val key = query.entityKey()
        val target = links.firstOrNull { it.text().entityKey() == key }
            ?: links.firstOrNull { it.text().entityKey().contains(key) }
            ?: return emptyList()
        return fetchMangas(target.absUrl("href"))
    }

    private fun filterUrl(filters: FilterList): String? {
        for (filter in filters) {
            val path = when (filter) {
                is CategoryFilter -> filter.toPath()
                is IdFilter -> filter.toPath()
                else -> null
            }
            if (path != null) return "$baseUrl/$path"
        }
        return null
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Búsqueda de texto solo por nombre de artista, grupo, etiqueta, serie o personaje (el buscador del sitio está caído)"),
        CategoryFilter(),
        TagFilter(),
        SerieFilter(),
        PersonajeFilter(),
    )

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updated = if (fetchDetails) {
            parseDetails(client.get(getMangaUrl(manga)).asJsoup()).apply {
                url = manga.url
            }
        } else {
            manga
        }

        // one reader entry per manga, so a single synthetic chapter
        val resultChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url.replace("/g/", "/leer/")
                    name = "Chapter"
                    chapter_number = 1F
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(updated, resultChapters)
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        thumbnail_url = document.selectFirst("div#cover img")?.absUrl("src")
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE

        with(document.selectFirst("div#info-block div#info")!!) {
            title = selectFirst("h1")!!.text()
            genre = tags("Categorías")
            author = tags("Grupo")
            artist = tags("Artista")
            description = buildString {
                listOf("Serie", "Tipo", "Personajes", "Idioma").forEach { label ->
                    tags(label)?.let { append(label).append(": ").append(it).append("\n") }
                }
            }
        }
    }

    private fun Element.tags(label: String): String? = select("""div.tag-container:containsOwn($label) a.tag""")
        .joinToString { it.text() }
        .takeIf { it.isNotEmpty() && it != "N/A" }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val script = document.selectFirst("script:containsData(page_image)")?.data()
            ?: return emptyList()

        // the site emits a JS array with a trailing comma ("},]") - strip it
        // so the payload parses as a plain JSON array
        val raw = PAGES_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        val pages = raw.replace(TRAILING_COMMA, "$1").parseAs<List<PageImageDto>>()
        return pages.mapIndexed { index, dto -> Page(index, imageUrl = dto.pageImage) }
    }

    // ============================ URL search ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != host) return null
        // /g/<id> and /leer/<id> share the same gallery id
        val id = when (url.pathSegments.firstOrNull()) {
            "g", "leer" -> url.pathSegments.getOrNull(1)
            else -> null
        } ?: return null

        return runCatching {
            val document = client.get("$baseUrl/g/$id").asJsoup()
            parseDetails(document).apply {
                setUrlWithoutDomain(document.location())
            }
        }.getOrNull()
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host.removePrefix("www.") != host) return MangasPage(emptyList(), false)
        if (url.pathSegments.firstOrNull() in listOf("g", "leer")) return super.getMangasByUrl(url, page)

        // listing URLs like /etiqueta/N, /serie/N, /personaje/N, /ultimos-*,
        // /artist?s=, /group?s= all render the same book grid
        return MangasPage(fetchMangas(url.toString()), hasNextPage = false)
    }

    private fun String.entityKey(): String = lowercase()
        .replace('♀', ' ')
        .replace('♂', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    companion object {
        private val PAGES_REGEX = Regex("""pages\s*=\s*(\[[\s\S]*?]);""")
        private val TRAILING_COMMA = Regex(""",\s*([}\]])""")
        private val WHITESPACE = Regex("\\s+")
    }
}
