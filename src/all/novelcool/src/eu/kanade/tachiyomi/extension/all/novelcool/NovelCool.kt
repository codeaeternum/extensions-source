package eu.kanade.tachiyomi.extension.all.novelcool

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

@Source
abstract class NovelCool : KeiSource() {

    private val apiUrl = "https://api.novelcool.com"
    private val apiLang: String get() = if (lang == "pt-BR") "br" else lang

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1)

    override suspend fun getPopularManga(page: Int): MangasPage = fetchBookList("$apiUrl/elite/hot/", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchBookList("$apiUrl/elite/latest/", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchBookList("$apiUrl/book/search/", page, query.trim())

    private suspend fun fetchBookList(url: String, page: Int, keyword: String? = null): MangasPage {
        val payload = BrowsePayload(
            appId = APP_ID,
            secret = APP_SECRET,
            lang = apiLang,
            lcType = "manga",
            page = page.toString(),
            pageSize = PAGE_SIZE.toString(),
            keyword = keyword?.takeUnless { it.isEmpty() },
        )

        val list = client.post(url, payload.toJsonRequestBody()).parseAs<MangaListResponse>().list
        val entries = list.filterNot(MangaDto::isTextNovel).map { it.toSManga() }
        return MangasPage(entries, hasNextPage = list.size == PAGE_SIZE)
    }

    // Manga details and the chapter list are separate API endpoints, so honor the
    // flags and fetch them concurrently when both are needed.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val bookId = manga.url.substringBefore('/')
        val infoAsync = async { if (fetchDetails) fetchBookInfo(bookId) else null }
        val chaptersAsync = async { if (fetchChapters) fetchChapterList(bookId) else chapters }

        val info = infoAsync.await()
        SMangaUpdate(
            manga = info?.toSManga() ?: manga,
            // a text-first book is a novel mislabeled as manga; its image
            // chapters (if any) are stray extras, not a real chapter list
            chapters = if (info?.isTextNovel == true) emptyList() else chaptersAsync.await(),
        )
    }

    private suspend fun fetchBookInfo(bookId: String): BookInfoDto = client.post(
        "$apiUrl/book/info/",
        BookPayload(APP_ID, APP_SECRET, apiLang, bookId).toJsonRequestBody(),
    ).parseAs<BookInfoResponse>().info

    private suspend fun fetchChapterList(bookId: String): List<SChapter> = client.post(
        "$apiUrl/chapter/book_list/",
        BookPayload(APP_ID, APP_SECRET, apiLang, bookId).toJsonRequestBody(),
    ).parseAs<ChapterListResponse>().list
        .filter { it.isReadable }
        .map { it.toSChapter() }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.post(
        "$apiUrl/chapter/info/",
        ChapterPayload(APP_ID, APP_SECRET, apiLang, chapter.url).toJsonRequestBody(),
    ).parseAs<ChapterInfoResponse>().info.toPageList()

    // SManga.url is "<book_id>/<visit_path>"; the site's manga pages require the real slug.
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url.substringAfter('/')}.html"

    // Chapter reader pages resolve on the trailing numeric id; the slug segment can be anything.
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/x/${chapter.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("novelcool.com")) return null
        val path = url.pathSegments
        val bookId = when {
            // /chapter/<slug>/<chapter_id>.html - the site also accepts a trailing slash form
            path.size == 3 && path[0] == "chapter" -> runCatching {
                chapterInfo(path[2].substringBeforeLast('.')).bookId
            }.getOrNull()
            // /novel/<slug>.html - the book id only exists in the page markup
            path.size == 2 && path[0] == "novel" -> fetchBookId(url)
            else -> null
        } ?: return null

        val info = runCatching { fetchBookInfo(bookId) }.getOrNull()
        return info?.takeUnless { it.isTextNovel }?.toSManga()
    }

    private suspend fun chapterInfo(chapterId: String): ChapterInfoDto = client.post(
        "$apiUrl/chapter/info/",
        ChapterPayload(APP_ID, APP_SECRET, apiLang, chapterId).toJsonRequestBody(),
    ).parseAs<ChapterInfoResponse>().info

    private suspend fun fetchBookId(url: HttpUrl): String? = client.get(url).asJsoup()
        .selectFirst(".book-follow-trigger")
        ?.attr("book_id")
        ?.takeUnless { it.isEmpty() }

    companion object {
        private const val APP_ID = "202201290625004"
        private const val APP_SECRET = "c73a8590641781f203660afca1d37ada"
        private const val PAGE_SIZE = 20
    }
}
