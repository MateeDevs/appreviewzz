package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.AnalysisAlertId
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisAlertRepository
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.NewAnalysisAlert
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.port.VersionAggregate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ORG = OrganizationId(Uuid.random())

/** 8. 9. 2026 v 15:00 pražského času — „dnešek" v zóně aplikace je 8. 9. */
private val AFTERNOON = Instant.parse("2026-09-08T13:00:00Z")
private val TODAY = LocalDate(2026, 9, 8)

/** Zápis výkyvů v paměti se stejnou unikátností jako databáze: (appka, druh, téma, den). */
private class FakeAnalysisAlertRepository : AnalysisAlertRepository {
    private val taken = mutableSetOf<List<Any?>>()
    val written = mutableListOf<AnalysisAlert>()

    override fun insertIfAbsent(
        orgId: OrganizationId,
        alert: NewAnalysisAlert,
        createdAt: Instant,
    ): AnalysisAlert? {
        if (!taken.add(listOf(alert.appId, alert.kind, alert.topicKey, alert.windowDate))) return null
        return AnalysisAlert(
            id = AnalysisAlertId(Uuid.random()),
            orgId = orgId,
            appId = alert.appId,
            kind = alert.kind,
            topicKey = alert.topicKey,
            windowDate = alert.windowDate,
            observed = alert.observed,
            expected = alert.expected,
            zScore = alert.zScore,
            createdAt = createdAt,
        ).also { written += it }
    }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        since: LocalDate,
        limit: Int,
    ): List<AnalysisAlert> = written.filter { it.windowDate >= since }.take(limit)
}

private fun quietDays(negativePerDay: Int) =
    (1..SpikeDetection.BASELINE_DAYS).map { offset ->
        DayCounts(
            date = TODAY.minus(offset, DateTimeUnit.DAY),
            reviews = negativePerDay,
            starSum = negativePerDay,
            sentiments = mapOf(OverallSentiment.NEGATIVE to negativePerDay),
        )
    }

/**
 * Alert na výkyv. Zajímavé je, že se **neposílá dvakrát za týž den** (dotagování jede
 * po dávkách) a že zpráva nese baseline i stopu — téma a verzi.
 */
class SpikeAlertUseCaseTest :
    FunSpec({
        fun fixture(
            days: List<DayCounts>,
            dayTopics: List<DayTopicCount> = emptyList(),
        ): Triple<SpikeAlertUseCase, FakeNotificationChannel, AppId> {
            val apps = FakeAppRepository()
            val app = apps.put(Ingest.app(ORG))
            val organizations = FakeOrganizationRepository()
            organizations.put(Organization(id = ORG, name = "Matee", slug = "matee", createdAt = Delivery.now))
            val channelRepository = FakeChannelRepository()
            channelRepository.put(Delivery.channel(ORG, app.id))
            val slack = FakeNotificationChannel()
            val useCase =
                SpikeAlertUseCase(
                    apps = apps,
                    organizations = organizations,
                    channels = channelRepository,
                    aggregates =
                        FakeAnalysisAggregateRepository(
                            current =
                                AnalysisPeriod.EMPTY.copy(
                                    versions = listOf(VersionAggregate("3.2.0", Platform.ANDROID, count = 8, starSum = 8, negative = 8)),
                                ),
                            quote =
                                TopicQuote(
                                    reviewId = ReviewId(Uuid.random()),
                                    quote = "po aktualizaci to padá",
                                    starRating = 1,
                                    platform = Platform.ANDROID,
                                    appVersion = "3.2.0",
                                ),
                            days = days,
                            dayTopics = dayTopics,
                        ),
                    alerts = FakeAnalysisAlertRepository(),
                    appTopics = FakeAppTopicRepository(),
                    secrets = secretResolver("xoxb-token"),
                    links = ConsoleLinks("https://console.test"),
                    notificationChannels = listOf(slack),
                    clock = fixedClock(AFTERNOON),
                )
            return Triple(useCase, slack, app.id)
        }

        test("skok v záporných recenzích pošle zprávu s baseline, tématem i verzí") {
            val today =
                DayCounts(TODAY, reviews = 11, starSum = 11, sentiments = mapOf(OverallSentiment.NEGATIVE to 11))
            val (useCase, slack, appId) =
                fixture(
                    days = quietDays(1) + today,
                    dayTopics = listOf(DayTopicCount(TODAY, Topic.CRASH.key, 9)),
                )

            val report = useCase.run(ORG, appId)

            report.alerts.first().kind shouldBe AlertKind.NEGATIVE_SPIKE
            val message = slack.alerts.first().second
            message.headline() shouldContain "11"
            message.topTopicLine().shouldNotBeNull() shouldContain "Pády"
            message.topVersionLine().shouldNotBeNull() shouldContain "3.2.0"
            message.quoteLines().first() shouldContain "po aktualizaci to padá"
        }

        test("druhý běh téhož dne zprávu nepošle podruhé") {
            val today =
                DayCounts(TODAY, reviews = 11, starSum = 11, sentiments = mapOf(OverallSentiment.NEGATIVE to 11))
            val (useCase, slack, appId) = fixture(days = quietDays(1) + today)

            useCase.run(ORG, appId)
            val second = useCase.run(ORG, appId)

            second.alerts.shouldBeEmpty()
            slack.alerts shouldHaveSize 1
        }

        test("záporný výkyv i výkyv tématu naráz pošlou jednu zprávu, ne dvě") {
            // Když se den vymkne, vymkne se obojí naráz — a záporný výkyv už v textu nese
            // nejčastější téma. Dvě zprávy o téže věci jsou pro tým šum, ne informace.
            val today =
                DayCounts(TODAY, reviews = 11, starSum = 11, sentiments = mapOf(OverallSentiment.NEGATIVE to 11))
            val quietTopics =
                (1..SpikeDetection.BASELINE_DAYS).map { DayTopicCount(TODAY.minus(it, DateTimeUnit.DAY), Topic.CRASH.key, 1) }
            val (useCase, slack, appId) =
                fixture(days = quietDays(1) + today, dayTopics = quietTopics + DayTopicCount(TODAY, Topic.CRASH.key, 9))

            val report = useCase.run(ORG, appId)

            // Zaznamenají se oba: v konzoli je to historie výkyvů, ne fronta zpráv.
            report.alerts.map { it.kind } shouldBe listOf(AlertKind.NEGATIVE_SPIKE, AlertKind.TOPIC_SPIKE)
            slack.alerts shouldHaveSize 1
            slack.alerts
                .single()
                .second.alert.kind shouldBe AlertKind.NEGATIVE_SPIKE
        }

        test("klidný den žádnou zprávu nevyvolá") {
            val today =
                DayCounts(TODAY, reviews = 2, starSum = 2, sentiments = mapOf(OverallSentiment.NEGATIVE to 2))
            val (useCase, slack, appId) = fixture(days = quietDays(1) + today)

            useCase.run(ORG, appId)

            slack.alerts.shouldBeEmpty()
        }
    })
