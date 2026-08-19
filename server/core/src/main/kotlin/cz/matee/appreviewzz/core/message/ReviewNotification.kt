package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.storeReplyMaxLength
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Recenze připravená k vykreslení do kanálu — všechno, co Slack i Teams potřebují, spočítané
 * jednou a stejně. Kanálový modul z toho skládá už jen bloky, resp. Adaptive Card, takže se
 * texty ani fallbacky nemůžou mezi kanály rozejít (v n8n se rozešly: iOS/Android šablony
 * a Slack/Teams verze si každá vymýšlela vlastní).
 */
data class ReviewNotification(
    val review: Review,
    val appName: String,
    /** Zóna aplikace — datum recenze má tým vidět ve svém čase, ne v UTC. */
    val timezone: String,
    val locale: MessageLocale,
    /** Návrh od AI; `null`, když provider není nastavený nebo selhal. Zpráva jde i bez něj. */
    val suggestedReply: String?,
    /** Autor recenzi po doručení přepsal — zpráva to musí odlišit od první notifikace. */
    val isUpdate: Boolean = false,
) {
    val catalog: MessageCatalog = MessageCatalog.of(locale)

    val platform: Platform get() = review.platform

    val authorName: String
        get() = review.authorName?.takeIf { it.isNotBlank() } ?: catalog[MessageKey.USER_FALLBACK]

    val starRating: Int get() = review.starRating

    /**
     * Text recenze tak, jak ho člověk uvidí: `Titulek: obsah`. App Store dává titulek zvlášť,
     * Google Play ho nemá vůbec — kanál tenhle rozdíl řešit nemá.
     */
    val text: String
        get() {
            val title = review.title?.trim()?.takeIf { it.isNotEmpty() }
            val body = review.body?.trim()?.takeIf { it.isNotEmpty() }
            val joined =
                when {
                    title != null && body != null -> "$title: $body"
                    else -> title ?: body ?: return catalog[MessageKey.MISSING_REVIEW_TEXT]
                }
            return joined.replace(WHITESPACE, " ")
        }

    val appVersion: String? get() = review.appVersion?.takeIf { it.isNotBlank() }

    /** Odpověď, která už ve storu je (naše dřívější, nebo napsaná ručně v Play Console / ASC). */
    val previousReply: String? get() = review.developerResponseBody?.takeIf { it.isNotBlank() }

    val replyCharLimit: Int get() = platform.storeReplyMaxLength

    /**
     * Varování „už jste odpověděli". Na obou platformách znamená další odpověď něco jiného:
     * Google Play přidá do konverzace, App Store tu předchozí přepíše.
     */
    val alreadyRepliedWarning: String?
        get() =
            previousReply?.let {
                when (platform) {
                    Platform.ANDROID -> catalog[MessageKey.ALREADY_REPLIED_WARNING_ANDROID]
                    Platform.IOS -> catalog[MessageKey.ALREADY_REPLIED_WARNING_IOS]
                }
            }

    /** Datum vzniku recenze v zóně appky a jazyce kanálu — fallback pro kanály bez dynamických dat. */
    fun formattedDate(): String =
        DATE_FORMAT
            .withLocale(catalog.dateLocale)
            .withZone(zoneId())
            .format(Instant.ofEpochMilli(review.submittedAt.toEpochMilliseconds()))

    /** Neznámou zónu appky nesmí zpráva odnést pádem — v nejhorším se datum ukáže v UTC. */
    private fun zoneId(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}
