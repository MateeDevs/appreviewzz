package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.port.TopicQuote
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Zpráva o výkyvu (B4) — jedna a tatáž čísla pro Slack i Teams.
 *
 * Zpráva **vždycky říká, proti čemu se to měří** („obvykle 1–2"). Alert bez baseline se
 * po druhém výskytu začne ignorovat, protože z něj nejde poznat, jestli je to opravdu
 * neobvyklé, nebo jen běžný čtvrtek.
 */
data class AnalysisAlertMessage(
    val appName: String,
    val locale: MessageLocale,
    val alert: AnalysisAlert,
    /** Název tématu v jazyce kanálu; u záporného výkyvu název nejčastějšího tématu. */
    val topicName: String?,
    /** Nejčastější téma dne a kolikrát padlo — u záporného výkyvu je to ta hlavní stopa. */
    val topTopic: Pair<String, Int>? = null,
    /** Verze, které se výkyv nejvíc týká; `null`, když ji store neuvádí. */
    val topVersion: Pair<String, Int>? = null,
    /** Nejvýš dva ověřené citáty — tři už nikdo nečte. */
    val quotes: List<TopicQuote> = emptyList(),
    val consoleUrl: String? = null,
) {
    val catalog: MessageCatalog = MessageCatalog.of(locale)

    fun headline(): String =
        when (alert.kind) {
            AlertKind.NEGATIVE_SPIKE ->
                catalog.format(
                    MessageKey.ALERT_NEGATIVE_LINE,
                    "observed" to alert.observed,
                    "date" to formatDate(alert.windowDate),
                    "expected" to expected(),
                )

            AlertKind.TOPIC_SPIKE ->
                catalog.format(
                    MessageKey.ALERT_TOPIC_LINE,
                    "observed" to alert.observed,
                    "name" to (topicName ?: alert.topicKey.orEmpty()),
                    "date" to formatDate(alert.windowDate),
                    "expected" to expected(),
                )
        }

    fun topTopicLine(): String? {
        val (name, count) = topTopic ?: return null
        // U výkyvu tématu by věta jen zopakovala nadpis.
        if (alert.kind == AlertKind.TOPIC_SPIKE) return null
        return catalog.format(MessageKey.ALERT_TOP_TOPIC, "name" to name, "count" to count)
    }

    fun topVersionLine(): String? {
        val (version, count) = topVersion ?: return null
        return catalog.format(MessageKey.ALERT_TOP_VERSION, "version" to version, "count" to count)
    }

    fun quoteLines(): List<String> = quotes.map { "„${it.quote}“ · " + AnalysisDigest.platformName(it.platform) }

    fun fallbackText(): String = "⚠️ ${catalog[MessageKey.ALERT_TITLE]} · $appName · ${headline()}"

    /**
     * Baseline jako rozsah, ne jako „1,43": desetinné číslo v takové větě vypadá přesněji,
     * než jaký ve skutečnosti je odhad ze čtyř týdnů.
     */
    private fun expected(): String {
        val low = alert.expected.toInt()
        val high = alert.expected.roundToInt()
        return if (high > low) "$low–$high" else "$low"
    }

    private fun formatDate(date: LocalDate): String =
        DATE_FORMAT.withLocale(catalog.dateLocale).format(java.time.LocalDate.of(date.year, date.month.number, date.day))

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
}
