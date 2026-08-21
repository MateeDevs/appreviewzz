package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.PostedMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Tenká vrstva nad Bot Framework Connector API — přesně ta část, kterou dnes n8n dělá holými
 * HTTP nody. Java SDK pro Bot Framework je opuštěné, takže vlastní vrstva vyjde i pro v2 líp
 * než závislost, kterou nikdo neudržuje (plán §5.5).
 *
 * Tři věci, které tu jsou navíc proti n8n:
 *
 * - **`serviceUrl` z instalace, ne natvrdo `…/emea`.** Regionální endpoint je vlastnost
 *   klientova tenantu; zadrátovaný evropský host by mimo Evropu tiše nedoručoval.
 * - **Mapování stavů na [ChannelErrorKind]**, aby scheduler poznal, co opakovat (429, 5xx)
 *   a co potřebuje člověka (401 odvolaný secret, 403 bot vyhozený z týmu).
 * - **Odpověď se rozbaluje.** `POST /v3/conversations` vrací `id` konverzace i `activityId`
 *   založené zprávy — bez obojího nejde zprávu později přepsat na „odpovězeno".
 */
class TeamsApi(
    private val httpClient: HttpClient,
) {
    /**
     * Založí v teamsovém kanálu nové vlákno s kartou. Každá recenze má tak vlastní konverzaci —
     * odpovědi ani chybová hlášení pak nezaplevelí kanál.
     */
    suspend fun createChannelConversation(
        token: String,
        install: TeamsInstall,
        teamsChannelId: String,
        card: JsonObject,
    ): PostedMessage {
        val body =
            buildJsonObject {
                put("isGroup", true)
                putJsonObject("channelData") {
                    putJsonObject("tenant") { put("id", install.tenantId) }
                    putJsonObject("channel") { put("id", teamsChannelId) }
                    install.teamId?.takeIf { it.isNotBlank() }?.let { putJsonObject("team") { put("id", it) } }
                }
                put("activity", messageActivity(card))
            }
        val payload = call(HttpMethod.Post, "${install.connectorBaseUrl()}/v3/conversations", token, body)
        return PostedMessage(
            conversationId =
                payload["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.TRANSIENT, "Teams nevrátil ID založené konverzace"),
            messageId =
                payload["activityId"]?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.TRANSIENT, "Teams nevrátil ID odeslané zprávy"),
        )
    }

    /** Přepíše existující kartu — formulář zmizí a zůstane odeslaná odpověď. */
    suspend fun updateActivity(
        token: String,
        serviceUrl: String,
        conversationId: String,
        activityId: String,
        card: JsonObject,
    ) {
        call(
            method = HttpMethod.Put,
            url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities/$activityId",
            token = token,
            body = messageActivity(card),
        )
    }

    /** Odpověď **do vlákna** pod kartou; původní karta i s formulářem zůstává, jde to zkusit znovu. */
    suspend fun replyToActivity(
        token: String,
        serviceUrl: String,
        conversationId: String,
        replyToId: String,
        card: JsonObject,
    ): PostedMessage {
        val body =
            buildJsonObject {
                messageActivity(card).forEach { (key, value) -> put(key, value) }
                put("replyToId", replyToId)
            }
        val payload =
            call(
                method = HttpMethod.Post,
                url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities",
                token = token,
                body = body,
            )
        return PostedMessage(
            conversationId = conversationId,
            messageId = payload["id"]?.jsonPrimitive?.contentOrNull ?: replyToId,
        )
    }

    private fun messageActivity(card: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "message")
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("contentType", ADAPTIVE_CARD_CONTENT_TYPE)
                        put("content", card)
                    },
                )
            }
        }

    private suspend fun call(
        method: HttpMethod,
        url: String,
        token: String,
        body: JsonObject,
    ): JsonObject {
        val response =
            try {
                httpClient.request(url) {
                    this.method = method
                    // Token bez CR/LF: n8n si ho takhle čistí ručně v každém nodu, tady stačí jednou.
                    bearerAuth(token.replace("\r", "").replace("\n", ""))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (error: Exception) {
                throw ChannelException(ChannelErrorKind.TRANSIENT, "Bot Framework je nedostupný", error)
            }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw failure(response.status, text)
        // Prázdné tělo je legitimní odpověď na PUT — volající si z něj stejně nic nebere.
        return runCatching { text.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it).jsonObject } }
            .getOrNull() ?: JsonObject(emptyMap())
    }

    private fun failure(
        status: HttpStatusCode,
        body: String,
    ): ChannelException {
        val detail =
            runCatching {
                json
                    .parseToJsonElement(body)
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull() ?: body.take(ERROR_DETAIL_LIMIT).ifBlank { status.description }
        val kind =
            when {
                status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> ChannelErrorKind.AUTH
                status == HttpStatusCode.NotFound || status == HttpStatusCode.Gone -> ChannelErrorKind.NOT_FOUND
                status == HttpStatusCode.TooManyRequests -> ChannelErrorKind.RATE_LIMITED
                status.value >= HttpStatusCode.InternalServerError.value -> ChannelErrorKind.TRANSIENT
                else -> ChannelErrorKind.INVALID_REQUEST
            }
        logger.warn { "Bot Framework odmítl požadavek: ${status.value} $detail" }
        return ChannelException(kind, "Teams odmítl zprávu (${status.value}): $detail")
    }

    companion object {
        const val ADAPTIVE_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.adaptive"

        private const val ERROR_DETAIL_LIMIT = 300
    }
}
