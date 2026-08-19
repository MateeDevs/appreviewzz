package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * OAuth v2 install flow naší Slack Appky (plán §5.5).
 *
 * Scopes držíme na minimu, protože appka půjde do App Directory a každý scope navíc je
 * otázka v review: `chat:write` a `chat:write.public` na posílání zpráv (druhý znamená, že
 * klient nemusí bota do veřejného kanálu zvát), `channels:read` na výběr kanálu v consoli.
 * **Žádné user tokeny a žádný scope na historii kanálu** — stav „odpovězeno" skládáme z dat.
 */
class SlackOAuth(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val clientSecret: SecretPayload,
    private val scopes: List<String> = DEFAULT_SCOPES,
    private val apiBaseUrl: String = SlackApi.SLACK_API_BASE_URL,
    private val authorizeBaseUrl: String = AUTHORIZE_URL,
) {
    /** Adresa, na kterou se klient přesměruje. `state` je náš podepsaný token, ne náhodné číslo. */
    fun authorizeUrl(
        state: String,
        redirectUri: String,
    ): String =
        URLBuilder(authorizeBaseUrl)
            .apply {
                parameters.append("client_id", clientId)
                parameters.append("scope", scopes.joinToString(","))
                parameters.append("state", state)
                parameters.append("redirect_uri", redirectUri)
            }.buildString()

    /** Výměna kódu za token workspace. Kód je jednorázový a platí pár minut. */
    suspend fun exchange(
        code: String,
        redirectUri: String,
    ): SlackInstall {
        val response =
            try {
                httpClient.submitForm(
                    url = "$apiBaseUrl/oauth.v2.access",
                    formParameters =
                        parameters {
                            append("client_id", clientId)
                            append("client_secret", clientSecret.value)
                            append("code", code)
                            append("redirect_uri", redirectUri)
                        },
                )
            } catch (error: Exception) {
                throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack API je nedostupné", error)
            }

        val payload =
            runCatching { json.parseToJsonElement(response.bodyAsText()) as JsonObject }
                .getOrElse { throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack vrátil nečitelnou odpověď", it) }
        if (payload["ok"]?.jsonPrimitive?.boolean != true) {
            val error = payload["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error"
            logger.warn { "Instalace Slack Appky selhala: $error" }
            throw ChannelException(ChannelErrorKind.AUTH, "Slack instalaci odmítl: $error")
        }

        val team = payload["team"]?.jsonObject
        return SlackInstall(
            botToken =
                payload["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.AUTH, "Slack nevrátil token workspace"),
            teamId =
                team?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: throw ChannelException(ChannelErrorKind.AUTH, "Slack nevrátil ID workspace"),
            teamName = team["name"]?.jsonPrimitive?.contentOrNull,
            botUserId = payload["bot_user_id"]?.jsonPrimitive?.contentOrNull,
            scopes = payload["scope"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        const val AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize"

        val DEFAULT_SCOPES = listOf("chat:write", "chat:write.public", "channels:read")

        /** Adresa, kterou musí mít Slack App nastavenou jako Redirect URL. */
        fun redirectUri(publicBaseUrl: String): String = publicBaseUrl.trimEnd('/') + SLACK_OAUTH_CALLBACK_PATH
    }
}

const val SLACK_OAUTH_CALLBACK_PATH = "/slack/oauth/callback"
