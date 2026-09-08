package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.LanguageAggregate
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TerritoryAggregate
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.port.VersionWindow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-09-08T09:00:00Z")

/** Pevné hodiny: období na stránce se počítá ode dneška a jinak by test žil týden. */
private val CLOCK =
    object : Clock {
        override fun now(): Instant = NOW
    }

private fun day(
    date: LocalDate,
    positive: Int,
    negative: Int,
) = DayCounts(
    date = date,
    reviews = positive + negative,
    starSum = positive * 5 + negative,
    sentiments = mapOf(OverallSentiment.POSITIVE to positive, OverallSentiment.NEGATIVE to negative),
)

/**
 * Rozbor pro konzoli je z týchž agregátů jako zpráva do kanálu — testuje se tu jen to,
 * co k nim stránka přidává: týdenní řada nálady, trend tématu a srovnání verzí.
 */
class AnalysisInsightsTest :
    FunSpec({
        val orgId = OrganizationId(Uuid.random())

        fun insights(
            current: AnalysisPeriod,
            previous: AnalysisPeriod = AnalysisPeriod.EMPTY,
            days: List<DayCounts> = emptyList(),
            dayTopics: List<DayTopicCount> = emptyList(),
            markets: List<TerritoryAggregate> = emptyList(),
            tongues: List<LanguageAggregate> = emptyList(),
            versions: List<VersionWindow> = emptyList(),
        ): Pair<AnalysisInsights, cz.matee.appreviewzz.core.model.App> {
            val apps = FakeAppRepository()
            val app = apps.put(Ingest.app(orgId))
            return AnalysisInsights(
                apps = apps,
                aggregates =
                    FakeAnalysisAggregateRepository(
                        current = current,
                        previous = previous,
                        replies = ReplyStats(total = current.reviews, replied = 2, medianHours = 4.0, uplifted = 1),
                        days = days,
                        dayTopics = dayTopics,
                        markets = markets,
                        tongues = tongues,
                        versions = versions,
                    ),
                insights = FakeReviewInsightRepository(),
                appTopics = FakeAppTopicRepository(),
                clock = CLOCK,
            ) to app
        }

        test("denní body se posbírají do týdnů začínajících pondělím") {
            val (useCase, app) =
                insights(
                    current = AnalysisPeriod.EMPTY.copy(reviews = 6),
                    days =
                        listOf(
                            // Pondělí 31. 8. a středa 2. 9. patří do jednoho týdne…
                            day(LocalDate(2026, 8, 31), positive = 2, negative = 1),
                            day(LocalDate(2026, 9, 2), positive = 1, negative = 0),
                            // …pondělí 7. 9. už do dalšího.
                            day(LocalDate(2026, 9, 7), positive = 1, negative = 1),
                        ),
                )

            val weekly = useCase.overview(orgId, app.id, days = 30).weekly

            weekly shouldHaveSize 2
            weekly[0].weekStart shouldBe LocalDate(2026, 8, 31)
            weekly[0].reviews shouldBe 4
            weekly[1].weekStart shouldBe LocalDate(2026, 9, 7)
            weekly[1].reviews shouldBe 2
        }

        test("trend tématu má vždy osm bodů a novější zmínky sedí vpravo") {
            val (useCase, app) =
                insights(
                    current =
                        AnalysisPeriod.EMPTY.copy(
                            reviews = 10,
                            topics = listOf(TopicAggregate(Topic.CRASH.key, count = 5, negative = 5, positive = 0, starSum = 10)),
                        ),
                    dayTopics =
                        listOf(
                            DayTopicCount(LocalDate(2026, 9, 7), Topic.CRASH.key, 3),
                            DayTopicCount(LocalDate(2026, 9, 8), Topic.CRASH.key, 2),
                        ),
                )

            val trend =
                useCase
                    .overview(orgId, app.id, days = 8)
                    .topics
                    .single()
                    .trend

            trend shouldHaveSize AnalysisInsights.TREND_POINTS
            trend.sum() shouldBe 5
            // Období je 1.–8. 9., osm dní na osm bodů: poslední dva dny jsou poslední dva body.
            trend.last() shouldBe 2
        }

        test("rozbor za konkrétní období se nedrží dneška") {
            // Report se jmenuje podle měsíce; kdyby se čísla počítala klouzavým oknem do
            // dneška, byl by „srpen" spočítaný do osmého září a nikdo by si toho nevšiml.
            val (useCase, app) = insights(current = AnalysisPeriod.EMPTY.copy(reviews = 40))

            val overview = useCase.overview(orgId, app.id, LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))

            overview.periodStart shouldBe LocalDate(2026, 8, 1)
            overview.periodEnd shouldBe LocalDate(2026, 8, 31)
        }

        test("trhy a jazyky dostanou podíl nespokojených, ne holý počet") {
            val (useCase, app) =
                insights(
                    current = AnalysisPeriod.EMPTY.copy(reviews = 10),
                    markets = listOf(TerritoryAggregate("DE", reviews = 4, negative = 3, starSum = 8)),
                    tongues = listOf(LanguageAggregate("de", reviews = 4, negative = 3)),
                )

            val overview = useCase.overview(orgId, app.id)

            overview.territories.single().negativeShare shouldBe (0.75 plusOrMinus 0.001)
            overview.territories.single().avgStars shouldBe (2.0 plusOrMinus 0.001)
            overview.languages.single().negativeShare shouldBe (0.75 plusOrMinus 0.001)
        }

        test("dopad verze pozná téma, které před vydáním nebylo") {
            val firstSeen = Instant.parse("2026-09-01T00:00:00Z")
            val apps = FakeAppRepository()
            val app = apps.put(Ingest.app(orgId))
            // Fake vrací „current" pro nejnovější dotazované období, „previous" pro starší —
            // což přesně odpovídá dvojici (po vydání, před vydáním).
            val useCase =
                AnalysisInsights(
                    apps = apps,
                    aggregates =
                        FakeAnalysisAggregateRepository(
                            current =
                                AnalysisPeriod.EMPTY.copy(
                                    reviews = 10,
                                    sentiments = mapOf(OverallSentiment.NEGATIVE to 8, OverallSentiment.POSITIVE to 2),
                                    topics = listOf(TopicAggregate(Topic.CRASH.key, 6, negative = 6, positive = 0, starSum = 6)),
                                ),
                            previous =
                                AnalysisPeriod.EMPTY.copy(
                                    reviews = 10,
                                    sentiments = mapOf(OverallSentiment.NEGATIVE to 2, OverallSentiment.POSITIVE to 8),
                                    topics = listOf(TopicAggregate(Topic.PRICING.key, 4, negative = 4, positive = 0, starSum = 12)),
                                ),
                            versions = listOf(VersionWindow("3.2.0", Platform.ANDROID, firstSeen, reviews = 10)),
                        ),
                    insights = FakeReviewInsightRepository(),
                    appTopics = FakeAppTopicRepository(),
                    clock = CLOCK,
                )

            val impact = useCase.versions(orgId, app.id).single()

            impact.version shouldBe "3.2.0"
            impact.newTopics.single().key shouldBe Topic.CRASH.key
            impact.goneTopics.single().key shouldBe Topic.PRICING.key
            impact.negativeDelta shouldBe (0.6 plusOrMinus 0.001)
        }
    })
