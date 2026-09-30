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
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.SocketException
import java.text.DecimalFormat
import java.util.Calendar

@Source
abstract class Webtoons : KeiSource() {
    // Due to lang code getting more specific for zh-Hant
    private val langCode: String get() = if (lang == "zh-Hant") "zh-hant" else lang
    private val localeForCookie: String get() = if (lang == "zh-Hant") "zh_TW" else lang

    private val mobileUrl = "https://m.webtoons.com"

    private val mobileHeaders: Headers
        get() = headersBuilder()
            .set("Referer", "$mobileUrl/")
            .removeAll("Origin")
            .build()

    override val supportRelatedMangasBySearch = true

    // Sin addCookie (CookieManager/android.webkit) ni TextInterceptor (android.graphics):
    // no existen en el runtime J9 de Tachimanga. La cookie va como header fijo.
    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val newRequest = if (request.url.host.endsWith("webtoons.com")) {
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

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ranking = when (page) {
            1 -> "trending"
            2 -> "popular"
            3 -> "originals"
            4 -> "canvas"
            else -> throw Exception("page > 4 not available")
        }

        val document = client.get("$baseUrl/$langCode/ranking/$ranking").asJsoup()
        val entries = document.select(".webtoon_list li a")
            .map(::mangaFromElement)

        return MangasPage(entries, hasNextPage = ranking != "canvas")
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

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

        val document = client.get("$baseUrl/$langCode/originals/$day?sortOrder=UPDATE").asJsoup()
        val entries = document.select(".webtoon_list li a")
            .map(::mangaFromElement)

        return MangasPage(entries, hasNextPage = false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // El sitio migró la búsqueda SSR a /search/result
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(langCode)
            addPathSegment("search")
            addPathSegment("result")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url).asJsoup()
        val entries = document.select(".webtoon_list li a").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("a.pagination[aria-current=true] + a") != null

        return MangasPage(entries, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val titleNo = url.queryParameter("title_no")?.toIntOrNull() ?: return null
        val path = url.pathSegments
        if (path.size < 3) return null

        // Every language ships as its own source; only the matching one resolves the link.
        if (path[0] != langCode) return null

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
            status = with(infoElement?.selectFirst("p.day_info")?.text().orEmpty()) {
                when {
                    contains("UP") || contains("EVERY") || contains("NOUVEAU") -> SManga.ONGOING
                    contains("END") || contains("COMPLETED") || contains("TERMINÉ") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
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

        val result = client.get(url, mobileHeaders).parseAs<EpisodeListResponse>()

        var recognized = 0
        var unrecognized = 0

        val chapters = result.result.episodeList.onEach { episode ->
            val match = episodeNoRegex
                .find(episode.episodeTitle)
                ?.groupValues
                ?.takeIf { it[6].isEmpty() } // skip mini/bonus episodes

            episode.chapterNumber = match?.get(11)?.toFloat() ?: -1f
            episode.seasonNumber = match?.get(4)?.takeIf(String::isNotBlank)?.toInt() ?: 1

            if (episode.chapterNumber == -1f) {
                unrecognized++
            } else {
                recognized++
            }
        }

        if (unrecognized > recognized) {
            chapters.onEachIndexed { index, chapter ->
                chapter.chapterNumber = (index + 1).toFloat()
            }
        } else {
            var maxChapterNumber = 0f
            var currentSeason = 1
            var seasonOffset = 0f

            chapters.forEachIndexed { idx, chapter ->
                if (chapter.chapterNumber != -1f) {
                    val originalNumber = chapter.chapterNumber

                    // Check if we've moved to a new season
                    if (chapter.seasonNumber > currentSeason) {
                        currentSeason = chapter.seasonNumber
                        if (originalNumber <= maxChapterNumber) {
                            seasonOffset = maxChapterNumber
                        }
                    }

                    chapter.chapterNumber = seasonOffset + originalNumber
                    maxChapterNumber = maxOf(maxChapterNumber, chapter.chapterNumber)
                } else {
                    val previous = chapters.getOrNull(idx - 1)
                    if (previous == null) {
                        chapter.chapterNumber = 0f
                    } else {
                        chapter.chapterNumber = previous.chapterNumber + 0.01f
                    }
                }
            }
        }

        val numberFormatter = DecimalFormat("#.##")
        return chapters.map { episode ->
            SChapter.create().apply {
                this.url = episode.viewerLink
                name = buildString {
                    append(Parser.unescapeEntities(episode.episodeTitle, false))
                    append(" (ch. ", numberFormatter.format(episode.chapterNumber), ")")
                    if (episode.hasBgm) {
                        append(" ♫")
                    }
                }
                date_upload = episode.exposureDateMillis
                chapter_number = episode.chapterNumber
            }
        }.asReversed()
    }

    // season number - 4 capture group
    // possible bonus/mini/special episode - 6 capture group
    // episode number - 11 capture group
    private val episodeNoRegex = Regex(
        """(?:(s(eason)?|saison|part|vol(ume)?)\s*\.?\s*(\d+).*?)?(.*?(mini|bonus|special).*?)?(e(p(isode)?)?|ch(apter)?)\s*\.?\s*(\d+(\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val pages = document.select("div#_imageList > img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("data-url"))
        }.toMutableList()

        if (pages.isEmpty()) {
            pages.addAll(
                fetchMotionToonPages(document),
            )
        }

        return pages
    }

    private suspend fun fetchMotionToonPages(document: Document): List<Page> {
        val docString = document.toString()

        val docUrlRegex = Regex("documentURL:.*?'(.*?)'")
        val motionToonPathRegex = Regex("jpg:.*?'(.*?)\\{")

        val docUrl = docUrlRegex.find(docString)!!.groupValues[1]
        val motionToonPath = motionToonPathRegex.find(docString)!!.groupValues[1]
        val motionToonImages = client.get(docUrl).parseAs<MotionToonResponse>().assets.images

        return motionToonImages.entries
            .filter { it.key.contains("layer") }
            .mapIndexed { i, entry ->
                Page(i, imageUrl = motionToonPath + entry.value)
            }
    }
}
