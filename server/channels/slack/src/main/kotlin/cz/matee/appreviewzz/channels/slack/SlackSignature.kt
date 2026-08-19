package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Proč jsme požadavek nepřijali. Do odpovědi jde jen 401 — důvod patří do logu. */
enum class SignatureFailure {
    /** Chybí hlavička s podpisem nebo časem — tohle Slack nikdy neposílá. */
    MISSING,

    /** Podpis je starší (nebo z budoucnosti) než tolerance: nejspíš přehrávaný požadavek. */
    STALE,

    MISMATCH,
}

/**
 * Ověření, že požadavek přišel od Slacku
 * ([dokumentace](https://api.slack.com/authentication/verifying-requests-from-slack)).
 *
 * Dnešní n8n řešení ověřuje podpis taky, ale dvakrát nedotaženě: **nekontroluje stáří**
 * požadavku (takže zachycený požadavek jde přehrát kdykoli později) a porovnává podpis
 * obyčejným `!=` (časově závislé porovnání). Obojí je tady spravené.
 */
class SlackSignatureVerifier(
    private val signingSecret: SecretPayload,
    private val clock: Clock = Clock.System,
    private val tolerance: Duration = DEFAULT_TOLERANCE,
) {
    /** @return `null`, když je požadavek v pořádku; jinak důvod odmítnutí. */
    fun verify(
        timestamp: String?,
        signature: String?,
        rawBody: String,
    ): SignatureFailure? {
        if (timestamp.isNullOrBlank() || signature.isNullOrBlank()) return SignatureFailure.MISSING
        val sentAt = timestamp.toLongOrNull()?.let { Instant.fromEpochSeconds(it) } ?: return SignatureFailure.MISSING
        val age = clock.now() - sentAt
        if (age.absoluteValue > tolerance) return SignatureFailure.STALE

        val expected = sign(timestamp, rawBody)
        // Časově konstantní porovnání: délka podpisu je veřejná, ale obsah se nesmí uhádat po bajtech.
        val matches =
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                signature.toByteArray(Charsets.US_ASCII),
            )
        return if (matches) null else SignatureFailure.MISMATCH
    }

    private fun sign(
        timestamp: String,
        rawBody: String,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(signingSecret.value.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val digest = mac.doFinal("$VERSION:$timestamp:$rawBody".toByteArray(Charsets.UTF_8))
        return "$VERSION=" + digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
        const val SIGNATURE_HEADER = "X-Slack-Signature"

        private const val VERSION = "v0"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val DEFAULT_TOLERANCE = 5.minutes
    }
}
