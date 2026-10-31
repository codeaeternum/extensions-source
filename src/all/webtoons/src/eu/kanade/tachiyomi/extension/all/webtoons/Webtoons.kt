package eu.kanade.tachiyomi.extension.all.webtoons

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketException
import java.util.Calendar

@Source
abstract class Webtoons : KeiSource() {
    // URL paths use the lowercase language code
    private val langCode: String get() = if (lang == "zh-Hant") "zh-hant" else lang
    private val localeForCookie: String get() = if (lang == "zh-Hant") "zh_TW" else lang

    private val mobileUrl = "https://m.webtoons.com"

    private val mobileHeaders: Headers
        get() = headersBuilder()
            .set("Referer", "$mobileUrl/")
            .removeAll("Origin")
            .build()

    override val supportRelatedMangasBySearch = true

    // android.webkit.CookieManager is not available on every runtime, so the
    // age-gate and locale cookies are sent as a plain header instead, scoped to
    // webtoons.com hosts only (the image CDN on pstatic.net must not see them).
    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val newRequest = if (request.url.host.isWebtoonsHost()) {
                request.newBuilder()
                    .header("Cookie", "ageGatePass=true; locale=$localeForCookie; needGDPR=false")
                    .build()
            } else {
                request
            }
            try {
                chain.proceed(newRequest)
            } catch (e: SocketException) {
                // m.webtoons.com throws an SSL error that can be solved by a simple retry
                chain.proceed(newRequest)
            }
        }
        rateLimit(1) { it.host == mobileUrl.toHttpUrl().host }
    }

    private fun String.isWebtoonsHost() = this == "webtoons.com" || endsWith(".webtoons.com")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ranking = when (page) {
            1 -> "trending"
            2 -> "popular"
            3 -> "originals"
            4 -> "canvas"
            else -> throw Exception("page > 4 not available")
        }

        val entries = client.get("$baseUrl/$langCode/ranking/$ranking").asJsoup()
            .select(".webtoon_list li a")
            .map(::mangaFromElement)

        return MangasPage(entries, hasNextPage = ranking != "canvas")
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")?.fullSizeThumb()
    }

    // pstatic serves a compressed or cropped derivative when ?type= is present;
    // the bare URL returns the original image
    private fun String.fullSizeThumb(): String = toHttpUrlOrNull()
        ?.takeIf { it.host.endsWith(".pstatic.net") }
        ?.newBuilder()
        ?.removeAllQueryParameters("type")
        ?.build()
        ?.toString()
        ?: this

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            else -> throw Exception("Unknown day of week")
        }

        // day names stay in English for every language
        val entries = client.get("$baseUrl/$langCode/originals/$day?sortOrder=UPDATE").asJsoup()
            .select(".webtoon_list li a")
            .map(::mangaFromElement)

        return MangasPage(entries, hasNextPage = false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SearchType(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchType = filters.firstInstanceOrNull<SearchType>()?.selected

        // /search/result is the combined ORIGINALS + CANVAS overview (no
        // pagination); the typed endpoints paginate at 30 entries per page
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(langCode)
            addPathSegment("search")
            addPathSegment(searchType ?: "result")
            addQueryParameter("keyword", query)
            if (searchType != null) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url).asJsoup()
        val entries = document.select(".webtoon_list li a").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("a.pagination[aria-current=true] + a") != null

        return MangasPage(entries, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.isWebtoonsHost()) return null
        val titleNo = url.queryParameter("title_no")?.toIntOrNull()
            ?: url.queryParameter("titleNo")?.toIntOrNull()
            ?: return null
        val path = url.pathSegments

        // Every language ships as its own source; only the matching one resolves the link.
        if (path.size < 3 || path[0] != langCode) return null

        val manga = SManga.create().apply {
            this.url = buildString {
                if (path[1] == "canvas") {
                    append("/challenge")
                }
                append("/episodeList?titleNo=")
                append(titleNo)
            }
        }

        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // details and the episode list are on different hosts
        val detailsAsync = async { if (fetchDetails) fetchMangaDetails(manga) else manga }
        val chaptersAsync = async { if (fetchChapters) fetchChapterList(manga) else chapters }

        SMangaUpdate(manga = detailsAsync.await(), chapters = chaptersAsync.await())
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val detailElement = document.selectFirst(".detail_header .info")
        val infoElement = document.selectFirst("#_asideDetail")

        return SManga.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.subj, h3.subj")!!.text()
            author = detailElement?.selectFirst(".author:nth-of-type(1)")?.ownText()
                ?: detailElement?.selectFirst(".author_area")?.ownText()
            artist = detailElement?.selectFirst(".author:nth-of-type(2)")?.ownText()
                ?: detailElement?.selectFirst(".author_area")?.ownText() ?: author
            genre = detailElement?.select(".genre").orEmpty().joinToString { it.text() }
            description = infoElement?.selectFirst("p.summary")?.text()
            // day_info text is localized ("COMPLETED", "FINALIZADAS",
            // "ABGESCHLOSSEN", ...); the icon class is locale-independent
            status = when {
                infoElement?.selectFirst("p.day_info .txt_ico_completed") != null -> SManga.COMPLETED
                infoElement?.selectFirst("p.day_info .txt_ico_up") != null -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            thumbnail_url = run {
                val bannerFile = document.selectFirst(".detail_header .thmb img")
                    ?.absUrl("src")
                    ?.toHttpUrl()
                    ?.pathSegments
                    ?.lastOrNull()
                val oldThumbFile = manga.thumbnail_url
                    ?.toHttpUrl()
                    ?.pathSegments
                    ?.lastOrNull()
                val thumbnail = document.selectFirst("head meta[property=\"og:image\"]")
                    ?.attr("content")
                    ?.fullSizeThumb()

                // replace banner image for toons in library
                if (oldThumbFile != null && oldThumbFile != bannerFile) {
                    manga.thumbnail_url
                } else {
                    thumbnail
                }
            }
        }
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val webtoonUrl = getMangaUrl(manga).toHttpUrl()
        val titleId = webtoonUrl.queryParameter("title_no")
            ?: webtoonUrl.queryParameter("titleNo")
            ?: throw Exception("Migrate from $name to $name")

        val type = run {
            val path = webtoonUrl.pathSegments.filter(String::isNotEmpty)

            // older url pattern, people have in their library
            if (webtoonUrl.encodedPath.contains("episodeList")) {
                when (path[0]) {
                    // "/episodeList?titleNo=1049"
                    "episodeList" -> "webtoon"

                    // "/challenge/episodeList?titleNo=304446"
                    "challenge" -> "canvas"

                    else -> throw Exception("Migrate from $name to $name")
                }
            } else {
                // "/en/canvas/meme-girls/list?title_no=304446"
                if (path[1] == "canvas") {
                    "canvas"
                } else {
                    "webtoon"
                }
            }
        }

        val url = mobileUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/v1")
            addPathSegment(type)
            addPathSegment(titleId)
            addPathSegment("episodes")
            addQueryParameter("pageSize", "99999")
            if (type == "canvas") {
                addQueryParameter("readingLanguageCode", langCode)
            }
        }.build()

        val episodes = client.get(url, mobileHeaders)
            .parseAs<EpisodeListResponse>()
            .result
            .episodeList
            .onEach(::assignEpisodeNumber)

        if (episodes.count { it.chapterNumber == -1f } > episodes.size / 2) {
            // most titles follow the "Ep. N" naming convention; when the
            // majority of titles do not match, fall back to sequential numbers
            episodes.forEachIndexed { index, episode ->
                episode.chapterNumber = (index + 1).toFloat()
            }
        } else {
            var maxChapterNumber = 0f
            var currentSeason = 1
            var seasonOffset = 0f

            episodes.forEachIndexed { index, episode ->
                if (episode.chapterNumber != -1f) {
                    val originalNumber = episode.chapterNumber

                    if (episode.seasonNumber > currentSeason) {
                        currentSeason = episode.seasonNumber
                        if (originalNumber <= maxChapterNumber) {
                            seasonOffset = maxChapterNumber
                        }
                    }

                    episode.chapterNumber = seasonOffset + originalNumber
                    maxChapterNumber = maxOf(maxChapterNumber, episode.chapterNumber)
                } else {
                    val previous = episodes.getOrNull(index - 1)
                    episode.chapterNumber = previous?.let { it.chapterNumber + 0.01f } ?: 0f
                }
            }
        }

        return episodes.map { it.toSChapter() }.asReversed()
    }

    private fun assignEpisodeNumber(episode: Episode) {
        val match = EPISODE_NUMBER_REGEX.find(episode.episodeTitle)
            ?.groupValues
            ?.takeIf { it[6].isEmpty() } // skip mini/bonus/special episodes

        episode.chapterNumber = match?.get(11)?.toFloat() ?: -1f
        episode.seasonNumber = match?.get(4)?.takeIf(String::isNotBlank)?.toInt() ?: 1
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val pages = document.select("div#_imageList > img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("data-url"))
        }

        if (pages.isNotEmpty()) {
            return pages
        }

        return fetchMotionToonPages(document)
    }

    private suspend fun fetchMotionToonPages(document: Document): List<Page> {
        val docString = document.toString()
        val docUrl = DOC_URL_REGEX.find(docString)!!.groupValues[1]
        val motionToonPath = MOTION_TOON_PATH_REGEX.find(docString)!!.groupValues[1]

        return client.get(docUrl).parseAs<MotionToonResponse>()
            .imageUrls()
            .mapIndexed { i, url -> Page(i, imageUrl = motionToonPath + url) }
    }

    companion object {
        // group 4: season number, group 6: mini/bonus/special marker, group 11: episode number
        private val EPISODE_NUMBER_REGEX = Regex(
            """(?:(s(eason)?|saison|part|vol(ume)?)\s*\.?\s*(\d+).*?)?(.*?(mini|bonus|special).*?)?(e(p(isode)?)?|ch(apter)?)\s*\.?\s*(\d+(\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )

        private val DOC_URL_REGEX = Regex("documentURL:.*?'(.*?)'")
        private val MOTION_TOON_PATH_REGEX = Regex("jpg:.*?'(.*?)\\{")
    }
}
