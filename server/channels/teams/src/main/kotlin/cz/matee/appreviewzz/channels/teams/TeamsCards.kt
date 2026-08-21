package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.message.MessageCatalog
import cz.matee.appreviewzz.core.message.MessageKey
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReplyRendering
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Adaptive Cards skládané z [ReviewNotification] — teamsový protějšek Block Kitu ve Slacku.
 *
 * Obsah je schválně **tentýž**: obojí se skládá z jednoho [ReviewNotification], takže se
 * texty ani fallbacky nemůžou mezi kanály rozejít. V n8n se rozešly hned dvakrát (Slack proti
 * Teams a iOS proti Androidu) a nikdo si toho měsíce nevšiml.
 *
 * Proti dnešní kartě jsou dva rozdíly:
 *
 * - **V `data` tlačítka není celá recenze.** n8n do payloadu balí `customerTexts`,
 *   `developerTexts` i `clientId` a reply listener jim věří — kdokoli s přístupem ke kartě
 *   tak může podstrčit cizí data. Tady jde ven jen `reviewId` pro dohledání a routing stejně
 *   jede přes databázi podle konverzace a aktivity.
 * - **Stabilní `id` vstupu** (`replyText`), takže se čte konkrétní pole, ne „první, co má hodnotu".
 */
internal object TeamsCards {
    const val REPLY_INPUT_ID = "replyText"
    const val SEND_VERB = "sendReply"

    /** Karta s recenzí: obsah + vstup s návrhem + tlačítko. */
    fun review(notification: ReviewNotification): JsonObject =
        card(
            buildJsonArray {
                content(notification).forEach { add(it) }
                add(replyContainer(notification))
            },
            actions = buildJsonArray { add(submitAction(notification)) },
        )

    /**
     * Táž karta po odeslání odpovědi: vstup i tlačítko jsou pryč, místo nich je vidět, co se
     * odeslalo a kdo to napsal. Skládá se ze stejného obsahu, takže se původní karta nemusí
     * číst zpátky z Teams.
     */
    fun replied(rendering: ReplyRendering): JsonObject {
        val notification = rendering.notification
        val catalog = notification.catalog
        val author = rendering.authorDisplayName?.takeIf { it.isNotBlank() }
        val stamp = listOfNotNull(author, notification.formattedDate()).joinToString(" · ")
        return card(
            buildJsonArray {
                content(notification).forEach { add(it) }
                addJsonObject {
                    put("type", "Container")
                    put("style", "good")
                    put("separator", true)
                    put("spacing", "Medium")
                    putJsonArray("items") {
                        add(textBlock("✅ **${catalog[MessageKey.YOU_REPLIED]}**", weight = "Bolder"))
                        add(textBlock(rendering.replyText.take(TEXT_LIMIT).ifBlank { "—" }))
                        if (stamp.isNotBlank()) add(textBlock(stamp, subtle = true, size = "Small"))
                    }
                }
            },
        )
    }

    /** Ověřovací zpráva po `channel test`: krátká, bez formuláře, ať se nesplete s recenzí. */
    fun connectivityCheck(
        catalog: MessageCatalog,
        appName: String,
    ): JsonObject =
        card(
            buildJsonArray {
                add(textBlock("✅ **${catalog[MessageKey.CONNECTION_OK_TITLE]}**", size = "Medium", weight = "Bolder"))
                add(textBlock(catalog.format(MessageKey.CONNECTION_OK_DETAIL, "app" to appName), subtle = true))
            },
        )

    /** Hlášení do vlákna, když store odpověď odmítl. */
    fun failure(
        notification: ReviewNotification,
        error: String,
    ): JsonObject {
        val catalog = notification.catalog
        return card(
            buildJsonArray {
                add(
                    textBlock(
                        "⚠️ **${catalog[MessageKey.REPLY_FAILED_TITLE]}**",
                        size = "Medium",
                        weight = "Bolder",
                        color = "Attention",
                    ),
                )
                add(textBlock("*${catalog[MessageKey.ERROR_LABEL]}:* ${error.ifBlank { "?" }.take(ERROR_LIMIT)}"))
            },
        )
    }

    /** Společná část karty: kdo, kolik hvězd, co napsal a k jaké verzi appky. */
    private fun content(notification: ReviewNotification): List<JsonObject> {
        val catalog = notification.catalog
        return buildList {
            if (notification.isUpdate) {
                add(textBlock("✏️ **${catalog[MessageKey.REVIEW_UPDATED]}**", subtle = true, size = "Small"))
            }
            add(header(notification))
            add(textBlock(notification.text.take(TEXT_LIMIT), size = "Large", weight = "Bolder", spacing = "Medium"))
            notification.previousReply?.let { previous ->
                add(
                    buildJsonObject {
                        put("type", "Container")
                        put("style", "emphasis")
                        put("separator", true)
                        putJsonArray("items") {
                            add(textBlock("**${catalog[MessageKey.YOU_ALREADY_REPLIED]}**"))
                            add(textBlock(previous.take(QUOTE_LIMIT), subtle = true))
                        }
                    },
                )
            }
            add(textBlock(footer(notification), subtle = true, size = "Small", separator = true))
        }
    }

