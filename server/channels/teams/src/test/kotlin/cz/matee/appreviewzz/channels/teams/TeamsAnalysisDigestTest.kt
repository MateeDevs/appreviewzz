package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.message.AnalysisDigest
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.usecase.AnalysisAggregates
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun digest(
    reviews: Int = 20,
    consoleUrl: String? = "https://console.test/matee/rozbory",
) = AnalysisDigest(
    appName = "IsleGrow",
    locale = MessageLocale.CS,
    aggregates =
        AnalysisAggregates.of(
            periodStart = LocalDate(2026, 8, 31),
            periodEnd = LocalDate(2026, 9, 6),
            current =
                AnalysisPeriod(
                    reviews = reviews,
                    byPlatform = mapOf(Platform.ANDROID to reviews),
                    starSum = reviews * 2,
                    sentiments = mapOf(OverallSentiment.NEGATIVE to reviews),
                    topics = listOf(TopicAggregate(Topic.CRASH.key, reviews, reviews, 0, reviews)),
                    versions = emptyList(),
                ),
            previous = AnalysisPeriod.EMPTY,
            replies = ReplyStats(total = reviews, replied = 5, medianHours = 4.0),
            dataSince = null,
            locale = MessageLocale.CS,
        ),
    consoleUrl = consoleUrl,
)

/** Týdenní rozbor v Teams. Stejná čísla i pořadí jako ve Slacku — šablona je jedna. */
class TeamsAnalysisDigestTest :
    FunSpec({
        test("karta nese náladu, problémy, odpovídání a odkaz do konzole") {
            val card = TeamsCards.analysisDigest(digest())

            val rendered = card.toString()
            rendered shouldContain "Rozbor recenzí · IsleGrow"
            rendered shouldContain "Nálada"
            rendered shouldContain "Pády"
            rendered shouldContain "Odpovídání"

            val action = card["actions"]!!.jsonArray.single().jsonObject
            action["type"]!!.jsonPrimitive.content shouldBe "Action.OpenUrl"
            action["url"]!!.jsonPrimitive.content shouldBe "https://console.test/matee/rozbory"
        }

        test("pod prahem recenzí karta neplácá o trendech") {
            val rendered = TeamsCards.analysisDigest(digest(reviews = 4)).toString()

            rendered shouldContain "Málo recenzí na rozbor"
            rendered shouldNotContain "Co nejvíc bolí"
        }
    })
