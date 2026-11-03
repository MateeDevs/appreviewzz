package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Komu instalační odkaz patří a do kdy platí. */
data class SlackInstallState(
    val orgId: String,
    val expiresAt: Instant,
    val returnPath: String? = null,
    val environment: String = "local",
)

/**
 * Podepsaný `state` pro OAuth. Není to jen ochrana proti CSRF, jak to Slack popisuje —
 * je to **jediné, co váže instalaci na organizaci**, protože instalační odkaz otevírá klient
 * v prohlížeči, kde ještě nikdo přihlášený není. Bez podpisu by si kdokoli mohl nainstalovat
 * appku „za cizí organizaci" a začal by dostávat její recenze.
 *
 * Podepsaný obsah nese organizaci, expiraci, návratovou cestu a náhodné ID pokusu.
 * Klíč je signing secret Slack Appky. Prostředí je také podepsané, protože tajemství je společné.
 * Odkaz má krátkou platnost, aby se nedal poslat dál a použít za měsíc.
 */
class SlackInstallStates(
    private val secret: SecretPayload,
    private val clock: Clock = Clock.System,
    private val validity: Duration = DEFAULT_VALIDITY,
    private val environment: String = "local",
) {
    fun issue(
        orgId: String,
        returnPath: String? = null,
    ): String {
        require(SEPARATOR !in orgId) { "ID organizace nesmí obsahovat '$SEPARATOR'" }
        require(returnPath == null || safeReturnPath(returnPath)) { "Návrat musí vést do konzole" }
        val body =
            listOf(
                orgId,
                (clock.now() + validity).epochSeconds,
                encode(returnPath.orEmpty()),
                Uuid.random(),
                environment,
            ).joinToString(SEPARATOR)
        return encode(body) + TOKEN_SEPARATOR + encode(sign(body))
    }

    /** @return `null`, když je odkaz podvržený, poškozený nebo prošlý. */
    fun verify(state: String?): SlackInstallState? {
        val parts = state?.split(TOKEN_SEPARATOR) ?: return null
        if (parts.size != 2) return null
        val body = decode(parts[0]) ?: return null
        val signature = decode(parts[1]) ?: return null
        if (!MessageDigest.isEqual(sign(body).toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))) {
            return null
        }

        val fields = body.split(SEPARATOR)
        // Nesdílíme instalační odkazy mezi databázemi, i když mají společnou Slack App.
        if (fields.size != 5 || fields[4] != environment) return null
        val expiresAt = fields[1].toLongOrNull()?.let { Instant.fromEpochSeconds(it) } ?: return null
        if (clock.now() > expiresAt) return null
        val returnPath = decode(fields[2])?.takeIf { it.isNotEmpty() }
        if (returnPath != null && !safeReturnPath(returnPath)) return null
        return SlackInstallState(orgId = fields[0], expiresAt = expiresAt, returnPath = returnPath, environment = environment)
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.value.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString(separator = "") { "%02x".format(it) }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? = runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()

    companion object {
        fun safeReturnPath(path: String): Boolean =
            path.startsWith("/") &&
                !path.startsWith("//") &&
                path.length <= 2_000 &&
                path.none { it == '\\' || it.isISOControl() } &&
                '%' !in path.substringBefore('?') &&
                '#' !in path

        /** Delší platnost nedává smysl: odkaz se generuje ve chvíli, kdy si ho klient rozklikne. */
        val DEFAULT_VALIDITY: Duration = 2.hours

        private const val SEPARATOR = ":"
        private const val TOKEN_SEPARATOR = "."
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
