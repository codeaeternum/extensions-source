package eu.kanade.tachiyomi.extension.all.mangaplus

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
import keiyoushi.utils.decodeHex
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseAsProto
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.UUID

@Source
abstract class MangaPlus : KeiSource() {

    private val internalLangName: String
        get() = when (lang) {
            "en" -> "eng"
            "es" -> "esp"
            "fr" -> "fra"
            "id" -> "ind"
            "pt-BR" -> "ptb"
            "ru" -> "rus"
            "th" -> "tha"
            "vi" -> "vie"
            "de" -> "deu"
            else -> throw IllegalStateException("Unsupported language: $lang")
        }

    private val internalLangCode: Int
        get() = when (lang) {
            "en" -> LANGUAGE_ENGLISH
            "es" -> LANGUAGE_SPANISH
            "fr" -> LANGUAGE_FRENCH
            "id" -> LANGUAGE_INDONESIAN
            "pt-BR" -> LANGUAGE_PORTUGUESE_BR
            "ru" -> LANGUAGE_RUSSIAN
            "th" -> LANGUAGE_THAI
            "vi" -> LANGUAGE_VIETNAMESE
            "de" -> LANGUAGE_GERMAN
            else -> throw IllegalStateException("Unsupported language: $lang")
        }

    private val apiHost = API_URL.toHttpUrl().host
    private val siteHost get() = baseUrl.toHttpUrl().host

