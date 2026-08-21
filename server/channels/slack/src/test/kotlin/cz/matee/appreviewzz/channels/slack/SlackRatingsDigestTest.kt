package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.PlatformRatings
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate

private val TODAY = LocalDate(2026, 8, 21)

private fun part(
    platform: Platform = Platform.IOS,
    average: Double? = 4.62,
    previousAverage: Double? = 4.5,
    total: Long? = 1050,
    previousTotal: Long? = 1000,
    newRatings: Map<Int, Long> = mapOf(1 to 2L, 2 to 1L, 3 to 3L, 4 to 14L, 5 to 30L),
    asOf: LocalDate = TODAY,
    previousAsOf: LocalDate? = LocalDate(2026, 8, 20),
) = PlatformRatings(platform, average, total, previousAverage, previousTotal, newRatings, asOf, previousAsOf)

private fun digest(
    parts: List<PlatformRatings> = listOf(part()),
    locale: MessageLocale = MessageLocale.CS,
) = RatingsDigest("IsleGrow", locale, "Europe/Prague", TODAY, parts)

class SlackRatingsDigestTest :
    FunSpec({
        test("přehled ukazuje celkový průměr, změnu od minule a rozpad nových hodnocení") {
            val blocks = SlackBlocks.ratingsDigest(digest()).render()

            blocks shouldContain "Souhrn hodnocení"
            blocks shouldContain "IsleGrow"
            blocks shouldContain "4.62"
            // Δ je proti minulému přehledu, ne proti celkovému průměru jako v n8n.
            blocks shouldContain "Δ od minule"
            blocks shouldContain "▲ +0.12"
            blocks shouldContain "Nová hodnocení*: 50"
            blocks shouldContain "5★: 30"
        }

        test("první přehled řekne, že není s čím srovnávat") {
            val first =
                SlackBlocks
                    .ratingsDigest(
                        digest(listOf(part(previousAverage = null, previousTotal = null, newRatings = emptyMap(), previousAsOf = null))),
                    ).render()

            first shouldContain "První přehled"
            // Bez srovnání se nesmí objevit ani šipka, ani nula — obojí by lhalo.
            first shouldNotContain "Δ od minule"
        }

        test("žádná nová hodnocení nejsou propad, ale nula") {
            val quiet =
                SlackBlocks
                    .ratingsDigest(
                        digest(
                            listOf(part(average = 4.5, previousAverage = 4.5, total = 1000, previousTotal = 1000, newRatings = emptyMap())),
                        ),
                    ).render()

            quiet shouldContain "Žádná nová hodnocení"
            // Dnešní n8n při nule ukáže ▼ −4.5, protože počítá průměr nových minus celkový.
            quiet shouldNotContain "▼"
        }

        test("obě platformy jsou v jedné zprávě, Android první") {
            val both =
                SlackBlocks
                    .ratingsDigest(digest(listOf(part(platform = Platform.ANDROID), part(platform = Platform.IOS))))
                    .render()

            both.indexOf("Android") shouldBe both.indexOf("Android")
            (both.indexOf("Android") < both.indexOf("iOS")) shouldBe true
            both shouldContain "🤖"
            both shouldContain "🍎"
        }

        test("data staršího data se přiznají, ne že by se tvářila jako dnešek") {
            val stale = SlackBlocks.ratingsDigest(digest(listOf(part(asOf = LocalDate(2026, 8, 19))))).render()

            stale shouldContain "19"
        }

        test("anglický kanál dostane anglický přehled") {
            val english = SlackBlocks.ratingsDigest(digest(locale = MessageLocale.EN)).render()

            english shouldContain "Ratings Summary"
            english shouldContain "Δ since last"
            english shouldNotContain "Souhrn hodnocení"
        }
    })
