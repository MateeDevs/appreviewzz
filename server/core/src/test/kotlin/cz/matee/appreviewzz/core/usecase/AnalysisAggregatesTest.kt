package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private val START = LocalDate(2026, 8, 24)
private val END = LocalDate(2026, 8, 30)

private fun period(
    reviews: Int,
    topics: List<TopicAggregate> = emptyList(),
    sentiments: Map<OverallSentiment, Int> = mapOf(OverallSentiment.NEGATIVE to reviews),
    starSum: Int = reviews * 3,
) = AnalysisPeriod(
    reviews = reviews,
    byPlatform = mapOf(Platform.ANDROID to reviews),
    starSum = starSum,
    sentiments = sentiments,
    topics = topics,
    versions = emptyList(),
)

private fun topic(
    key: String,
    count: Int,
    negative: Int = count,
    starSum: Int = count * 2,
) = TopicAggregate(key = key, count = count, negative = negative, positive = 0, starSum = starSum)

/**
 * Pravidla rozboru jsou čistá funkce nad dvěma obdobími — právě proto se dají otestovat
 * bez databáze a bez AI. To je celý smysl toho, že čísla nepočítá model.
 */
class AnalysisAggregatesTest :
    FunSpec({
        fun of(
            current: AnalysisPeriod,
            previous: AnalysisPeriod = AnalysisPeriod.EMPTY,
            replies: ReplyStats = ReplyStats(total = current.reviews, replied = 0, medianHours = null),
        ) = AnalysisAggregates.of(
            periodStart = START,
            periodEnd = END,
            current = current,
            previous = previous,
            replies = replies,
            dataSince = null,
            locale = MessageLocale.CS,
        )

        test("podíly nálady počítají smíšenou recenzi k nespokojeným") {
            val aggregates =
                of(
                    period(
                        reviews = 10,
                        sentiments =
                            mapOf(
                                OverallSentiment.POSITIVE to 5,
                                OverallSentiment.MIXED to 2,
                                OverallSentiment.NEGATIVE to 2,
                                OverallSentiment.NEUTRAL to 1,
                            ),
                    ),
                )

            aggregates.sentiment.positive shouldBe (0.5 plusOrMinus 0.001)
            aggregates.sentiment.negative shouldBe (0.4 plusOrMinus 0.001)
            aggregates.sentiment.neutral shouldBe (0.1 plusOrMinus 0.001)
        }

        test("téma pod prahem se do rozboru nedostane — jedna recenze není trend") {
            val aggregates = of(period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 2))))

            aggregates.topics.shouldBeEmpty()
        }

        test("pořadí problémů je velikost krát bolestivost, ne jen počet") {
            val aggregates =
                of(
                    period(
                        reviews = 40,
                        topics =
                            listOf(
                                topic(Topic.PRAISE.key, count = 20, negative = 0),
                                topic(Topic.CRASH.key, count = 9, negative = 9),
                            ),
                    ),
                )

            aggregates.topics.first().key shouldBe Topic.CRASH.key
        }

        test("nové téma se pozná podle toho, že minulý týden skoro nebylo") {
            val aggregates =
                of(
                    current = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 9))),
                    previous = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 1))),
                )

            aggregates.topics.single().status shouldBe TopicStatus.NEW
            aggregates.topics.single().previousCount shouldBe 1
        }

        test("rostoucí téma potřebuje dvojnásobek i rozdíl aspoň tří zmínek") {
            val doubled =
                of(
                    current = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 10))),
                    previous = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 4))),
                )
            doubled.topics.single().status shouldBe TopicStatus.GROWING

            // Dvojnásobek, ale rozdíl jen dvě zmínky — to ještě není trend.
            val small =
                of(
                    current = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 4))),
                    previous = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 2))),
                )
            small.topics.single().status shouldBe TopicStatus.STABLE
        }

        test("zlepšení se hlásí zvlášť, ne jako téma s malým počtem") {
            val aggregates =
                of(
                    current = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 1))),
                    previous = period(reviews = 20, topics = listOf(topic(Topic.CRASH.key, count = 9))),
                )

            val improved = aggregates.improved.single()
            improved.key shouldBe Topic.CRASH.key
            improved.before shouldBe 9
            improved.after shouldBe 1
            improved.name shouldBe Topic.CRASH.labelCs
        }

        test("pod deseti recenzemi se rozbor neposílá jako rozbor") {
            of(period(reviews = 9)).tooFewReviews shouldBe true
            of(period(reviews = 10)).tooFewReviews shouldBe false
        }

        test("bez minulého období se změna nálady nepředstírá") {
            of(period(reviews = 20)).previousNegativeDelta shouldBe null
        }

        test("podíl odpovědí a medián se přenášejí, jak přišly z databáze") {
            val aggregates = of(period(reviews = 20), replies = ReplyStats(total = 20, replied = 5, medianHours = 6.5))

            aggregates.replies.share shouldBe (0.25 plusOrMinus 0.001)
            aggregates.replies.medianHours shouldBe 6.5
        }
    })
