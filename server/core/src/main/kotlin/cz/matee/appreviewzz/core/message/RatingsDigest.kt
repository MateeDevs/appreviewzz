package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Hodnocení jedné platformy připravená k vykreslení — spočítaná jednou pro Slack i Teams,
 * ze stejného důvodu jako [ReviewNotification].
 *
 * Sémantika je proti dnešnímu n8n **narovnaná** (plán §5.6). Dnes se ukazuje „průměr dnešních
 * nových hodnocení minus celkový průměr", takže appka bez nových hodnocení hlásí propad
 * o celý průměr (▼ −4,3) a nikdo neví proč. Tady je delta rozdíl **celkového průměru proti
 * minulému přehledu** — tedy to, co lidi z toho čísla stejně vyčíst chtějí.
 */
data class PlatformRatings(
    val platform: Platform,
    /** Celkový průměr aplikace teď. */
    val average: Double?,
    val totalCount: Long?,
    /** Hodnoty z předchozího přehledu; `null` u prvního běhu. */
    val previousAverage: Double?,
    val previousCount: Long?,
    /** Přírůstek hodnocení po hvězdách od minulého přehledu; prázdné u zdrojů bez histogramu. */
    val newRatings: Map<Int, Long>,
    /** Den, ke kterému data platí — Play export bývá o den dva pozadu a je to vidět. */
    val asOf: LocalDate,
    val previousAsOf: LocalDate?,
) {
    /** První přehled nemá s čím srovnávat. Dnešní řešení místo toho ukáže nesmysl. */
    val isFirstRun: Boolean get() = previousAsOf == null

    val delta: Double?
        get() =
            if (average != null && previousAverage != null) average - previousAverage else null

    /**
     * Kolik hodnocení přibylo. Prvně z histogramu (přesnější rozpad), jinak z rozdílu počtů —
     * a nikdy záporně: store občas hodnocení maže a „−3 nová hodnocení" nedává smysl.
     */
    val newTotal: Long?
        get() =
            when {
                newRatings.isNotEmpty() -> newRatings.values.sum()
                totalCount != null && previousCount != null -> (totalCount - previousCount).coerceAtLeast(0)
                else -> null
            }
}

/**
 * Denní přehled hodnocení jedné aplikace. Pořadí platforem je Android, pak iOS — stejně
 * jako dnes, aby klienti po migraci nekoukali na přeházenou zprávu.
 */
data class RatingsDigest(
    val appName: String,
    val locale: MessageLocale,
    val timezone: String,
    /** Den, za který přehled jde ven (v zóně aplikace). */
    val date: LocalDate,
    val platforms: List<PlatformRatings>,
) {
    val catalog: MessageCatalog = MessageCatalog.of(locale)

    init {
        require(platforms.isNotEmpty()) { "Přehled hodnocení bez jediné platformy" }
    }

    /** Datum v jazyce kanálu; u platformy se ukazuje její vlastní `asOf`, ne dnešek. */
    fun formattedDate(date: LocalDate): String = DATE_FORMAT.withLocale(catalog.dateLocale).format(date.toJavaLocalDate())

    /** Krátký souhrn do notifikace na mobilu — bloky ani karta se tam nevykreslí. */
    fun fallbackText(): String {
        val head = "${catalog[MessageKey.RATINGS_OVERVIEW_TITLE]} $appName"
        val numbers =
            platforms.mapNotNull { part ->
                part.average?.let { "${platformEmoji(part.platform)} ${formatRating(it)}" }
            }
        return (listOf(head) + numbers).joinToString(" · ")
    }

    companion object {
        /** Pořadí, ve kterém se platformy ukazují — parita s dnešní zprávou. */
        val PLATFORM_ORDER = listOf(Platform.ANDROID, Platform.IOS)

        fun platformEmoji(platform: Platform): String =
            when (platform) {
                Platform.ANDROID -> "🤖"
                Platform.IOS -> "🍎"
            }

        /**
         * Hodnocení na dvě desetinná místa bez koncových nul (4,50 → „4,5"). Dnešní n8n na to
         * má regex `/\.?0+$/`, který z „10" udělá „1" — u ratingů to nevadí, ale je to past,
         * kterou nemá smysl přenášet.
         */
        fun formatRating(value: Double): String {
            val rounded = Math.round(value * ROUNDING) / ROUNDING
            return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        }

        /** Delta se znaménkem: `+0.12`, `-0.03`, `0` — bez šipek, ty si přidá kanál. */
        fun formatDelta(value: Double): String {
            val formatted = formatRating(kotlin.math.abs(value))
            return when {
                formatted == "0" -> "0"
                value > 0 -> "+$formatted"
                else -> "-$formatted"
            }
        }

        /** Ukazatel směru; nula i chybějící srovnání mají vlastní znak, ať se nepletou s propadem. */
        fun trend(delta: Double?): String =
            when {
                delta == null -> "▪︎"
                formatDelta(delta) == "0" -> "▪︎"
                delta > 0 -> "▲"
                else -> "▼"
            }

        /**
         * Pruh z 20 buněk, stejný jako dnes — je to jediná část zprávy, kterou lidi znají
         * a poznali by, kdyby zmizela.
         */
        fun bar(value: Double?): String {
            val filled = value?.let { Math.round(it / MAX_STARS * BAR_CELLS).toInt().coerceIn(0, BAR_CELLS) } ?: 0
            return "▰".repeat(filled) + "▱".repeat(BAR_CELLS - filled)
        }

        private const val BAR_CELLS = 20
        private const val MAX_STARS = 5.0
        private const val ROUNDING = 100.0
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}

private fun LocalDate.toJavaLocalDate(): java.time.LocalDate = java.time.LocalDate.of(year, month.number, day)
