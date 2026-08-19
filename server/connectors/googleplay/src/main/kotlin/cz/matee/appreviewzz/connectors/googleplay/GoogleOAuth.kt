package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.Signature
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * OAuth2 přes JWT grant: service accountem podepsané tvrzení vyměníme za access token.
 * Žádný uživatelský souhlas, žádný refresh token — přesně tenhle tok používá i dnešní n8n.
 */
class GoogleOAuth(
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
) {
    private val cache = ConcurrentHashMap<String, CachedToken>()

    suspend fun accessToken(
        account: GoogleServiceAccount,
        scope: String = ANDROID_PUBLISHER_SCOPE,
    ): String {
        val key = "${account.clientEmail}|$scope"
        val now = clock.now()
        cache[key]?.takeIf { it.expiresAt > now + EXPIRY_MARGIN }?.let { return it.token }

        val assertion = signedAssertion(account, scope, now)
        val response =
            try {
                httpClient.submitForm(
                    url = account.tokenUri,
                    formParameters =
                        parameters {
                            append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                            append("assertion", assertion)
                        },
                )
            } catch (error: java.io.IOException) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Google OAuth je nedostupný", error)
            }

        if (!response.status.isSuccess()) {
            throw response.toConnectorException(account)
        }

        val token = response.body<TokenResponse>()
        cache[key] = CachedToken(token.accessToken, now + token.expiresIn.seconds)
        return token.accessToken
    }

    /** Po rotaci klíče nebo při 401 nemá smysl zkoušet to znovu s tokenem z cache. */
    fun invalidate(account: GoogleServiceAccount) {
        cache.keys.removeIf { it.startsWith("${account.clientEmail}|") }
    }

    private fun signedAssertion(
        account: GoogleServiceAccount,
        scope: String,
        now: Instant,
    ): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val issuedAt = now.epochSeconds
        val claims =
            """{"iss":"${account.clientEmail}","scope":"$scope","aud":"${account.tokenUri}",""" +
                """"iat":$issuedAt,"exp":${issuedAt + ASSERTION_LIFETIME.inWholeSeconds}}"""
        val unsigned = "${header.base64Url()}.${claims.base64Url()}"
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(account.privateKey)
                update(unsigned.toByteArray(Charsets.UTF_8))
                sign()
            }
        return "$unsigned.${signature.base64Url()}"
    }

    private suspend fun HttpResponse.toConnectorException(account: GoogleServiceAccount): StoreConnectorException {
        // Tělo chyby od Googlu obsahuje jen kód a popis, ne credential — do hlášky patří,
        // protože přesně ono řekne klientovi „klíč byl smazaný" nebo „API není zapnuté".
        val detail = bodyAsText().take(ERROR_DETAIL_LIMIT)
        val kind =
            when {
                status == HttpStatusCode.TooManyRequests -> StoreErrorKind.RATE_LIMITED
                status.value >= HttpStatusCode.InternalServerError.value -> StoreErrorKind.TRANSIENT
                else -> StoreErrorKind.AUTH
            }
        return StoreConnectorException(
            kind,
            "Google nevydal access token pro ${account.clientEmail} (HTTP ${status.value}): $detail",
        )
    }

    companion object {
        const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"

        private val ASSERTION_LIFETIME = 60.minutes
        private val EXPIRY_MARGIN = 2.minutes
        private const val ERROR_DETAIL_LIMIT = 300
    }

    private class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

private fun String.base64Url(): String = toByteArray(Charsets.UTF_8).base64Url()

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
