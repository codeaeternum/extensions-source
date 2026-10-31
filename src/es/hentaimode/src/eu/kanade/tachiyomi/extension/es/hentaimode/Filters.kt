package eu.kanade.tachiyomi.extension.es.hentaimode

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter :
    Filter.Select<String>(
        "Listado",
        arrayOf("Ninguno", "Últimos incest", "Últimos loli", "Últimos netorare"),
    ) {
    fun toPath(): String? = when (state) {
        1 -> "ultimos-incest"
        2 -> "ultimos-loli"
        3 -> "ultimos-netorare"
        else -> null
    }
}

abstract class IdFilter(name: String, private val prefix: String) : Filter.Text(name) {
    fun toPath(): String? = state.trim()
        .takeIf(String::isNotEmpty)
        ?.let { "$prefix/$it" }
}

class TagFilter : IdFilter("Etiqueta (ID)", "etiqueta")
class SerieFilter : IdFilter("Serie (ID)", "serie")
class PersonajeFilter : IdFilter("Personaje (ID)", "personaje")
