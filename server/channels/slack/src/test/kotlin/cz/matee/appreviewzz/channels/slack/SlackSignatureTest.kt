package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val SECRET = SecretPayload("8f742231b10e8888abcd99yyyzzz85a5")
private val NOW = Instant.parse("2026-08-19T12:00:00Z")
private const val BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D"

private fun signature(
    timestamp: Long,
    body: String = BODY,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SECRET.value.toByteArray(), "HmacSHA256"))
    return "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun verifier(now: Instant = NOW): SlackSignatureVerifier =
    SlackSignatureVerifier(
        signingSecret = SECRET,
        clock =
            object : Clock {
                override fun now(): Instant = now
            },
    )

class SlackSignatureTest :
    FunSpec({
        test("platný podpis projde") {
            val ts = NOW.epochSeconds

            verifier().verify(ts.toString(), signature(ts), BODY) shouldBe null
        }

        test("chybějící hlavičky nejsou od Slacku") {
            verifier().verify(null, "v0=abc", BODY) shouldBe SignatureFailure.MISSING
            verifier().verify(NOW.epochSeconds.toString(), null, BODY) shouldBe SignatureFailure.MISSING
            verifier().verify("nesmysl", "v0=abc", BODY) shouldBe SignatureFailure.MISSING
        }

        test("starý požadavek se odmítne, i když podpis sedí — to je přehrání") {
            val old = (NOW - 10.minutes).epochSeconds

            verifier().verify(old.toString(), signature(old), BODY) shouldBe SignatureFailure.STALE
        }

        test("podpis z budoucnosti se odmítne stejně jako starý") {
            val future = (NOW + 10.minutes).epochSeconds

            verifier().verify(future.toString(), signature(future), BODY) shouldBe SignatureFailure.STALE
        }

        test("změna těla podpis rozbije") {
            val ts = NOW.epochSeconds

            verifier().verify(ts.toString(), signature(ts), BODY + "&podvrzeno=1") shouldBe SignatureFailure.MISMATCH
        }

        test("cizí podpis neprojde") {
            val ts = NOW.epochSeconds

            verifier().verify(ts.toString(), "v0=" + "0".repeat(64), BODY) shouldBe SignatureFailure.MISMATCH
        }
    })
