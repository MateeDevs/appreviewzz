package cz.matee.appreviewzz.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import kotlin.time.Instant

/**
 * Tajemství z RFC 6238, přílohy B: ASCII `12345678901234567890`. Testové vektory z RFC jsou
 * jediný způsob, jak si být jistý, že implementace není „skoro správně" — kód, který si sedí
 * sám se sebou, projde i s prohozeným pořadím bajtů.
 */
private val RFC_SECRET = SecretPayload("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

class TotpTest :
    FunSpec({

        test("base32 zakóduje tajemství z RFC") {
            Base32.encode("12345678901234567890".toByteArray(Charsets.US_ASCII)) shouldBe RFC_SECRET.value
        }

        test("base32 je obousměrné a snese mezery i pomlčky z ručního přepisu") {
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

            Base32.decode(Base32.encode(bytes))!!.toList() shouldBe bytes.toList()
            Base32.decode("GEZD GNBV-GY3T")!!.toList() shouldBe Base32.decode("GEZDGNBVGY3T")!!.toList()
        }

        test("base32 odmítne znak mimo abecedu") {
            Base32.decode("GEZD1NBV") shouldBe null
        }

        // RFC 6238 příloha B — osmimístné hodnoty zkrácené na našich šest číslic.
        listOf(
            59L to "287082",
            1_111_111_109L to "081804",
            1_111_111_111L to "050471",
            1_234_567_890L to "005924",
            2_000_000_000L to "279037",
            20_000_000_000L to "353130",
        ).forEach { (epochSeconds, expected) ->
            test("kód v čase $epochSeconds odpovídá vektoru z RFC") {
                val at = Instant.fromEpochSeconds(epochSeconds)

                Totp.code(RFC_SECRET, Totp.stepAt(at)) shouldBe expected
            }
        }

        test("kód si drží vedoucí nuly") {
            // `005924` není číslo 5924; kdyby se někde po cestě převedl na Int, tenhle test padne.
            Totp.code(RFC_SECRET, Totp.stepAt(Instant.fromEpochSeconds(1_234_567_890L))) shouldMatch Regex("""\d{6}""")
        }

        test("kód projde i s hodinami rozejitými o jeden krok") {
            val at = Instant.parse("2026-08-22T20:00:15Z")
            val previous = Totp.code(RFC_SECRET, Totp.stepAt(at) - 1)

            Totp.matchingStep(RFC_SECRET, previous, at).shouldNotBeNull()
        }

        test("kód o dva kroky starý neprojde") {
            val at = Instant.parse("2026-08-22T20:00:15Z")
            val old = Totp.code(RFC_SECRET, Totp.stepAt(at) - 2)

            Totp.matchingStep(RFC_SECRET, old, at) shouldBe null
        }

        test("už uplatněný krok se podruhé nepřijme") {
            val at = Instant.parse("2026-08-22T20:00:15Z")
            val step = Totp.stepAt(at)
            val code = Totp.code(RFC_SECRET, step)

            // Odposlechnutý kód jde jinak použít celé jeho třicetisekundové okno.
            Totp.matchingStep(RFC_SECRET, code, at, usedStep = step) shouldBe null
        }

        test("nesmysl místo kódu nespadne, jen neprojde") {
            val at = Instant.parse("2026-08-22T20:00:15Z")

            Totp.matchingStep(RFC_SECRET, "", at) shouldBe null
            Totp.matchingStep(RFC_SECRET, "abcdef", at) shouldBe null
            Totp.matchingStep(RFC_SECRET, "12345", at) shouldBe null
        }

        test("dvě tajemství po sobě nejsou stejná") {
            Totp.generateSecret().value shouldNotBe Totp.generateSecret().value
        }

        test("otpauth odkaz nese vydavatele v cestě i v parametru") {
            val uri = Totp.provisioningUri("appreviewzz", "tadeas@example.com", RFC_SECRET)

            uri shouldContain "otpauth://totp/appreviewzz:tadeas%40example.com"
            uri shouldContain "issuer=appreviewzz"
            uri shouldContain "secret=${RFC_SECRET.value}"
            uri shouldContain "digits=6"
            uri shouldContain "period=30"
        }

        test("záchranné kódy jsou různé a normalizace přežije přepis z papíru") {
            val codes = RecoveryCodes.generate()

            codes shouldHaveSize RecoveryCodes.COUNT
            codes.toSet() shouldHaveSize RecoveryCodes.COUNT
            RecoveryCodes.hash("ABCDE-FGHJK").toList() shouldBe RecoveryCodes.hash("abcdefghjk").toList()
        }
    })
