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
import keiyoushi.utils.tryParseDate
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class NovelCool : KeiSource() {

    private val siteLang: String get() = if (lang == "pt-BR") "br" else lang

    private val apiUrl = "https://api.novelcool.com"

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1)

    private val pageClient by lazy {
        client.newBuilder()
            .addInterceptor(::jsRedirect)
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = commonApiRequest("$apiUrl/elite/hot/", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = commonApiRequest("$apiUrl/elite/latest/", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = commonApiRequest("$apiUrl/book/search/", page, query)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val details = SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h1.bookinfo-title")!!.text()
            description = document.selectFirst("div.bk-summary-txt")?.text()
            genre = document.select(".bookinfo-category-list a").joinToString { it.text() }
            author = document.selectFirst(".bookinfo-author > a")?.attr("title")
            thumbnail_url = document.selectFirst(".bookinfo-pic-img")?.attr("abs:src")
            status = document.select(".bookinfo-category-list a").first()?.text().parseStatus()
        }

        val chapterList = document.select(".chapter-item-list a").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.attr("title")
                date_upload = DATE_FORMATTER.tryParseDate(element.select(".chapter-item-time").text())
            }
        }

        return SMangaUpdate(details, chapterList)
    }

    private fun String?.parseStatus(): Int {
        this ?: return SManga.UNKNOWN
        return when {
            this.lowercase() in completedStatusList -> SManga.COMPLETED
            this.lowercase() in ongoingStatusList -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        var doc = pageClient.get(getChapterUrl(chapter)).asJsoup()

        // Chapter pages redirect (HTTP 302) to an intermediate "choose a source" page on a
        // partner domain (e.g. techsmartideas.com). That page contains a.vision-button links
        // which point to the actual image server (e.g. financemasterpro.com). This is the
        // same shared infrastructure used by NineAnime.
        val serverUrl = doc.selectFirst("a.vision-button")?.attr("abs:href")

        if (serverUrl != null) {
            val serverHeaders = headers.newBuilder()
                .set("Referer", doc.baseUri())
                .build()
            doc = pageClient.get(serverUrl, serverHeaders).asJsoup()
        }

        // Parse all_imgs_url from the script using a robust approach: extract the array
        // content as a string and then find all quoted http URLs within it. This avoids
        // fragile JSON parsing and trailing-comma issues in the original JS array.
        val scriptData = doc.select("script:containsData(all_imgs_url)").firstOrNull()?.data()

        if (scriptData != null) {
            val arrayContent = scriptData
                .substringAfter("all_imgs_url: [")
                .substringBefore("]")
            val images = imageUrlRegex.findAll(arrayContent)
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()

            if (images.isNotEmpty()) {
                return images.mapIndexed { idx, img -> Page(idx, imageUrl = img) }
            }
        }

        return singlePageParse(doc)
    }

    private fun singlePageParse(document: Document): List<Page> = document.selectFirst(".mangaread-pagenav > .sl-page")?.select("option")
        ?.mapIndexed { idx, page ->
            Page(idx, url = page.attr("value"))
        } ?: emptyList()

    override suspend fun getImageUrl(page: Page): String {
        val document = client.get(page.url).asJsoup()
        return document.select(".mangaread-manga-pic").attr("src")
    }

    private fun jsRedirect(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = request.headers.newBuilder()
            .removeAll("Accept-Encoding")
            .build()
        val response = chain.proceed(request.newBuilder().headers(headers).build())

        if (response.header("Content-Type")?.contains("text/html") != true) {
            return response
        }

        val responseBody = response.peekBody(Long.MAX_VALUE).string()
        val document = Jsoup.parse(responseBody)
        val script = document.selectFirst("script:containsData(window.location.href)")?.html()
            ?: return response

        val jsRedirect = JS_REDIRECT_REGEX.find(script)?.groupValues?.get(1)
            ?: return response

        val requestUrl = response.request.url

        val url = requestUrl.resolve(jsRedirect)
            ?: return response

        response.close()

        val newHeaders = request.headers.newBuilder()
            .set("Referer", requestUrl.toString())
            .build()

        return chain.proceed(
            request.newBuilder()
                .url(url)
                .headers(newHeaders)
                .build(),
        )
    }

    private suspend fun commonApiRequest(url: String, page: Int, query: String? = null): MangasPage {
        val payload = NovelCoolBrowsePayload(
            appId = APP_ID,
            lang = siteLang,
            query = query,
            type = "manga",
            page = page.toString(),
            size = SIZE.toString(),
            secret = APP_SECRET,
        )

        val browse = client.post(url, payload.toJsonRequestBody()).parseAs<NovelCoolBrowseResponse>()
        val mangas = browse.list?.map {
            it.toSManga().apply {
                setUrlWithoutDomain(it.url)
            }
        }.orEmpty()

        return MangasPage(mangas, browse.list?.size == SIZE)
    }

    companion object {
        private const val APP_ID = "202201290625004"
        private const val APP_SECRET = "c73a8590641781f203660afca1d37ada"
        private const val SIZE = 20

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

        // Matches any http/https URL inside single or double quotes within the all_imgs_url array.
        // Using the same approach as NineAnime which shares the same image-serving infrastructure.
        private val imageUrlRegex = Regex("""["'](https?://[^"']+)["']""")

        private val JS_REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")

        // copied from Madara
        private val completedStatusList: Array<String> = arrayOf(
            "completed",
            "completo",
            "completado",
            "concluído",
            "concluido",
            "finalizado",
            "terminé",
            "hoàn thành",
        )

        private val ongoingStatusList: Array<String> = arrayOf(
            "ongoing", "Продолжается", "updating", "em lançamento", "em lançamento", "em andamento",
            "em andamento", "en cours", "ativo", "lançando", "Đang Tiến Hành", "devam ediyor",
            "devam ediyor", "in corso", "in arrivo", "en curso", "en curso", "emision",
            "curso", "en marcha", "Publicandose", "en emision",
        )
    }
}
