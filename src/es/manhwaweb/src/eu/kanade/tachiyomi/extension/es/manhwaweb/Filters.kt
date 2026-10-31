package eu.kanade.tachiyomi.extension.es.manhwaweb

import eu.kanade.tachiyomi.source.model.Filter

abstract class QueryFilter(
    val param: String,
    name: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    val selected: String get() = vals.getOrNull(state)?.second.orEmpty()
}

class TypeFilter :
    QueryFilter(
        "tipo",
        "Tipo",
        arrayOf(
            Pair("Ver todo", ""),
            Pair("Manhwa", "manhwa"),
            Pair("Manga", "manga"),
            Pair("Manhua", "manhua"),
            Pair("Doujinshi", "doujinshi"),
            Pair("Novela", "novela"),
            Pair("One shot", "one_shot"),
        ),
    )

class DemographyFilter :
    QueryFilter(
        "demografia",
        "Demografía",
        arrayOf(
            Pair("Ver todo", ""),
            Pair("Seinen", "seinen"),
            Pair("Shonen", "shonen"),
            Pair("Josei", "josei"),
            Pair("Shojo", "shojo"),
        ),
    )

class StatusFilter :
    QueryFilter(
        "estado",
        "Estado",
        arrayOf(
            Pair("Ver todo", ""),
            Pair("Publicándose", "publicandose"),
            Pair("Pausado", "pausado"),
            Pair("Finalizado", "finalizado"),
        ),
    )

class EroticFilter :
    QueryFilter(
        "erotico",
        "Erótico",
        arrayOf(
            Pair("Ver todo", ""),
            Pair("Sí", "si"),
            Pair("No", "no"),
        ),
    )

class Genre(name: String, val id: Int) : Filter.CheckBox(name)

class GenreFilter :
    Filter.Group<Genre>(
        "Géneros",
        listOf(
            Genre("Acción", 3),
            Genre("Aventura", 29),
            Genre("Comedia", 18),
            Genre("Drama", 1),
            Genre("Recuentos de la vida", 42),
            Genre("Romance", 2),
            Genre("Venganza", 5),
            Genre("Harem", 6),
            Genre("Fantasía", 23),
            Genre("Sobrenatural", 31),
            Genre("Tragedia", 25),
            Genre("Psicológico", 43),
            Genre("Horror", 32),
            Genre("Thriller", 44),
            Genre("Historias cortas", 28),
            Genre("Ecchi", 30),
            Genre("Gore", 34),
            Genre("Girls love", 27),
            Genre("Boys love", 45),
            Genre("Reencarnación", 41),
            Genre("Sistema de niveles", 37),
            Genre("Ciencia ficción", 33),
            Genre("Apocalíptico", 38),
            Genre("Artes marciales", 39),
            Genre("Superpoderes", 40),
            Genre("Cultivación (cultivo)", 35),
            Genre("Milf", 8),
        ),
    )

class SortByFilter :
    Filter.Sort(
        "Ordenar por",
        arrayOf("Alfabético", "Creación", "Popularidad", "Num. Capítulos"),
        Selection(0, ascending = false),
    ) {
    val selected: String
        get() = SORT_VALUES.getOrElse(state?.index ?: 0) { "alfabetico" }

    companion object {
        private val SORT_VALUES = listOf("alfabetico", "creacion", "popularidad", "num_chapter")
    }
}
