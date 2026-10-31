package eu.kanade.tachiyomi.extension.es.lectortmoo

import eu.kanade.tachiyomi.source.model.Filter

abstract class QueryFilter(
    val param: String,
    name: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    val selected: String get() = vals.getOrNull(state)?.second.orEmpty()
}

class GenreFilter(vals: Array<Pair<String, String>>) : QueryFilter("genre", "Género", vals)
class TypeFilter : QueryFilter("type", "Tipo", TYPES)
class StatusFilter : QueryFilter("status", "Estado", STATUSES)

private val TYPES = arrayOf(
    "Todos" to "",
    "Manga" to "manga",
    "Manhwa" to "manhwa",
    "Manhua" to "manhua",
    "Novela" to "novela",
)

private val STATUSES = arrayOf(
    "Todos" to "",
    "En curso" to "En curso",
    "Finalizado" to "Finalizado",
)
