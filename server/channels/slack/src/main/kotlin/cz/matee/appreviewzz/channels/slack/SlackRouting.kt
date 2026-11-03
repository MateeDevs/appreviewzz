package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

enum class SlackEnvironment(
    val wireName: String,
) {
    PROD("prod"),
    STAGING("staging"),
    LOCAL("local"),
    ;

    companion object {
        fun parse(value: String): SlackEnvironment? = entries.firstOrNull { it.wireName == value }

        fun deployment(value: String): SlackEnvironment =
            when (value) {
                "prod", "production" -> PROD
                "staging", "alpha", "dev" -> STAGING
                else -> LOCAL
            }
    }
}

data class SlackMessageRoute(
    val environment: SlackEnvironment,
    val orgId: String,
    val reviewId: String,
)

/** Podepsaná hodnota tlačítka. Viditelná patička ALPHA nemá vliv na směrování. */
class SlackMessageRouting(
    private val secret: SecretPayload,
) {
    fun issue(
        environment: SlackEnvironment,
        orgId: String,
        reviewId: String,
    ): String {
        val body = "$PREFIX${environment.wireName}:$orgId:$reviewId"
        return "$body:${slackHmac(secret, body)}"
    }

    fun verify(value: String): SlackMessageRoute? {
        val parts = value.split(':')
        if (parts.size != 6 || !value.startsWith(PREFIX)) return null
        val body = value.substringBeforeLast(':')
        if (!slackSignaturesEqual(slackHmac(secret, body), parts[5])) return null
        val environment = SlackEnvironment.parse(parts[2]) ?: return null
        if (runCatching {
                Uuid.parse(parts[3])
                Uuid.parse(parts[4])
            }.isFailure
        ) {
            return null
        }
        return SlackMessageRoute(environment, parts[3], parts[4])
    }

    companion object {
        const val PREFIX = "appreviewz:v1:"

        fun isRouted(value: String?): Boolean = value?.startsWith("appreviewz:") == true
    }
}

internal fun slackHmac(
    secret: SecretPayload,
    body: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.value.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun slackSignaturesEqual(
    expected: String,
    actual: String,
): Boolean = MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))
