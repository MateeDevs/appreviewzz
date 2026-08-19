package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.PostedMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Tenká vrstva nad Slack Web API. Dvě věci, které se u Slacku dělají špatně a tady se dělají
 * jednou pro všechny:
 *
 * - **`ok: false` v odpovědi s HTTP 200.** Slack skoro nikdy nevrací chybový status; kdo se
 *   dívá jen na kód, myslí si, že zpráva odešla. Tady se odpověď rozbaluje vždycky.
 * - **Mapování `error` na [ChannelErrorKind]**, aby scheduler poznal, co má smysl opakovat
 *   (`ratelimited`) a co potřebuje člověka (`not_in_channel`, `invalid_auth`).
 */
class SlackApi(
    private val httpClient: HttpClient,
    private val baseUrl: String = SLACK_API_BASE_URL,
) {
    suspend fun postMessage(
        token: SecretPayload,
        channel: String,
        blocks: JsonArray,
        fallbackText: String,
        metadata: JsonObject? = null,
        threadTs: String? = null,
    ): PostedMessage {
        val body =
            buildJsonObject {
                put("channel", channel)
                put("text", fallbackText.take(FALLBACK_TEXT_LIMIT))
                put("blocks", blocks)
                metadata?.let { put("metadata", it) }
                threadTs?.let { put("thread_ts", it) }
            }
        val response = call("chat.postMessage", token, body).payload
        return PostedMessage(
            conversationId = response["channel"]?.jsonPrimitive?.contentOrNull ?: channel,
            messageId =
                response["ts"]?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack nevrátil ts odeslané zprávy"),
        )
    }

    suspend fun updateMessage(
        token: SecretPayload,
        channel: String,
        ts: String,
        blocks: JsonArray,
        fallbackText: String,
    ) {
        val body =
            buildJsonObject {
                put("channel", channel)
                put("ts", ts)
                put("text", fallbackText.take(FALLBACK_TEXT_LIMIT))
                put("blocks", blocks)
            }
        call("chat.update", token, body)
    }

    /**
     * Ověření tokenu workspace. Používá se při ručním vložení tokenu (self-host, náš vlastní
     * workspace): z odpovědi se poznají údaje o workspace a z hlavičky i schválené scopes,
     * takže chybějící oprávnění je vidět hned, ne až první nedoručenou zprávou.
     */
    suspend fun authTest(token: SecretPayload): SlackInstall {
        val response = call("auth.test", token, buildJsonObject { })
        return SlackInstall(
            botToken = token.value,
            teamId =
                response.payload["team_id"]?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.AUTH, "Slack nevrátil ID workspace — je to opravdu bot token?"),
            teamName = response.payload["team"]?.jsonPrimitive?.contentOrNull,
            botUserId = response.payload["user_id"]?.jsonPrimitive?.contentOrNull,
            scopes = response.scopes,
        )
    }

    /** Odpověď Slack API i se schválenými scopes z hlavičky — ty v těle nejsou. */
    private data class SlackResponse(
        val payload: JsonObject,
        val scopes: String?,
    )

    private suspend fun call(
        method: String,
        token: SecretPayload,
        body: JsonObject,
    ): SlackResponse {
        val response =
            try {
                httpClient.post("$baseUrl/$method") {
                    bearerAuth(token.value)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (error: Exception) {
                throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack API je nedostupné", error)
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            val retryAfter = response.headers["Retry-After"]
            throw ChannelException(
                ChannelErrorKind.RATE_LIMITED,
                "Slack API omezuje tempo (retry-after ${retryAfter ?: "neuvedeno"})",
            )
        }
        if (response.status.value >= HttpStatusCode.InternalServerError.value) {
            throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack API vrátilo ${response.status.value}")
        }

        val payload =
            runCatching { json.parseToJsonElement(response.bodyAsText()) as JsonObject }
                .getOrElse { throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack API vrátilo nečitelnou odpověď", it) }
        val ok = payload["ok"]?.jsonPrimitive?.boolean == true
        if (ok) return SlackResponse(payload, response.headers[OAUTH_SCOPES_HEADER])

        val error = payload["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error"
        logger.warn { "Slack $method odmítl: $error" }
        throw ChannelException(kindOf(error), "Slack $method odmítl: $error")
    }

    private fun kindOf(error: String): ChannelErrorKind =
        when (error) {
            "invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired", "missing_scope",
            "no_permission",
            -> ChannelErrorKind.AUTH

            "channel_not_found", "not_in_channel", "is_archived", "message_not_found", "cant_update_message",
            -> ChannelErrorKind.NOT_FOUND

            "ratelimited", "rate_limited" -> ChannelErrorKind.RATE_LIMITED

            "fatal_error", "internal_error", "service_unavailable", "request_timeout" -> ChannelErrorKind.TRANSIENT

            // invalid_blocks, msg_too_long, invalid_arguments a spol.: retry by poslal totéž.
            else -> ChannelErrorKind.INVALID_REQUEST
        }

    companion object {
        const val SLACK_API_BASE_URL = "https://slack.com/api"

        /** Notifikační text (mobil, náhled). Slack delší stejně ořízne. */
        private const val FALLBACK_TEXT_LIMIT = 3_000

        private const val OAUTH_SCOPES_HEADER = "x-oauth-scopes"
    }
}
