package cz.matee.appreviewzz.core.model

import kotlinx.datetime.LocalDate

/**
 * Hodnocení aplikace tak, jak je právě vidí store — výstup konektoru před uložením.
 *
 * Dvě věci, které v dnešním n8n chybí a bez kterých se digest nedá vyložit:
 *
 * - **`asOf`**: ke kterému dni data platí. Play export je jeden až dva dny pozadu, takže
 *   „dnešní" průměr z CSV je ve skutečnosti předevčerejší. Dnes se to nikde nenese a v kartě
 *   se objeví jako dnešek.
 * - **`territory`**: iOS čísla jsou součet vybraných storefrontů, ne celého světa. Když se to
 *   uloží pod `GLOBAL` bez rozpadu, není za měsíc jak zjistit, co v tom čísle vlastně bylo.
 */
data class ObservedRatings(
    val platform: Platform,
    /** ISO kód storefrontu velkými písmeny, nebo [GLOBAL] pro agregát přes všechny. */
    val territory: String,
    /** Průměrné hodnocení 1–5; `null`, když ho zdroj nedává. */
    val average: Double?,
    /** Počet hodnocení celkem; `null`, když ho zdroj nedává. */
    val totalCount: Long?,
    /** Kumulativní počty po hvězdách 1..5; prázdné u zdrojů bez histogramu (Play CSV). */
    val histogram: Map<Int, Long>,
    val source: RatingSource,
    /** Den, ke kterému hodnoty platí. `null` = zdroj datum neuvádí, tedy „teď". */
    val asOf: LocalDate? = null,
) {
    init {
        require(territory.isNotBlank()) { "Hodnocení bez storefrontu" }
        require(average == null || average in 0.0..MAX_STARS) { "Průměr mimo rozsah: $average" }
        require(histogram.keys.all { it in 1..MAX_STARS.toInt() }) { "Histogram má hvězdy mimo 1..5: ${histogram.keys}" }
        require(histogram.values.all { it >= 0 }) { "Histogram má záporné počty" }
    }

    /** Součet histogramu; `null`, když histogram není. Použije se, když zdroj počet neuvádí. */
    fun histogramCount(): Long? = histogram.values.sum().takeIf { histogram.isNotEmpty() }

    /**
     * Vážený průměr z histogramu — jediný způsob, jak z počtů po hvězdách dostat průměr.
     * `null` u prázdného histogramu, protože „nula hodnocení" a „průměr nula" jsou dvě věci.
     */
    fun histogramAverage(): Double? {
        val count = histogramCount()?.takeIf { it > 0 } ?: return null
        val weighted = histogram.entries.sumOf { (stars, votes) -> stars.toLong() * votes }
        return weighted.toDouble() / count
    }

    companion object {
        const val GLOBAL = "GLOBAL"
        private const val MAX_STARS = 5.0

        /**
         * Agregát přes storefronty: počty se sčítají, průměr se váží počtem hodnocení.
         * Prostý průměr průměrů by dal Andorře stejnou váhu jako Spojeným státům.
         */
        fun aggregate(
            parts: List<ObservedRatings>,
            source: RatingSource,
        ): ObservedRatings? {
            if (parts.isEmpty()) return null
            val platform = parts.first().platform
            require(parts.all { it.platform == platform }) { "Agregovat jde jen hodnocení jedné platformy" }

            val histogram =
                parts
                    .flatMap { it.histogram.entries }
                    .groupingBy { it.key }
                    .fold(0L) { sum, entry -> sum + entry.value }
                    .filterValues { it > 0 }
            val counted = parts.filter { (it.totalCount ?: 0) > 0 && it.average != null }
            val totalCount = parts.mapNotNull { it.totalCount }.takeIf { it.isNotEmpty() }?.sum()
            val average =
                when {
                    counted.isNotEmpty() ->
                        counted.sumOf { it.average!! * (it.totalCount ?: 0) } / counted.sumOf { it.totalCount ?: 0 }

                    else -> null
                }

            return ObservedRatings(
                platform = platform,
                territory = GLOBAL,
                average = average,
                totalCount = totalCount,
                histogram = histogram,
                source = source,
                asOf = parts.mapNotNull { it.asOf }.minOrNull(),
            )
        }
    }
}
