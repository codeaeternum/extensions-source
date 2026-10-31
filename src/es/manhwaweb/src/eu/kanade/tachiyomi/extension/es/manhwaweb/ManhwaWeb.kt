package eu.kanade.tachiyomi.extension.es.manhwaweb

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ManhwaWeb : KeiSource() {

    private val apiUrl = "https://api.manhwaweb.com"

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = client.get("$apiUrl/manhwa/nuevos").parseAs<PopularPayloadDto>()
        return MangasPage(result.toSMangaList(), hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get("$apiUrl/latest/new-manhwa").parseAs<LatestPayloadDto>()
        return MangasPage(result.toSMangaList(), hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size != 2) return null
        val slug = when (segments[0]) {
            "manhwa", "manga" -> segments[1]
            // Reader URLs carry the chapter id; the chapter payload holds the
            // manga's slug.
            "leer" -> client.get("$apiUrl/chapters/see/${segments[1]}")
                .parseAs<PagesPayloadDto>().mangaSlug
            else -> null
        } ?: return null
        return getDetailsBySlug(slug).toSManga()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/manhwa/library".toHttpUrl().newBuilder()
            .addQueryParameter("buscar", query)

        filters.forEach { filter ->
            when (filter) {
                is QueryFilter -> url.addQueryParameter(filter.param, filter.selected)

                is GenreFilter -> url.addQueryParameter(
                    "generes",
                    filter.state.filter { it.state }.joinToString("a") { it.id.toString() },
                )

                is SortByFilter -> {
                    url.addQueryParameter("order_item", filter.selected)
                    url.addQueryParameter(
                        "order_dir",
                        if (filter.state?.ascending == true) "asc" else "desc",
                    )
                }

                else -> {}
            }
        }

        val result = client.get(url.addQueryParameter("page", (page - 1).toString()).build())
            .parseAs<LibraryPayloadDto>()

        return MangasPage(result.toSMangaList(), result.hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = getDetailsBySlug(manga.url.substringAfterLast("/"))
        return SMangaUpdate(
            manga = if (fetchDetails) dto.toSManga() else manga,
            chapters = if (fetchChapters) dto.toChapterList() else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val result = client.get("$apiUrl/chapters/see/${chapter.url.substringAfterLast("/")}")
            .parseAs<PagesPayloadDto>()

        return result.images
            .filter { it.startsWith("http") }
            .mapIndexed { index, image -> Page(index, imageUrl = image) }
    }

    // imgNmw.xyz hosts reject image requests without the site's Referer, while
    // the site marks every other image host as no-referrer.
    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!.toHttpUrl()
        if (url.host.endsWith("mw.xyz")) return super.imageRequest(page)

        return Request.Builder()
            .url(url)
            .headers(headers.newBuilder().removeAll("Referer").removeAll("Origin").build())
            .get()
            .build()
    }

    private suspend fun getDetailsBySlug(slug: String) = client.get("$apiUrl/manhwa/see/$slug").parseAs<ComicDetailsDto>()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        DemographyFilter(),
        StatusFilter(),
        EroticFilter(),
        Filter.Separator(),
        GenreFilter(),
        Filter.Separator(),
        SortByFilter(),
    )
}
