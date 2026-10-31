package eu.kanade.tachiyomi.extension.es.lectortmoo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class MangaListResponse(
    val data: List<MangaItemDto> = emptyList(),
    val meta: MetaDto? = null,
)

@Serializable
class MetaDto(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
)

@Serializable
class MangaItemDto(
    val slug: String,
    val title: String,
    val cover: String? = null,
)

@Serializable
class MangaDetailResponse(val data: MangaDetailDto)

@Serializable
class MangaDetailDto(
    val slug: String,
    val title: String = "",
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("title_ja") val titleJa: String? = null,
    val synopsis: String? = null,
    val cover: String? = null,
    val status: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<GenreDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class GenreDto(val name: String, val slug: String)

@Serializable
class ChapterDto(
    val slug: String,
    val title: String? = null,
    val number: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
class ChapterPagesResponse(val data: ChapterPagesDto)

@Serializable
class ChapterPagesDto(
    val pages: List<String> = emptyList(),
    val manga: ChapterMangaDto? = null,
)

@Serializable
class ChapterMangaDto(val slug: String)
