package eu.kanade.tachiyomi.extension.es.lectortmoo

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class Lectortmoo : KeiSource() {

    private val apiUrl: String get() = "$baseUrl/api"

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchList(listUrl(page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchList(listUrl(page).newBuilder().addQueryParameter("order", "latest").build())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchList(listUrl(page, query, filters))

    private fun listUrl(page: Int, query: String = "", filters: FilterList = FilterList()): HttpUrl = "$apiUrl/mangas".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
        if (query.isNotBlank()) addQueryParameter("q", query)
        filters.filterIsInstance<QueryFilter>().forEach { f ->
            if (f.selected.isNotEmpty()) addQueryParameter(f.param, f.selected)
        }
    }.build()

    private suspend fun fetchList(url: HttpUrl): MangasPage {
        val response = client.get(url).parseAs<MangaListResponse>()
        val meta = response.meta
        return MangasPage(
            response.data.map { it.toSManga() },
            meta != null && meta.currentPage < meta.lastPage,
        )
    }

    private fun MangaItemDto.toSManga() = SManga.create().apply {
        url = slug
        title = this@toSManga.title
        thumbnail_url = cover
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = client.get("$apiUrl/mangas/${manga.url}").parseAs<MangaDetailResponse>().data
        return SMangaUpdate(detail.toSManga(), detail.toChapterList())
    }

    private fun MangaDetailDto.toSManga() = SManga.create().apply {
        url = slug
        title = this@toSManga.title
        thumbnail_url = cover
        description = buildString {
            synopsis?.trim()?.takeIf { it.isNotEmpty() }?.let(::append)
            val altNames = listOfNotNull(titleEn, titleJa).filter { it.isNotBlank() }
            if (altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alt name(s): ").append(altNames.joinToString(" · "))
            }
        }
        genre = genres.joinToString { it.name }
        author = this@toSManga.author
        artist = this@toSManga.artist
        status = this@toSManga.status.toStatus()
    }

    private fun MangaDetailDto.toChapterList() = chapters.map { ch ->
        SChapter.create().apply {
            url = ch.slug
            name = ch.title ?: "Capítulo ${ch.number.orEmpty()}"
            chapter_number = ch.number?.toFloatOrNull() ?: -1f
            date_upload = Instant.tryParse(ch.publishedAt)
        }
    }.sortedByDescending { it.chapter_number }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$apiUrl/chapters/${chapter.url}").parseAs<ChapterPagesResponse>()
        .data.pages.mapIndexed { i, img -> Page(i, imageUrl = img) }

    // cdn.lectortmoo.com devuelve 403 a cualquier request con Referer del sitio
    // (anti-hotlink). La API no lo necesita: se quitan Referer/Origin de todo.
    override fun Headers.Builder.configureHeaders() = removeAll("Referer").removeAll("Origin")

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .get()
        .build()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null

        val segments = url.pathSegments
        val slug = when {
            segments.size == 2 && segments[0] == "manga" -> segments[1]
            // Chapter slugs can embed a lang suffix (e.g. "-es-la-capitulo-N"),
            // so resolve the parent manga via the chapter endpoint instead.
            segments.size == 1 && segments[0].contains("-capitulo-") ->
                runCatching {
                    client.get("$apiUrl/chapters/${segments[0]}").parseAs<ChapterPagesResponse>().data.manga?.slug
                }.getOrNull()
            else -> return null
        }

        return runCatching {
            client.get("$apiUrl/mangas/$slug").parseAs<MangaDetailResponse>().data.toSManga()
        }.getOrNull()
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/genres").parseAs()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>().orEmpty()
            .distinctBy { it.slug }
            .sortedBy { it.name.lowercase() }
            .map { it.name to it.slug }

        return FilterList(
            GenreFilter(arrayOf("Todos" to "") + genres),
            TypeFilter(),
            StatusFilter(),
        )
    }

    private fun String?.toStatus() = when (this?.lowercase()) {
        null -> SManga.UNKNOWN
        else -> when {
            contains("curso") || contains("ongoing") -> SManga.ONGOING
            contains("final") || contains("complet") -> SManga.COMPLETED
            contains("pausa") || contains("hiatus") -> SManga.ON_HIATUS
            contains("cancel") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}
