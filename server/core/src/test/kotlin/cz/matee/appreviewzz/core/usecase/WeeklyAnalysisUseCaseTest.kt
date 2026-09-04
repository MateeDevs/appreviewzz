package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.port.TopicQuote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ORG = OrganizationId(Uuid.random())

/** Pondělí 7. 9. 2026 v 8:30 pražského času — rozbor tedy patří týdnu 31. 8. – 6. 9. */
private val MONDAY_MORNING = Instant.parse("2026-09-07T06:30:00Z")

private fun period(
    reviews: Int,
    topicCount: Int = reviews,
) = AnalysisPeriod(
    reviews = reviews,
    byPlatform = mapOf(Platform.ANDROID to reviews),
    starSum = reviews * 2,
    sentiments = mapOf(OverallSentiment.NEGATIVE to reviews),
    topics = listOf(TopicAggregate(Topic.CRASH.key, count = topicCount, negative = topicCount, positive = 0, starSum = topicCount * 2)),
    versions = emptyList(),
)

private class WeeklyFixture(
    current: AnalysisPeriod = period(20),
    previous: AnalysisPeriod = AnalysisPeriod.EMPTY,
    quote: TopicQuote? = null,
    deliverAnalyses: Boolean = true,
) {
    val apps = FakeAppRepository()
    val organizations = FakeOrganizationRepository()
    val channelRepository = FakeChannelRepository()
    val insights = FakeReviewInsightRepository()
    val slack = FakeNotificationChannel()
    val app = apps.put(Ingest.app(ORG))

    init {
        organizations.put(Organization(id = ORG, name = "Matee", slug = "matee", createdAt = Delivery.now))
        channelRepository.put(Delivery.channel(ORG, app.id).copy(deliverAnalyses = deliverAnalyses))
        // Bez jediného výkladu by se rozbor neposílal; tenhle příznak reprezentuje „něco už máme".
        insights.awaiting()
    }

    val useCase =
        WeeklyAnalysisUseCase(
            apps = apps,
            organizations = organizations,
            channels = channelRepository,
            insights = insights,
            aggregates =
                FakeAnalysisAggregateRepository(
                    current = current,
                    previous = previous,
                    replies = ReplyStats(total = current.reviews, replied = 4, medianHours = 5.0),
                    quote = quote,
                ),
            appTopics = FakeAppTopicRepository(),
            digests = FakeAnalysisDigestRepository(),
            secrets = secretResolver("xoxb-token"),
            links = ConsoleLinks("https://console.test"),
            notificationChannels = listOf(slack),
            clock = fixedClock(MONDAY_MORNING),
        )
}

/**
 * Týdenní rozbor. Zajímavé jsou tři věci: že se počítá **minulý celý týden**, že se do kanálu
 * nepošle dvakrát, a že rozbor pod prahem recenzí neplácá o trendech.
 */
class WeeklyAnalysisUseCaseTest :
    FunSpec({
        test("období je minulé pondělí až neděle v zóně aplikace") {
            val fixture = WeeklyFixture()

            val report = fixture.useCase.run(ORG, fixture.app.id)

            val aggregates = report.aggregates.shouldNotBeNull()
            aggregates.periodStart shouldBe LocalDate(2026, 8, 31)
            aggregates.periodEnd shouldBe LocalDate(2026, 9, 6)
        }

        test("rozbor odejde do kanálu s odkazem na téma, které otevírá") {
            val fixture =
                WeeklyFixture(
                    quote =
                        TopicQuote(
                            reviewId =
                                cz.matee.appreviewzz.core.model
                                    .ReviewId(Uuid.random()),
                            quote = "pořád to padá",
                            starRating = 1,
                            platform = Platform.ANDROID,
                            appVersion = "3.2.0",
                        ),
                )

            val report = fixture.useCase.run(ORG, fixture.app.id)

            report.deliveries.single().sent shouldBe true
            val digest =
                fixture.slack.analyses
                    .single()
                    .second
            digest.appName shouldBe "IsleGrow"
            digest.issues.single().key shouldBe Topic.CRASH.key
            digest.quoteLine().shouldNotBeNull() shouldContain "pořád to padá"
            digest.consoleUrl.shouldNotBeNull() shouldContain "/matee/recenze?app="
            digest.consoleUrl.shouldNotBeNull() shouldContain "topic=crash"
        }

        test("druhý běh za tentýž týden zprávu nepošle podruhé") {
            val fixture = WeeklyFixture()

            fixture.useCase.run(ORG, fixture.app.id)
            val second = fixture.useCase.run(ORG, fixture.app.id)

            fixture.slack.analyses shouldHaveSize 1
            second.deliveries.single().alreadySent shouldBe true
        }

        test("pod prahem recenzí rozbor odejde, ale místo trendů řekne, že je dat málo") {
            val fixture = WeeklyFixture(current = period(reviews = 4, topicCount = 4))

            fixture.useCase.run(ORG, fixture.app.id)

            val digest =
                fixture.slack.analyses
                    .single()
                    .second
            digest.aggregates.tooFewReviews shouldBe true
            digest.tooFewLine() shouldContain "4"
        }

        test("kanál bez rozborů zprávu nedostane") {
            val fixture = WeeklyFixture(deliverAnalyses = false)

            val report = fixture.useCase.run(ORG, fixture.app.id)

            report.skipped shouldBe AnalysisSkipReason.NO_CHANNEL
            fixture.slack.analyses shouldHaveSize 0
        }

        test("zadané období přebije výpočet minulého týdne") {
            val fixture = WeeklyFixture()

            val report = fixture.useCase.run(ORG, fixture.app.id, LocalDate(2026, 8, 24))

            report.aggregates.shouldNotBeNull().periodStart shouldBe LocalDate(2026, 8, 24)
            report.aggregates.shouldNotBeNull().periodEnd shouldBe LocalDate(2026, 8, 30)
        }
    })