    private fun header(notification: ReviewNotification): JsonObject =
        buildJsonObject {
            put("type", "ColumnSet")
            putJsonArray("columns") {
                addJsonObject {
                    put("type", "Column")
                    put("width", "auto")
                    putJsonArray("items") { add(textBlock(platformEmoji(notification.platform), size = "Large")) }
                }
                addJsonObject {
                    put("type", "Column")
                    put("width", "stretch")
                    putJsonArray("items") {
                        add(
                            textBlock(
                                "${notification.authorName} ${stars(notification.starRating)}",
                                size = "ExtraLarge",
                                weight = "Bolder",
                            ),
                        )
                    }
                }
            }
        }

    /** Vstup s návrhem odpovědi, případně s varováním, že už jednou někdo odpověděl. */
    private fun replyContainer(notification: ReviewNotification): JsonObject {
        val catalog = notification.catalog
        val limit = inputMaxLength(notification)
        return buildJsonObject {
            put("type", "Container")
            put("style", "emphasis")
            put("separator", true)
            put("spacing", "Medium")
            putJsonArray("items") {
                notification.alreadyRepliedWarning?.let { add(textBlock("⚠️ $it", color = "Warning")) }
                add(textBlock("✍️ ${catalog.format(MessageKey.SUGGESTED_REPLY_LABEL, "limit" to limit)}", weight = "Bolder"))
                addJsonObject {
                    put("type", "Input.Text")
                    put("id", REPLY_INPUT_ID)
                    put("isMultiline", true)
                    put("maxLength", limit)
                    notification.suggestedReply?.takeIf { it.isNotBlank() }?.let { put("value", it.take(limit)) }
                }
            }
        }
    }

    /**
     * `Action.Submit` (ne `Action.Execute`): parita s dneškem a hlavně žádný `invokeResponse`
     * do tří sekund. Publikace do storu jde přes frontu, karta se přepíše až potom.
     */
    private fun submitAction(notification: ReviewNotification): JsonObject =
        buildJsonObject {
            put("type", "Action.Submit")
            put("title", "🚀 ${notification.catalog[MessageKey.SEND]}")
            putJsonObject("data") {
                put("verb", SEND_VERB)
                put("reviewId", notification.review.id.toString())
            }
        }

    private fun footer(notification: ReviewNotification): String {
        val catalog = notification.catalog
        val appName = notification.appName.ifBlank { catalog[MessageKey.APP_FALLBACK] }
        val version = notification.appVersion?.let { "${catalog[MessageKey.VERSION_LABEL]} $it" }
        return listOfNotNull("📱 $appName", notification.formattedDate(), version).joinToString(" · ")
    }

    private fun card(
        body: JsonArray,
        actions: JsonArray? = null,
    ): JsonObject =
        buildJsonObject {
            put("\$schema", ADAPTIVE_CARD_SCHEMA)
            put("type", "AdaptiveCard")
            put("version", ADAPTIVE_CARD_VERSION)
            put("body", body)
            actions?.let { put("actions", it) }
        }

    @Suppress("LongParameterList")
    private fun textBlock(
        text: String,
        size: String? = null,
        weight: String? = null,
        color: String? = null,
        subtle: Boolean = false,
        separator: Boolean = false,
        spacing: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "TextBlock")
            put("text", text)
            put("wrap", true)
            size?.let { put("size", it) }
            weight?.let { put("weight", it) }
            color?.let { put("color", it) }
            if (subtle) put("isSubtle", true)
            if (separator) put("separator", true)
            spacing?.let { put("spacing", it) }
        }

    private fun platformEmoji(platform: Platform): String =
        when (platform) {
            Platform.ANDROID -> "🤖"
            Platform.IOS -> "🍎"
        }

    private fun stars(rating: Int): String {
        val full = rating.coerceIn(0, MAX_STARS)
        return "★".repeat(full) + "☆".repeat(MAX_STARS - full)
    }

    /**
     * Teams vstup je štědřejší než slackový, ale limit storu je stejný — bere se ten nižší,
     * ať se do pole nedá napsat víc, než store přijme.
     */
    private fun inputMaxLength(notification: ReviewNotification): Int = notification.replyCharLimit.coerceIn(1, INPUT_MAX_LENGTH)

    const val ADAPTIVE_CARD_SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json"
    const val ADAPTIVE_CARD_VERSION = "1.5"

    private const val MAX_STARS = 5
    private const val TEXT_LIMIT = 3_000
    private const val QUOTE_LIMIT = 1_000
    private const val INPUT_MAX_LENGTH = 6_000
    private const val ERROR_LIMIT = 500
}
