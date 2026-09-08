package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AnalysisCadence
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

private class AnalysisFixture(
    current: AnalysisPeriod = period(20),
    previous: AnalysisPeriod = AnalysisPeriod.EMPTY,
    quote: TopicQuote? = null,
    deliverAnalyses: Boolean = true,
    cadence: AnalysisCadence = AnalysisCadence.WEEKLY,
    lastSentEnd: LocalDate? = null,
    thresholds: AnalysisThresholds = AnalysisThresholds(),
    minReviewsOverride: Int? = null,
) {
    val apps = FakeAppRepository()
    val organizations = FakeOrganizationRepository()
    val channelRepository = FakeChannelRepository()
    val insights = FakeReviewInsightRepository()
    val slack = FakeNotificationChannel()
    val app = apps.put(Ingest.app(ORG).copy(analysisCadence = cadence, analysisMinReviews = minReviewsOverride))

    init {
        organizations.put(Organization(id = ORG, name = "Matee", slug = "matee", createdAt = Delivery.now))
        channelRepository.put(Delivery.channel(ORG, app.id).copy(deliverAnalyses = deliverAnalyses))
        // Bez jediného výkladu by se rozbor neposílal; tenhle příznak reprezentuje „něco už máme".
        insights.awaiting()
    }

    val useCase =
        ScheduledAnalysisUseCase(
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
            digests = FakeAnalysisDigestRepository(lastSentEnd),
            secrets = secretResolver("xoxb-token"),
            links = ConsoleLinks("https://console.test"),
            notificationChannels = listOf(slack),
            policy = AnalysisPolicy.fixed(thresholds),
            clock = fixedClock(MONDAY_MORNING),
        )
}

/**
 * Pravidelný rozbor. Zajímavé jsou tři věci: že se počítá **minulé celé období**, že se do
 * kanálu nepošle dvakrát, a že se termín pod prahem recenzí **přeskočí, ne odbyde** — období
 * zůstane otevřené a přičte se k příštímu běhu.
 */
class ScheduledAnalysisUseCaseTest :
    FunSpec({
        test("období je minulé pondělí až neděle v zóně aplikace") {
            val fixture = AnalysisFixture()

            val report = fixture.useCase.run(ORG, fixture.app.id)

            val aggregates = report.aggregates.shouldNotBeNull()
            aggregates.periodStart shouldBe LocalDate(2026, 8, 31)
            aggregates.periodEnd shouldBe LocalDate(2026, 9, 6)
        }

        test("rozbor odejde do kanálu s odkazem na téma, které otevírá") {
            val fixture =
                AnalysisFixture(
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
            val fixture = AnalysisFixture()

            fixture.useCase.run(ORG, fixture.app.id)
            val second = fixture.useCase.run(ORG, fixture.app.id)

            fixture.slack.analyses shouldHaveSize 1
            second.deliveries.single().alreadySent shouldBe true
        }

        test("pod prahem se rozbor nepošle a období zůstane otevřené") {
            val fixture = AnalysisFixture(current = period(reviews = 4, topicCount = 4))

            val report = fixture.useCase.run(ORG, fixture.app.id)

            report.skipped shouldBe AnalysisSkipReason.NOT_ENOUGH_REVIEWS
            fixture.slack.analyses shouldHaveSize 0
            // Čísla se i tak spočítají — konzole i CLI mají co ukázat.
            report.aggregates.shouldNotBeNull().reviews shouldBe 4
        }

        test("--force pošle i pod prahem, kvůli první zprávě při onboardingu") {
            val fixture = AnalysisFixture(current = period(reviews = 4, topicCount = 4))

            val report = fixture.useCase.run(ORG, fixture.app.id, force = true)

            report.deliveries.single().sent shouldBe true
        }

        /**
         * Přeskočený termín se nesmí ztratit: kdyby další rozbor počítal jen svůj týden,
         * recenze z přeskočeného období by v žádném rozboru nikdy nebyly.
         */
        test("po přeskočeném termínu navazuje období na poslední odeslaný rozbor") {
            val fixture = AnalysisFixture(lastSentEnd = LocalDate(2026, 8, 16))

            val report = fixture.useCase.run(ORG, fixture.app.id)

            val aggregates = report.aggregates.shouldNotBeNull()
            aggregates.periodStart shouldBe LocalDate(2026, 8, 17)
            aggregates.periodEnd shouldBe LocalDate(2026, 9, 6)
        }

        test("odeslaný rozbor za minulý týden období neprodlužuje") {
            val fixture = AnalysisFixture(lastSentEnd = LocalDate(2026, 8, 30))

            val report = fixture.useCase.run(ORG, fixture.app.id)

            report.aggregates.shouldNotBeNull().periodStart shouldBe LocalDate(2026, 8, 31)
        }

        test("měsíční kadence počítá celý minulý měsíc") {
            val fixture = AnalysisFixture(cadence = AnalysisCadence.MONTHLY)

            val report = fixture.useCase.run(ORG, fixture.app.id)

            val aggregates = report.aggregates.shouldNotBeNull()
            aggregates.periodStart shouldBe LocalDate(2026, 8, 1)
            aggregates.periodEnd shouldBe LocalDate(2026, 8, 31)
        }

        test("práh aplikace přebíjí platformní hodnotu") {
            val platformSaysTwenty = AnalysisThresholds(minReviews = 20)
            val fixture =
                AnalysisFixture(
                    current = period(reviews = 12, topicCount = 12),
                    thresholds = platformSaysTwenty,
                    minReviewsOverride = 10,
                )

            fixture.useCase
                .run(ORG, fixture.app.id)
                .deliveries
                .single()
                .sent shouldBe true
        }

        test("téma pod platformním prahem zmínek se do rozboru nedostane") {
            val fixture =
                AnalysisFixture(
                    current = period(reviews = 20, topicCount = 2),
                    thresholds = AnalysisThresholds(minTopicCount = 3),
                )

            fixture.useCase.run(ORG, fixture.app.id)

            fixture.slack.analyses
                .single()
                .second.issues
                .shouldHaveSize(0)
        }

        test("kanál bez rozborů zprávu nedostane") {
            val fixture = AnalysisFixture(deliverAnalyses = false)

            val report = fixture.useCase.run(ORG, fixture.app.id)

            report.skipped shouldBe AnalysisSkipReason.NO_CHANNEL
            fixture.slack.analyses shouldHaveSize 0
        }

        test("zadané období přebije výpočet minulého týdne") {
            val fixture = AnalysisFixture()

            val report = fixture.useCase.run(ORG, fixture.app.id, LocalDate(2026, 8, 24))

            report.aggregates.shouldNotBeNull().periodStart shouldBe LocalDate(2026, 8, 24)
            report.aggregates.shouldNotBeNull().periodEnd shouldBe LocalDate(2026, 8, 30)
        }
    })
