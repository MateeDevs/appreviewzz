package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}
private val tokenJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
private data class TokenError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * Token pro Bot Connector (`client_credentials`, scope `api.botframework.com/.default`).
 *
 * Dnešní n8n si o token říká **při každém requestu** a v každém ze čtyř workflow zvlášť.
 * Token přitom platí hodinu — tady se cachuje do vypršení a sdílí ho odesílání recenzí,
 * denní přehledy i update karet po odpovědi.
 */
class TeamsTokens(
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
    private val loginBaseUrl: String = MICROSOFT_LOGIN_BASE_URL,
) {
    private val cache = ConcurrentHashMap<String, CachedToken>()

    suspend fun accessToken(bot: TeamsBotIdentity): String {
        val authority = bot.tokenAuthority()
        val now = clock.now()
        cache[authority]?.takeIf { it.expiresAt > now + EXPIRY_MARGIN }?.let { return it.token }

        val response =
            try {
                httpClient.submitForm(
                    url = "$loginBaseUrl/$authority/oauth2/v2.0/token",
                    formParameters =
                        parameters {
                            append("grant_type", "client_credentials")
                            append("client_id", bot.appId)
                            append("client_secret", bot.appPassword.value)
                            append("scope", BOT_CONNECTOR_SCOPE)
                        },
                )
            } catch (error: Exception) {
                throw ChannelException(ChannelErrorKind.TRANSIENT, "Microsoft Entra je nedostupné", error)
            }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw tokenFailure(response.status, body)

        val token =
            runCatching { tokenJson.decodeFromString(TokenResponse.serializer(), body) }
                .getOrElse { throw ChannelException(ChannelErrorKind.TRANSIENT, "Entra vrátila nečitelnou odpověď", it) }
        // Vlastní expirace se odvozuje z `expires_in`, ne z těla tokenu: parsovat cizí JWT jen
        // kvůli platnosti by znamenalo věřit něčemu, co stejně jen předáváme dál.
        val expiresAt = now + (token.expiresIn.takeIf { it > 0 } ?: DEFAULT_LIFETIME_SECONDS).seconds
        cache[authority] = CachedToken(token.accessToken, expiresAt)
        logger.debug { "Nový token pro Bot Connector (autorita $authority), platí do $expiresAt" }
        return token.accessToken
    }

    /** Po rotaci secretu nebo po 401 nemá smysl posílat token z cache znovu. */
    fun invalidate(bot: TeamsBotIdentity) {
        cache.remove(bot.tokenAuthority())
    }

    private fun tokenFailure(
        status: HttpStatusCode,
        body: String,
    ): ChannelException {
        val detail =
            runCatching { tokenJson.decodeFromString(TokenError.serializer(), body) }
                .getOrNull()
                ?.let { it.errorDescription ?: it.error }
                ?.lineSequence()
                ?.first()
        logger.warn { "Entra odmítla vydat token pro bota: ${status.value} ${detail ?: "bez detailu"}" }
        // Špatný secret ani neexistující tenant se opakováním nespraví — chce to člověka.
        val kind = if (status.value >= HttpStatusCode.InternalServerError.value) ChannelErrorKind.TRANSIENT else ChannelErrorKind.AUTH
        return ChannelException(kind, "Token pro Teams bota se nepodařilo získat: ${detail ?: status.description}")
    }

    private class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    companion object {
        const val MICROSOFT_LOGIN_BASE_URL = "https://login.microsoftonline.com"
        const val BOT_CONNECTOR_SCOPE = "https://api.botframework.com/.default"

        /** Kolik před vypršením si řekneme o nový; hodinový token to nepocítí. */
        private val EXPIRY_MARGIN = 5.minutes
        private const val DEFAULT_LIFETIME_SECONDS = 3_600L
    }
}
