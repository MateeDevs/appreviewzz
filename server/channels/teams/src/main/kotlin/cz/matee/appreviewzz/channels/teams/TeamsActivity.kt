package cz.matee.appreviewzz.channels.teams

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Příchozí aktivita z Bot Connectoru — jen ta pole, na kterých stojí ověření podpisu a routing.
 * Zbytek těla nás nezajímá; kdo mu věří, dělá přesně chybu dnešního n8n (viz [TeamsCards]).
 */
data class TeamsActivity(
    val type: String,
    /** ID téhle aktivity. Bot Connector ho dává unikátní — drží na něm ochrana proti přehrání. */
    val id: String?,
    val channelId: String?,
    val serviceUrl: String?,
    val conversationId: String?,
    /** ID zprávy s kartou, pod kterou se kliklo. */
    val replyToId: String?,
    val tenantId: String?,
    val fromId: String?,
    val fromName: String?,
    val value: JsonObject?,
) {
    /** Kliknutí na „Odeslat" pod kartou; ostatní aktivity (`conversationUpdate`, …) se potvrdí a zahodí. */
    fun replySubmission(): TeamsReplySubmission? {
        if (type != MESSAGE_TYPE) return null
        val data = value ?: return null
        if (data.string("verb") != TeamsCards.SEND_VERB) return null
        val conversation = conversationId ?: return null
        val activity = replyToId ?: return null
        return TeamsReplySubmission(
            conversationId = conversation,
            activityId = activity,
            text = data.string(TeamsCards.REPLY_INPUT_ID).orEmpty(),
            serviceUrl = serviceUrl,
            userId = fromId,
            userName = fromName,
            tenantId = tenantId,
        )
    }

    companion object {
        const val MESSAGE_TYPE = "message"

        fun parse(rawBody: String): TeamsActivity? {
            val body =
                runCatching { json.parseToJsonElement(rawBody).jsonObject }
                    .getOrElse {
                        logger.warn { "Teams aktivita nejde přečíst" }
                        return null
                    }
            return TeamsActivity(
                type = body.string("type").orEmpty(),
                id = body.string("id"),
                channelId = body.string("channelId"),
                serviceUrl = body.string("serviceUrl"),
                conversationId = body["conversation"]?.jsonObject?.string("id"),
                replyToId = body.string("replyToId"),
                tenantId =
                    body["channelData"]
                        ?.jsonObject
                        ?.get("tenant")
                        ?.jsonObject
                        ?.string("id")
                        ?: body["conversation"]?.jsonObject?.string("tenantId"),
                fromId = body["from"]?.jsonObject?.string("id"),
                fromName = body["from"]?.jsonObject?.string("name"),
                value = body["value"]?.let { it as? JsonObject },
            )
        }
    }
}

/**
 * Kliknutí na „Odeslat" pod kartou. Všechno, co potřebuje reply pipeline — a nic víc:
 * konkrétní recenzi si dohledáme v databázi podle konverzace a aktivity, ne podle toho, co
 * přišlo v payloadu tlačítka.
 */
data class TeamsReplySubmission(
    val conversationId: String,
    val activityId: String,
    val text: String,
    /** Regionální Bot Connector; z něj se pozná, kam odeslat update karty. */
    val serviceUrl: String?,
    /** Teams user ID (`29:…`) — do audit logu a do „odpověděl(a) …". */
    val userId: String?,
    val userName: String?,
    val tenantId: String?,
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
