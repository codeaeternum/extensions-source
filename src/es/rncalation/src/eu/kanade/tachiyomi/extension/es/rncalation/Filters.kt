package eu.kanade.tachiyomi.extension.es.rncalation

import eu.kanade.tachiyomi.source.model.Filter

abstract class UriPartFilter(
    val param: String,
    name: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    fun toUriPart(): String = vals.getOrNull(state)?.second.orEmpty()
}

class SortFilter : UriPartFilter("sort", "Orden", SORTS)
class TypeFilter : UriPartFilter("type", "Tipo", TYPES)
class StatusFilter : UriPartFilter("status", "Estado", STATUSES)
class GenreFilter : UriPartFilter("genre", "Género", GENRES)

// Bare /library already sorts by newest; an empty sort value keeps the default.
private val SORTS = arrayOf(
    "Más reciente" to "",
    "Actualizado" to "updated",
    "Más visto" to "views",
    "Mejor valorado" to "rating",
    "A-Z" to "title",
)

// "Novel" is left out on purpose: text novels produce empty readers anyway and
// the card list filters them out.
private val TYPES = arrayOf(
    "<Seleccionar>" to "",
    "Manga" to "Manga",
    "Manhwa" to "Manhwa",
    "Manhua" to "Manhua",
    "Doujinshi" to "Doujinshi",
    "Otro" to "Other",
)

private val STATUSES = arrayOf(
    "<Seleccionar>" to "",
    "En emisión" to "Ongoing",
    "Completado" to "Completed",
    "En pausa" to "Hiatus",
    "Cancelado" to "Cancelled",
)

// Values are the site's own genre chips; the data is user-entered, so the list
// carries the same case/accent duplicates the site shows.
private val GENRES = arrayOf(
    "<Seleccionar>" to "",
    "Fantasía" to "Fantasía",
    "Drama" to "Drama",
    "Acción" to "Acción",
    "Romance" to "Romance",
    "Aventura" to "Aventura",
    "Fantasia" to "Fantasia",
    "Accion" to "Accion",
    "Comedia" to "Comedia",
    "accion" to "accion",
    "Artes Marciales" to "Artes Marciales",
    "Manhua" to "Manhua",
    "drama" to "drama",
    "Adventure" to "Adventure",
    "romance" to "romance",
    "Sistema" to "Sistema",
    "aventura" to "aventura",
)
