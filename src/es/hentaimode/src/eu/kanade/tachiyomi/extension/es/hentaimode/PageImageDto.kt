package eu.kanade.tachiyomi.extension.es.hentaimode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PageImageDto(
    @SerialName("page_image") val pageImage: String,
)
