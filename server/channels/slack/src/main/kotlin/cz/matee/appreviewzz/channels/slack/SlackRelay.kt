package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Samostatný podpisový formát: interní relay podpis nelze použít jako Slack podpis. */
class SlackRelayAuthentication(
    private val secret: SecretPayload,
    private val clock: Clock = Clock.System,
) {
    fun sign(
        environment: SlackEnvironment,
        deliveryId: String,
        timestamp: String,
        body: String,
    ): String = slackHmac(secret, "appreviewz-relay:v1:${environment.wireName}:$deliveryId:$timestamp:$body")

    fun verify(
        environment: SlackEnvironment,
        deliveryId: String?,
        timestamp: String?,
        body: String,
        signature: String?,
    ): Boolean {
        if (deliveryId == null || !deliveryId.matches(Regex("[a-f0-9]{64}")) || timestamp == null || signature == null) return false
        val seconds = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(clock.now().epochSeconds.toDouble() - seconds.toDouble()) > 5.minutes.inWholeSeconds) return false
        return slackSignaturesEqual(sign(environment, deliveryId, timestamp, body), signature)
    }

    fun timestamp(): String = clock.now().epochSeconds.toString()

    companion object {
        const val ENVIRONMENT = "X-Appreviewz-Relay-Environment"
        const val DELIVERY_ID = "X-Appreviewz-Relay-Id"
        const val TIMESTAMP = "X-Appreviewz-Relay-Timestamp"
        const val SIGNATURE = "X-Appreviewz-Relay-Signature"
        const val PATH = "/webhooks/slack/relay"
    }
}

/** Cíle jsou pevná konfigurace serveru, nikdy URL z interakce nebo zprávy. */
class SlackRelayClient(
    private val http: HttpClient,
    private val authentication: SlackRelayAuthentication,
    private val targets: Map<SlackEnvironment, String>,
) {
    suspend fun forward(
        environment: SlackEnvironment,
        deliveryId: String,
        payload: String,
    ) {
        val base =
            targets[environment]
                ?: throw ChannelException(ChannelErrorKind.INVALID_REQUEST, "Cíl Slack relay ${environment.wireName} není nastavený")
        val timestamp = authentication.timestamp()
        val response =
            try {
                http.post(base.trimEnd('/') + SlackRelayAuthentication.PATH) {
                    contentType(ContentType.Application.Json)
                    header(SlackRelayAuthentication.ENVIRONMENT, environment.wireName)
                    header(SlackRelayAuthentication.DELIVERY_ID, deliveryId)
                    header(SlackRelayAuthentication.TIMESTAMP, timestamp)
                    header(SlackRelayAuthentication.SIGNATURE, authentication.sign(environment, deliveryId, timestamp, payload))
                    setBody(payload)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw ChannelException(ChannelErrorKind.TRANSIENT, "Slack relay je nedostupné", error)
            }
        if (response.status.value in 200..299) return
        val kind =
            when {
                response.status.value == 429 -> ChannelErrorKind.RATE_LIMITED
                response.status.value >= 500 -> ChannelErrorKind.TRANSIENT
                else -> ChannelErrorKind.INVALID_REQUEST
            }
        // Tělo chyby může nést uživatelský obsah; do logu ani DLQ ho nepřenášíme.
        throw ChannelException(kind, "Slack relay ${environment.wireName} vrátilo HTTP ${response.status.value}")
    }
}