    private val sessionToken = UUID.randomUUID().toString()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::sessionTokenInterceptor)
        addInterceptor(::imageDecryptInterceptor)
        rateLimit(1) { it.host == apiHost }
        rateLimit(2) { it.host == siteHost }
    }

    private fun sessionTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != apiHost) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("SESSION-TOKEN", sessionToken)
                .build(),
        )
    }

    // Encrypted pages carry their XOR key as a URL fragment ("#a1b2..."), which
    // OkHttp keeps client-side only; the stream is decrypted on the fly.
    private fun imageDecryptInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val key = request.url.fragment?.decodeHex()
            ?.takeIf { it.isNotEmpty() }
            ?: return response

        val decrypted = XorDecryptSource(response.body.source(), key)
        return response.newBuilder()
            .body(
                decrypted.buffer().asResponseBody(
                    response.body.contentType(),
                    response.body.contentLength(),
                ),
            )
            .build()
    }

    private class XorDecryptSource(
        delegate: okio.Source,
        private val key: ByteArray,
    ) : ForwardingSource(delegate) {
        private val chunk = Buffer()
        private var offset = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(chunk, byteCount)
            if (read == -1L) return -1L

            val bytes = chunk.readByteArray()
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() xor (key[offset++ % key.size].toInt() and 0xFF)).toByte()
            }
            sink.write(bytes)
            return read
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = client.get("$API_URL/title_list/rankingV2?lang=$internalLangName&type=hottest&clang=$internalLangName")
            .parseAsProto<MangaPlusResponse>()

        val titles = result.successOrThrow().titleRankingView!!.rankedTitles
            .flatMap(RankedTitle::titles)
            .filterByLang()

        return MangasPage(titles.map(Title::toSManga), hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get("$API_URL/web/web_homeV4?lang=$internalLangName&clang=$internalLangName")
            .parseAsProto<MangaPlusResponse>()

        val homeView = result.successOrThrow().webHomeView!!
        val updated = homeView.groups.flatMap(UpdatedTitleGroup::titles) + listOfNotNull(homeView.featured?.title)
        val titles = updated
            .sortedByDescending(UpdatedTitle::updatedAt)
            .flatMap(UpdatedTitle::latestChapters)
            .mapNotNull(LatestChapter::title)
            .filterByLang()

        return MangasPage(titles.map(Title::toSManga), hasNextPage = false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val typeFilter = filters.firstInstance<TypeFilter>()
        val genreSlug = filters.firstInstanceOrNull<GenreFilter>()?.slug.orEmpty()

        // allV2 returns the whole catalog; all_v3 is scoped to the status filter.
        val titles = if (query.isNotEmpty() && genreSlug.isEmpty() && typeFilter.isDefault) {
            client.get("$API_URL/title_list/allV2").parseAsProto<MangaPlusResponse>()
                .successOrThrow().allTitlesView!!.allTitlesGroup
                .flatMap(AllTitlesGroup::titles)
                .filterByLang()
        } else {
            client.get(allTitlesV3Url(typeFilter.type)).parseAsProto<MangaPlusResponse>()
                .successOrThrow().allTitlesViewV3!!.titles
                .filter { genreSlug.isEmpty() || it.genres.any { genre -> genre.slug == genreSlug } }
                .map(AllTitlesV3Entry::title)
                .filterByLang()
        }

        val filtered = titles.filter { title ->
            query.isEmpty() ||
                title.name.contains(query, ignoreCase = true) ||
                title.author.orEmpty().contains(query, ignoreCase = true)
        }

        return MangasPage(filtered.map(Title::toSManga), hasNextPage = false)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val result = client.get(allTitlesV3Url(TypeFilter.DEFAULT_TYPE)).parseAsProto<MangaPlusResponse>()
        return result.success?.allTitlesViewV3?.tags.orEmpty().toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(TypeFilter())
            val genres = data?.parseAs<List<TagName>>().orEmpty()
            if (genres.isNotEmpty()) {
                add(GenreFilter(genres))
            }
        },
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in MANGAPLUS_HOSTS) return null

        val segments = url.pathSegments
        val titleId = when {
            segments.firstOrNull() == "titles" -> segments.getOrNull(1)
            segments.firstOrNull() == "viewer" -> segments.getOrNull(1)?.let { titleIdFromChapter(it) }
            segments.lastOrNull() == "sns_share" -> url.queryParameter("title_id")
            else -> null
        } ?: return null

        return getTitleDetail(titleId)
            .takeIf { it.title.language == internalLangCode }
            ?.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = getTitleDetail(manga.url.substringAfterLast("/"))
        if (detail.title.language != internalLangCode) {
            throw Exception("This title is not available in this language")
        }

        val chapterList = detail.chapterList
            .filterNot(Chapter::isExpired)
            .map(Chapter::toSChapter)
            .reversed()

        return SMangaUpdate(detail.toSManga(), chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val result = client.get(mangaViewerUrl(chapterId)).parseAsProto<MangaPlusResponse>()

        val viewer = result.successOrThrow { popup ->
            when {
                popup?.subject == NOT_FOUND_SUBJECT -> "This chapter has expired"
                !popup?.body.isNullOrEmpty() -> popup.body
                else -> "Unknown error"
            }
        }.mangaViewer!!

        return viewer.pages
            .mapNotNull(MangaPlusPage::mangaPage)
            .mapIndexed { i, page ->
                Page(
                    i,
                    url = viewer.viewToken.orEmpty(),
                    imageUrl = page.imageUrl + (page.encryptionKey?.let { "#$it" }.orEmpty()),
                )
            }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headersBuilder().set("Plus-Vw-Token", page.url).build())
        .get()
        .build()

    private suspend fun getTitleDetail(titleId: String): TitleDetailView {
        val result = client.get("$API_URL/title_detailV3?title_id=$titleId&clang=$internalLangName")
            .parseAsProto<MangaPlusResponse>()

        return result.successOrThrow { popup ->
            when {
                popup?.subject == NOT_FOUND_SUBJECT -> "This title has been removed"
                !popup?.body.isNullOrEmpty() -> popup.body
                else -> "Unknown error"
            }
        }.titleDetailView!!
    }

    private suspend fun titleIdFromChapter(chapterId: String): String? {
        val result = client.get(mangaViewerUrl(chapterId)).parseAsProto<MangaPlusResponse>()
        return result.success?.mangaViewer?.titleId?.takeIf { it != 0 }?.toString()
    }

    private fun mangaViewerUrl(chapterId: String): String = "$API_URL/manga_viewer_v3".toHttpUrl().newBuilder()
        .addQueryParameter("chapter_id", chapterId)
        .addQueryParameter("split", "no")
        .addQueryParameter("img_quality", "super_high")
        .addQueryParameter("clang", internalLangName)
        .toString()

    private fun allTitlesV3Url(type: String): String = "$API_URL/title_list/all_v3?type=$type&lang=$internalLangName&clang=$internalLangName"

    private fun List<Title>.filterByLang(): List<Title> = filter { it.titleId != 0 && it.language == internalLangCode }.distinctBy(Title::titleId)

    private fun MangaPlusResponse.successOrThrow(
        errorMessage: (Popup?) -> String = { it?.body?.takeIf(String::isNotEmpty) ?: "Unknown error" },
    ): SuccessResult = success ?: throw Exception(errorMessage(error?.popupFor(internalLangCode)))
}

private const val API_URL = "https://jumpg-webapi.tokyo-cdn.com/api"
private const val NOT_FOUND_SUBJECT = "Not Found"

private val MANGAPLUS_HOSTS = listOf(
    "mangaplus.shueisha.co.jp",
    "www.mangaplus.shueisha.co.jp",
    "jumpg-webapi.tokyo-cdn.com",
    "www.jumpg-webapi.tokyo-cdn.com",
)
