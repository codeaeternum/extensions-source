package eu.kanade.tachiyomi.extension.all.webtoons

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.Serializable
import org.jsoup.parser.Parser
import java.text.DecimalFormat

typealias EpisodeListResponse = ResultDto<EpisodeList>

@Serializable
class ResultDto<T>(
    val result: T,
)

@Serializable
class EpisodeList(
    val episodeList: List<Episode>,
)

@Serializable
class Episode(
    val episodeTitle: String,
    private val viewerLink: String,
    private val exposureDateMillis: Long,
    private val hasBgm: Boolean = false,
) {
    var chapterNumber = -1f
    var seasonNumber = 1

    fun toSChapter(): SChapter = SChapter.create().apply {
        url = viewerLink
        name = buildString {
            append(Parser.unescapeEntities(episodeTitle, false))
            append(" (ch. ", chapterNumberFormat.format(chapterNumber), ")")
            if (hasBgm) {
                append(" ♫")
            }
        }
        date_upload = exposureDateMillis
        chapter_number = chapterNumber
    }
}

// rounds the 0.01 offsets used for bonus episodes as well as trailing zeros
private val chapterNumberFormat = DecimalFormat("#.##")

@Serializable
class MotionToonResponse(
    private val assets: MotionToonAssets,
) {
    fun imageUrls(): List<String> = assets.images.entries
        .filter { it.key.contains("layer") }
        .map { it.value }
}

@Serializable
class MotionToonAssets(
    val images: Map<String, String>,
)
