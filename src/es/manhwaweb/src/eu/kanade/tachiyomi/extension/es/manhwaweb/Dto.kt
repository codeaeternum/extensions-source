package eu.kanade.tachiyomi.extension.es.manhwaweb

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PopularPayloadDto(
    @SerialName("top") private val top: PopularTopDto? = null,
) {
    fun toSMangaList(): List<SManga> = top?.toSMangaList().orEmpty()
}

@Serializable
class PopularTopDto(
    @SerialName("manhwas_esp") private val esp: List<PopularComicDto> = emptyList(),
    @SerialName("manhwas_raw") private val raw: List<PopularComicDto> = emptyList(),
) {
    fun toSMangaList(): List<SManga> = (esp + raw)
        .distinctBy { it.slug }
        .sortedByDescending { it.views }
        .mapNotNull { it.toSManga() }
}

@Serializable
class PopularComicDto(
    @SerialName("link") private val link: String? = null,
    @SerialName("numero") val views: Int = 0,
    @SerialName("name") private val title: String? = null,
    @SerialName("imagen") private val thumbnail: String? = null,
) {
    val slug get() = link?.substringAfterLast("/")

    fun toSManga(): SManga? {
        val slug = slug ?: return null
        return SManga.create().apply {
            url = "/manhwa/$slug"
            title = this@PopularComicDto.title ?: slug
            thumbnail_url = thumbnail
        }
    }
}

@Serializable
class LatestPayloadDto(
    @SerialName("manhwas") private val manhwas: LatestDto? = null,
) {
    fun toSMangaList(): List<SManga> = manhwas?.toSMangaList().orEmpty()
}

@Serializable
class LatestDto(
    @SerialName("manhwas_esp") private val esp: List<LatestComicDto> = emptyList(),
    @SerialName("manhwas_raw") private val raw: List<LatestComicDto> = emptyList(),
    @SerialName("_manhwas") private val adult: List<LatestComicDto> = emptyList(),
) {
    fun toSMangaList(): List<SManga> = (esp + raw + adult)
        .distinctBy { it.slug }
        .sortedByDescending { it.updatedAt }
        .mapNotNull { it.toSManga() }
}

@Serializable
class LatestComicDto(
    @SerialName("id_rel") private val idRel: String? = null,
    @SerialName("id_manhwa") private val id: String? = null,
    @SerialName("name_manhwa") private val title: String? = null,
    @SerialName("img") private val thumbnail: String? = null,
    @SerialName("create") val updatedAt: Long = 0,
) {
    val slug get() = idRel ?: id

    fun toSManga(): SManga? {
        val slug = slug ?: return null
        return SManga.create().apply {
            url = "/manhwa/$slug"
            title = this@LatestComicDto.title ?: slug
            thumbnail_url = thumbnail
        }
    }
}

@Serializable
class LibraryPayloadDto(
    @SerialName("data") private val data: List<SearchComicDto> = emptyList(),
    @SerialName("next") val hasNextPage: Boolean = false,
) {
    fun toSMangaList(): List<SManga> = data.mapNotNull { it.toSManga() }
}

@Serializable
class SearchComicDto(
    @SerialName("real_id") private val realId: String? = null,
    @SerialName("_id") private val id: String? = null,
    @SerialName("the_real_name") private val title: String? = null,
    @SerialName("name_esp") private val altTitle: String? = null,
    @SerialName("_imagen") private val thumbnail: String? = null,
) {
    fun toSManga(): SManga? {
        val slug = realId ?: id ?: return null
        return SManga.create().apply {
            url = "/manhwa/$slug"
            title = this@SearchComicDto.title ?: altTitle ?: slug
            thumbnail_url = thumbnail
        }
    }
}

