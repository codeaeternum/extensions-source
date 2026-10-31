package eu.kanade.tachiyomi.extension.es.ikigaimangas

import eu.kanade.tachiyomi.source.model.Filter

class CheckBoxFilter(name: String, val id: Long) : Filter.CheckBox(name)

class GenreFilter :
    Filter.Group<CheckBoxFilter>(
        "Géneros",
        GENRES.map { (name, id) -> CheckBoxFilter(name, id) },
    )

class StatusFilter :
    Filter.Group<CheckBoxFilter>(
        "Estado",
        STATUSES.map { (name, id) -> CheckBoxFilter(name, id) },
    )

class SortByFilter :
    Filter.Sort(
        "Ordenar por",
        SORT_ORDERS.map { it.first }.toTypedArray(),
        Selection(2, ascending = false),
    ) {
    val selected: String get() = SORT_ORDERS[state!!.index].second
}

private val SORT_ORDERS = listOf(
    "Nombre" to "name",
    "Creado en" to "created_at",
    "Actualización más reciente" to "last_chapter_date",
    "Número de favoritos" to "bookmark_count",
    "Número de valoración" to "rating_count",
    "Número de vistas" to "view_count",
)

private val STATUSES = listOf(
    "Abandonada" to 906428048651190273L,
    "Cancelada" to 906426661911756802L,
    "Completa" to 906409532796731395L,
    "En Curso" to 911437469204086787L,
    "Hiatus" to 906409397258190851L,
)

private val GENRES = listOf(
    "+18" to 906409351272792067L,
    "Acción" to 906397904327999491L,
    "Adulto" to 906409527934582787L,
    "Apocalíptico" to 906409378635186179L,
    "Artes Marciales" to 906397904169861123L,
    "Aventura" to 906397904061530115L,
    "Boys Love" to 906409351330037763L,
    "Ciencia Ficción" to 906409468787720195L,
    "Comedia" to 906398112851165187L,
    "Demonios" to 906397904115531779L,
    "Deportes" to 906410143226462211L,
    "Doujinshi" to 1187154685166452739L,
    "Drama" to 906397903933407235L,
    "Ecchi" to 906409370648543235L,
    "Familia" to 906409382485884931L,
    "Fantasía" to 906397894348570627L,
    "Gender Bender" to 1093357252096753667L,
    "Girls Love" to 906409644012961795L,
    "Gore" to 906409472386203651L,
    "Guideverse" to 1182242384692314113L,
    "Harem" to 906397904221962243L,
    "Harem Inverso" to 906424352006438914L,
    "Histórico" to 906398112923385859L,
    "Horror" to 906423434084679682L,
    "Isekai" to 906409454067646467L,
    "Josei" to 906409501957390339L,
    "Maduro" to 906409612041551875L,
    "Magia" to 906409459593347075L,
    "Manga" to 1187155307072782337L,
    "Mecha" to 906409472453410819L,
    "Militar" to 906409472509739011L,
    "Misterio" to 906409374254727171L,
    "Omegaverse" to 1182242409543827457L,
    "Psicológico" to 906409351382073347L,
    "Realidad Virtual" to 906424676182294530L,
    "Recuentos de la vida" to 906409508165124099L,
    "Reencarnación" to 906409400553046019L,
    "Regresion" to 906397894469255171L,
    "Romance" to 906397894527549443L,
    "Seinen" to 906397903999959043L,
    "Shonen" to 906398112991150083L,
    "Shoujo" to 906397894408372227L,
    "Shoujo Ai" to 1187155022664531971L,
    "Shounen Ai" to 1187155082787848194L,
    "Sistema" to 906409408107216899L,
    "Smut" to 906409419999641603L,
    "Supernatural" to 906410027513937923L,
    "Supervivencia" to 906409454130921475L,
    "Tragedia" to 906409449984655363L,
    "Transmigración" to 906409378688663555L,
    "Vida Escolar" to 906409508232822787L,
    "Yaoi" to 906409432216403971L,
    "Yuri" to 906409472567017475L,
)
