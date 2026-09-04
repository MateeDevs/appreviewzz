package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.AnalysisDigest
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.usecase.AnalysisAggregates
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

private fun period(
    reviews: Int,
    topics: List<TopicAggregate> = listOf(TopicAggregate(Topic.CRASH.key, reviews, reviews, 0, reviews)),
) = AnalysisPeriod(
    reviews = reviews,
    byPlatform = mapOf(Platform.ANDROID to reviews),
    starSum = reviews * 2,
    sentiments = mapOf(OverallSentiment.NEGATIVE to reviews),
    topics = topics,
    versions = emptyList(),
)

private fun digest(
    reviews: Int = 20,
    quote: TopicQuote? = null,
    consoleUrl: String? = "https://console.test/matee/recenze?app=1&topic=crash",
) = AnalysisDigest(
    appName = "IsleGrow",
    locale = MessageLocale.CS,
    aggregates =
        AnalysisAggregates.of(
            periodStart = LocalDate(2026, 8, 31),
            periodEnd = LocalDate(2026, 9, 6),
            current = period(reviews),
            previous = AnalysisPeriod.EMPTY,
            replies = ReplyStats(total = reviews, replied = 5, medianHours = 4.0),
            dataSince = null,
            locale = MessageLocale.CS,
        ),
    quote = quote,
    consoleUrl = consoleUrl,
)

/**
 * Týdenní rozbor ve Slacku. Zpráva nemá formulář ani tlačítko k odeslání — jediná akce je
 * odkaz do konzole, takže se nemůže splést s recenzí, na kterou se odpovídá.
 */
class SlackAnalysisDigestTest :
    FunSpec({
        test("rozbor má hlavičku, náladu, problémy a odkaz do konzole") {
            val blocks = SlackBlocks.analysisDigest(digest())

            val rendered = blocks.render()
            rendered shouldContain "Rozbor recenzí · IsleGrow"
            rendered shouldContain "Nálada"
            rendered shouldContain "Co nejvíc bolí"
            rendered shouldContain "Pády"
            rendered shouldContain "Odpovídání"

            val button =
                blocks
                    .ofType("actions")
                    .single()
                    .getValue("elements")
                    .jsonArray
                    .single()
                    .jsonObject
            button.text("url") shouldBe "https://console.test/matee/recenze?app=1&topic=crash"
            // Rozbor se neodpovídá — vstupní pole tady nemá co dělat.
            blocks.ofType("input").size shouldBe 0
        }

        test("ověřený citát jde do zprávy jako citace i s hvězdami a verzí") {
            val blocks =
                SlackBlocks.analysisDigest(
                    digest(
                        quote =
                            TopicQuote(
                                reviewId =
                                    cz.matee.appreviewzz.core.model
                                        .ReviewId(Uuid.random()),
                                quote = "po updatu to pořád padá",
                                starRating = 1,
                                platform = Platform.ANDROID,
                                appVersion = "3.2.0",
                            ),
                    ),
                )

            val rendered = blocks.render()
            rendered shouldContain "po updatu to pořád padá"
            rendered shouldContain "Android"
            rendered shouldContain "3.2.0"
        }

        test("pod prahem recenzí zpráva neplácá o trendech") {
            val rendered = SlackBlocks.analysisDigest(digest(reviews = 4)).render()

            rendered shouldContain "Málo recenzí na rozbor"
            rendered shouldNotContain "Co nejvíc bolí"
        }

        test("žádná sekce nepřeteče limit Slacku") {
            val many = (1..40).map { TopicAggregate("t$it", count = 50, negative = 50, positive = 0, starSum = 50) }
            val blocks =
                SlackBlocks.analysisDigest(
                    AnalysisDigest(
                        appName = "IsleGrow",
                        locale = MessageLocale.CS,
                        aggregates =
                            AnalysisAggregates.of(
                                periodStart = LocalDate(2026, 8, 31),
                                periodEnd = LocalDate(2026, 9, 6),
                                current = period(reviews = 500, topics = many),
                                previous = AnalysisPeriod.EMPTY,
                                replies = ReplyStats(total = 500, replied = 5, medianHours = 4.0),
                                dataSince = null,
                                locale = MessageLocale.CS,
                            ),
                    ),
                )

            blocks.ofType("section").forEach { block ->
                block
                    .at("text", "text")
                    ?.jsonPrimitive
                    ?.content
                    ?.length
                    ?.shouldBeLessThanOrEqual(3_000)
            }
        }
    })