@Serializable
class ComicDetailsDto(
    @SerialName("_id") val id: String,
    @SerialName("real_id") private val realId: String? = null,
    @SerialName("name_esp") private val title: String? = null,
    @SerialName("the_real_name") private val realTitle: String? = null,
    @SerialName("name_raw") private val rawTitle: String? = null,
    @SerialName("_name") private val originalTitle: String? = null,
    @SerialName("others_name") private val otherTitles: List<String> = emptyList(),
    @SerialName("_sinopsis") private val synopsis: String? = null,
    @SerialName("_status") private val status: String? = null,
    @SerialName("_imagen") private val thumbnail: String? = null,
    @SerialName("_categoris") private val genres: List<Map<String, String>> = emptyList(),
    @SerialName("autor") private val authors: List<ComicPersonDto> = emptyList(),
    @SerialName("artista") private val artists: List<ComicPersonDto> = emptyList(),
    @SerialName("_extras") private val extras: ComicExtrasDto? = null,
    val chapters: List<ChapterDto> = emptyList(),
) {
    val slug get() = realId ?: id

    fun toSManga() = SManga.create().apply {
        url = "/manhwa/$slug"
        title = this@ComicDetailsDto.title ?: realTitle ?: slug
        thumbnail_url = thumbnail
        description = buildString {
            synopsis?.let { append(it) }
            val alternatives = (otherTitles + listOfNotNull(originalTitle, rawTitle)).distinct()
            if (alternatives.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Nombres alternativos: ")
                append(alternatives.joinToString())
            }
        }.ifBlank { null }
        status = when {
            extras?.hiatus == true -> SManga.ON_HIATUS
            this@ComicDetailsDto.status == "publicandose" -> SManga.ONGOING
            this@ComicDetailsDto.status == "finalizado" -> SManga.COMPLETED
            this@ComicDetailsDto.status == "pausado" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        genre = genres.mapNotNull { it.values.firstOrNull() }.joinToString()
        author = authors.mapNotNull { it.name }.joinToString()
            .ifBlank { extras?.authors?.joinToString() }
        artist = artists.mapNotNull { it.name }.joinToString().ifBlank { null }
        initialized = true
    }

    fun toChapterList(): List<SChapter> = chapters
        .flatMap { it.toSChapters(id, slug) }
        .sortedByDescending { it.chapter_number }
}

@Serializable
class ComicPersonDto(
    val name: String? = null,
)

@Serializable
class ComicExtrasDto(
    @SerialName("autores") val authors: List<String> = emptyList(),
    val hiatus: Boolean = false,
)

@Serializable
class ChapterDto(
    @SerialName("chapter") private val number: Float,
    @SerialName("link_raw") private val rawLink: String? = null,
    private val link: String? = null,
    private val create: Long? = null,
    private val versions: List<ChapterVersionDto> = emptyList(),
) {
    fun toSChapters(mangaId: String, mangaSlug: String): List<SChapter> {
        val name = "Capítulo ${number.toString().removeSuffix(".0")}"
        return buildList {
            versions.forEach { add(Triple(it.link, it.scanlator, it.create)) }
            add(Triple(link, "Esp", create))
            add(Triple(rawLink, "Raw", create))
        }.mapNotNull { (link, scanlator, date) ->
            val chapterId = link
                ?.takeIf { "manhwaweb.com" in it && "/leer" in it }
                ?.substringAfterLast("/")
                ?.replace(mangaId, mangaSlug)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SChapter.create().apply {
                url = "/leer/$chapterId"
                this.name = name
                chapter_number = number
                date_upload = date ?: 0L
                this.scanlator = scanlator
            }
        }.distinctBy { it.url }
    }
}

@Serializable
class ChapterVersionDto(
    val link: String? = null,
    val create: Long? = null,
    private val joint: List<ChapterGroupDto> = emptyList(),
) {
    val scanlator get() = joint.mapNotNull { it.name }.joinToString().ifBlank { "ManhwaWeb" }
}

@Serializable
class ChapterGroupDto(
    val name: String? = null,
)

@Serializable
class PagesPayloadDto(
    @SerialName("chapter") private val chapter: PagesChapterDto? = null,
    @SerialName("real_id") private val realId: String? = null,
    @SerialName("_id") private val id: String? = null,
) {
    val images get() = chapter?.images.orEmpty()
    val mangaSlug get() = realId ?: id
}

@Serializable
class PagesChapterDto(
    @SerialName("img") val images: List<String> = emptyList(),
)
