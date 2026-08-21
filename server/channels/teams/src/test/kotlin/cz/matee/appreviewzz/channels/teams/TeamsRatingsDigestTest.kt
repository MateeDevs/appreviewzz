package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.message.PlatformRatings
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ChannelTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.toByteArray
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val TODAY = LocalDate(2026, 8, 21)

private fun part(
    platform: Platform = Platform.IOS,
    average: Double? = 4.62,
    previousAverage: Double? = 4.5,
    newRatings: Map<Int, Long> = mapOf(1 to 2L, 2 to 1L, 3 to 3L, 4 to 14L, 5 to 30L),
    previousAsOf: LocalDate? = LocalDate(2026, 8, 20),
) = PlatformRatings(platform, average, 1050, previousAverage, 1000, newRatings, TODAY, previousAsOf)

private fun digest(
    parts: List<PlatformRatings> = listOf(part()),
    locale: MessageLocale = MessageLocale.CS,
) = RatingsDigest("IsleGrow", locale, "Europe/Prague", TODAY, parts)

class TeamsRatingsDigestTest :
    FunSpec({
        test("karta ukazuje průměr, změnu od minule a rozpad nových hodnocení") {
            val card = TeamsCards.ratingsDigest(digest()).render()

            card shouldContain "Souhrn hodnocení"
            card shouldContain "4.62"
            card shouldContain "Δ od minule"
            card shouldContain "▲ +0.12"
            card shouldContain "5★: 30"
            // Pruh musí být neproporcionálním písmem, jinak z něj je čára.
            card shouldContain "Monospace"
        }

        test("přehled nemá formulář ani tlačítko — je to zpráva, ne úkol") {
            val card = TeamsCards.ratingsDigest(digest())

            card.render() shouldNotContain TeamsCards.REPLY_INPUT_ID
            card["actions"] shouldBe null
        }

        test("první přehled řekne, že není s čím srovnávat") {
            val first =
                TeamsCards
                    .ratingsDigest(
                        digest(listOf(part(previousAverage = null, newRatings = emptyMap(), previousAsOf = null))),
                    ).render()

            first shouldContain "První přehled"
            first shouldNotContain "Δ od minule"
        }

        test("obsah karty odpovídá slackové zprávě — obojí se skládá z jednoho přehledu") {
            val card = TeamsCards.ratingsDigest(digest(listOf(part(platform = Platform.ANDROID), part()))).render()

            card shouldContain "🤖"
            card shouldContain "🍎"
            (card.indexOf("Android") < card.indexOf("iOS")) shouldBe true
        }

        test("přehled odchází do kanálu jako nové vlákno") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.toString().contains("/oauth2/")) {
                        tokenResponse()
                    } else {
                        respondJson("""{"id":"19:conv","activityId":"1"}""")
                    }
                }
            val client = engine.client()

            TeamsNotificationChannel(TeamsApi(client), TeamsTokens(client), BOT)
                .postRatingsDigest(ChannelTarget(TEAMS_CHANNEL_ID, INSTALL), digest())

            val request = engine.requests.last()
            request.url.toString() shouldBe "$SERVICE_URL/v3/conversations"
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body.render() shouldContain "Souhrn hodnocení"
        }
    })
