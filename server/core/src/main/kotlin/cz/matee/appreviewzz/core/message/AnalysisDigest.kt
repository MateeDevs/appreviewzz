package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.usecase.AnalysisAggregates
import cz.matee.appreviewzz.core.usecase.TopicInsight
import cz.matee.appreviewzz.core.usecase.TopicStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Týdenní rozbor recenzí připravený k vykreslení — jedna a tatáž čísla pro Slack i Teams,
 * ze stejného důvodu jako [ReviewNotification].
 *
 * **Šablona, ne generovaný text.** Věty se skládají z katalogu a čísla přicházejí z databáze;
 * model do zprávy nesahá vůbec (slovní shrnutí přibude až s vlastní validací). Rozbor, který
 * nikdo neumí zopakovat, je horší než žádný.
 */
data class AnalysisDigest(
    val appName: String,
    val locale: MessageLocale,
    val aggregates: AnalysisAggregates,
    /** Citát k prvnímu problému; `null`, když se žádný ověřený úryvek nenašel. */
    val quote: TopicQuote? = null,
    /** Odkaz do konzole na téma, které rozbor otevírá. */
    val consoleUrl: String? = null,
) {
    val catalog: MessageCatalog = MessageCatalog.of(locale)

    /** Problémy, které se do zprávy vejdou. Kolik jich je, říká platformní nastavení. */
    val issues: List<TopicInsight> get() = aggregates.issues

    fun period(): String =
        catalog.format(
            MessageKey.ANALYSIS_PERIOD,
            "from" to formatDate(aggregates.periodStart),
            "to" to formatDate(aggregates.periodEnd),
        )

    fun moodLine(): String =
        catalog.format(
            MessageKey.ANALYSIS_MOOD_LINE,
            "positive" to percent(aggregates.sentiment.positive),
            "negative" to percent(aggregates.sentiment.negative),
            "reviews" to aggregates.reviews,
            "stars" to (aggregates.avgStars?.let { RatingsDigest.formatRating(it) } ?: "—"),
        )

    /**
     * Změna nálady proti minulému týdnu. `null`, když minulý týden nemá s čím srovnávat —
     * první rozbor nemá předstírat trend.
     */
    fun moodChange(): String? {
        val delta = aggregates.previousNegativeDelta ?: return null
        val points = (delta * PERCENT).roundToInt()
        if (points == 0) return null
        return catalog.format(
            MessageKey.ANALYSIS_MOOD_CHANGE,
            "points" to kotlin.math.abs(points),
            // Roste podíl nespokojených = nálada jde dolů. Znaménko se čte obráceně, než vypadá.
            "direction" to catalog[if (points > 0) MessageKey.ANALYSIS_DOWN else MessageKey.ANALYSIS_UP],
        )
    }

    fun topicLine(topic: TopicInsight): String {
        val line =
            catalog.format(
                MessageKey.ANALYSIS_TOPIC_LINE,
                "name" to topic.name,
                "count" to topic.count,
                "share" to percent(topic.share),
                "stars" to (topic.avgStars?.let { RatingsDigest.formatRating(it) } ?: "—"),
            )
        val status =
            when (topic.status) {
                TopicStatus.NEW -> catalog[MessageKey.ANALYSIS_TOPIC_NEW]
                TopicStatus.GROWING -> catalog[MessageKey.ANALYSIS_TOPIC_GROWING]
                TopicStatus.FALLING -> catalog[MessageKey.ANALYSIS_TOPIC_FALLING]
                TopicStatus.STABLE -> null
            }
        return if (status == null) line else "$line · $status"
    }

    fun improvedLine(index: Int): String? {
        val improved = aggregates.improved.getOrNull(index) ?: return null
        return catalog.format(
            MessageKey.ANALYSIS_IMPROVED_LINE,
            "name" to improved.name,
            "before" to improved.before,
            "after" to improved.after,
        )
    }

    fun repliesLine(): String {
        val replies = aggregates.replies
        if (replies.replied == 0) return catalog[MessageKey.ANALYSIS_REPLIES_NONE]
        return catalog.format(
            MessageKey.ANALYSIS_REPLIES_LINE,
            "share" to percent(replies.share),
            "replied" to replies.replied,
            "total" to replies.total,
            "hours" to (replies.medianHours?.let { RatingsDigest.formatRating(it) } ?: "—"),
        )
    }

    /** Věta o krátké historii. Google Play vrací jen týden zpět a bez toho vypadá jako propad. */
    fun dataSinceLine(timezone: String): String? {
        val since = aggregates.dataSince ?: return null
        val zone = runCatching { TimeZone.of(timezone) }.getOrDefault(TimeZone.UTC)
        val date = since.toLocalDateTime(zone).date
        if (date <= aggregates.periodStart) return null
        return catalog.format(MessageKey.ANALYSIS_DATA_SINCE, "date" to formatDate(date))
    }

    fun tooFewLine(): String = catalog.format(MessageKey.ANALYSIS_TOO_FEW, "reviews" to aggregates.reviews)

    /** Citát i s tím, odkud je: `„…" · ★★☆☆☆ · Android · 3.2.0`. */
    fun quoteLine(): String? {
        val quote = quote ?: return null
        val parts =
            listOfNotNull(
                "★".repeat(quote.starRating) + "☆".repeat(MAX_STARS - quote.starRating),
                platformName(quote.platform),
                quote.appVersion,
            )
        return "„${quote.quote}\u201c · " + parts.joinToString(" · ")
    }

    /** Krátký souhrn do notifikace na mobilu — bloky ani karta se tam nevykreslí. */
    fun fallbackText(): String =
        "${catalog[MessageKey.ANALYSIS_TITLE]} $appName · " +
            if (aggregates.tooFewReviews) tooFewLine() else moodLine()

    fun formatDate(date: LocalDate): String =
        DATE_FORMAT.withLocale(catalog.dateLocale).format(java.time.LocalDate.of(date.year, date.month.number, date.day))

    /** Textový pruh nálady, stejná filozofie jako u hodnocení — je čitelný i bez grafiky. */
    fun moodBar(): String {
        val filled = (aggregates.sentiment.positive * BAR_CELLS).roundToInt().coerceIn(0, BAR_CELLS)
        return "▰".repeat(filled) + "▱".repeat(BAR_CELLS - filled)
    }

    companion object {
        fun percent(share: Double): Int = (share * PERCENT).roundToInt()

        fun platformName(platform: Platform): String =
            when (platform) {
                Platform.ANDROID -> "Android"
                Platform.IOS -> "iOS"
            }

        private const val PERCENT = 100
        private const val BAR_CELLS = 10
        private const val MAX_STARS = 5
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}
