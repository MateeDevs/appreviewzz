package cz.matee.appreviewzz.channels.slack

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Kliknutí na „Odeslat" pod recenzí. Všechno, co potřebuje reply pipeline — a nic víc:
 * konkrétní recenzi si dohledáme v databázi podle kanálu a `ts`, ne podle toho, co přišlo
 * v payloadu.
 */
data class SlackReplySubmission(
    val conversationId: String,
    val messageTs: String,
    val text: String,
    /** Slack user ID (`U123…`) — do audit logu a do „odpověděl(a) …". */
    val userId: String?,
    val userName: String?,
    val teamId: String?,
)

/**
 * Parsování interactivity payloadu. Slack ho posílá jako `application/x-www-form-urlencoded`
 * s jediným polem `payload` obsahujícím JSON.
 *
 * Vrací `null` pro všechno, co nás nezajímá (jiné akce, `view_submission`, kliknutí na jiné
 * tlačítko) — takový požadavek se má potvrdit a zahodit, ne zalogovat jako chybu.
 */
object SlackInteraction {
    fun parse(payloadJson: String): SlackReplySubmission? {
        val payload =
            runCatching { json.parseToJsonElement(payloadJson).jsonObject }
                .getOrElse {
                    logger.warn { "Slack interactivity payload nejde přečíst" }
                    return null
                }
        if (payload.string("type") != BLOCK_ACTIONS) return null

        val action =
            payload["actions"]
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it.string("action_id") == SlackBlocks.SUBMIT_ACTION_ID }
                ?: return null
        logger.debug { "Slack interakce ${action.string("action_id")}" }

        val container = payload["container"]?.jsonObject
        val conversationId = container?.string("channel_id") ?: payload["channel"]?.jsonObject?.string("id") ?: return null
        val messageTs =
            container?.string("message_ts")
                ?: payload["message"]?.jsonObject?.string("ts")
                ?: return null

        return SlackReplySubmission(
            conversationId = conversationId,
            messageTs = messageTs,
            text = replyText(payload).orEmpty(),
            userId = payload["user"]?.jsonObject?.string("id"),
            userName =
                payload["user"]?.jsonObject?.let { it.string("name") ?: it.string("username") },
            teamId = payload["team"]?.jsonObject?.string("id"),
        )
    }

    /**
     * Text z pojmenovaného vstupu. n8n si bralo „první blok, který má hodnotu", takže by ho
     * rozbil kterýkoli další vstup ve zprávě; tady se čte konkrétní `block_id`/`action_id`.
     */
    private fun replyText(payload: JsonObject): String? =
        payload["state"]
            ?.jsonObject
            ?.get("values")
            ?.jsonObject
            ?.get(SlackBlocks.REPLY_BLOCK_ID)
            ?.jsonObject
            ?.get(SlackBlocks.REPLY_ACTION_ID)
            ?.jsonObject
            ?.string("value")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private const val BLOCK_ACTIONS = "block_actions"
}
