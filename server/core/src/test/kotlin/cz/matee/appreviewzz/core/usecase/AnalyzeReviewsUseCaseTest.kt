package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.uuid.Uuid

private const val MODEL = "gemini-2.5-flash-lite"

class AnalyzeReviewsUseCaseTest :
    FunSpec({
        val orgId =
            cz.matee.appreviewzz.core.model
                .OrganizationId(Uuid.random())

        test("výklad recenze se uloží i s citátem, který v recenzi doopravdy je") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(
                        ReviewAnalysis(
                            id = review.id.toString(),
                            sentiment = OverallSentiment.NEGATIVE,
                            type = ReviewType.BUG,
                            urgency = Urgency.HIGH,
                            language = "cs",
                            topics = listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "nedostanu dál")),
                            translation = null,
                        ),
                    ),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val outcome = useCase.ensureAnalyzed(orgId, review.id)

            val insight = outcome.shouldBeInstanceOf<AnalysisOutcome.Analyzed>().insight
            insight.model shouldBe MODEL
            insight.taxonomyVersion shouldBe Topic.TAXONOMY_VERSION
            insight.topics.single().quote shouldBe "nedostanu dál"
        }

        test("citát, který v recenzi není, se zahodí — nikdy se nevymýšlí") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(
                        analysis(
                            review.id.toString(),
                            listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "tohle tam vůbec nestojí")),
                        ),
                    ),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val insight = useCase.ensureAnalyzed(orgId, review.id).insightOrNull.shouldNotBeNull()

            insight.topics.single().quote shouldBe null
        }

        test("citát se ověřuje bez ohledu na diakritiku a mezery") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(
                        analysis(
                            review.id.toString(),
                            listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "Po  updatu se NEDOSTANU dal")),
                        ),
                    ),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            useCase
                .ensureAnalyzed(orgId, review.id)
                .insightOrNull
                .shouldNotBeNull()
                .topics
                .single()
                .quote shouldBe
                "Po  updatu se NEDOSTANU dal"
        }

        test("neznámé téma se zahodí, a když nezbude žádné, je z toho other") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(analysis(review.id.toString(), listOf(TopicMention("vymyslene_tema", TopicSentiment.NEGATIVE, null)))),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val insight = useCase.ensureAnalyzed(orgId, review.id).insightOrNull.shouldNotBeNull()

            insight.topics.single().key shouldBe Topic.OTHER.key
        }

        test("other přilepené ke konkrétnímu tématu se odstraní") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(
                        analysis(
                            review.id.toString(),
                            listOf(
                                TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null),
                                TopicMention(Topic.OTHER.key, TopicSentiment.NEUTRAL, null),
                            ),
                        ),
                    ),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            useCase
                .ensureAnalyzed(orgId, review.id)
                .insightOrNull
                .shouldNotBeNull()
                .topics
                .map { it.key } shouldBe
                listOf(Topic.CRASH.key)
        }

        test("vlastní téma aplikace projde jako platný klíč") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val appTopics = FakeAppTopicRepository()
            val app = apps.put(Ingest.app(orgId))
            val custom = appTopics.put(orgId, app.id, "Garmin", "Problems syncing with Garmin watches.")
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(
                AnalysisResult.Analyzed(
                    listOf(analysis(review.id.toString(), listOf(TopicMention(custom.key, TopicSentiment.NEGATIVE, null)))),
                    MODEL,
                ),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, appTopics, provider)

            useCase
                .ensureAnalyzed(orgId, review.id)
                .insightOrNull
                .shouldNotBeNull()
                .topics
                .single()
                .key shouldBe custom.key
            provider.requests
                .single()
                .customTopics
                .single()
                .key shouldBe custom.key
        }

        test("recenze bez textu se do AI vůbec neposílá, sentiment se odvodí z hvězd") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id).copy(title = null, body = null, starRating = 5))
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val insight = useCase.ensureAnalyzed(orgId, review.id).insightOrNull.shouldNotBeNull()

            insight.model shouldBe AnalyzeReviewsUseCase.RULES_MODEL
            insight.sentiment shouldBe OverallSentiment.POSITIVE
            insight.type shouldBe ReviewType.PRAISE
            insight.topics.single().key shouldBe Topic.PRAISE.key
            provider.requests.shouldBeEmpty()
        }

        test("hotový a aktuální výklad se nepočítá znovu") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(AnalysisResult.Analyzed(listOf(analysis(review.id.toString())), MODEL))
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            useCase.ensureAnalyzed(orgId, review.id)
            val second = useCase.ensureAnalyzed(orgId, review.id)

            second.shouldBeInstanceOf<AnalysisOutcome.AlreadyAnalyzed>()
            provider.requests shouldHaveSize 1
        }

        test("selhání AI nic nezapíše a report to řekne") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            insights.awaiting(review)
            provider.answer(AnalysisResult.Failed("Gemini vrátilo 503"))
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val report = useCase.analyzeMissing(orgId, app.id)

            report.failed shouldBe 1
            report.analyzed shouldBe 0
            report.error shouldBe "Gemini vrátilo 503"
            insights.saved.shouldBeEmpty()
        }

        test("editovaná recenze se přeanalyzuje, protože se rozešel otisk") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val review = reviews.put(Delivery.review(orgId, app.id))
            provider.answer(AnalysisResult.Analyzed(listOf(analysis(review.id.toString())), MODEL))
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)
            useCase.ensureAnalyzed(orgId, review.id)

            val edited = reviews.replace(review.copy(contentHash = "hash-2", body = "Po updatu to padá."))
            provider.answer(AnalysisResult.Analyzed(listOf(analysis(edited.id.toString())), MODEL))
            val outcome = useCase.ensureAnalyzed(orgId, review.id)

            outcome.shouldBeInstanceOf<AnalysisOutcome.Analyzed>().insight.contentHash shouldBe "hash-2"
            provider.requests shouldHaveSize 2
        }

        test("recenze, kterou model v dávce vynechal, se počítá jako vynechaná") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val first = reviews.put(Delivery.review(orgId, app.id))
            val second = reviews.put(Delivery.review(orgId, app.id))
            insights.awaiting(first, second)
            provider.answer(AnalysisResult.Analyzed(listOf(analysis(first.id.toString())), MODEL))
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val report = useCase.analyzeMissing(orgId, app.id)

            report.analyzed shouldBe 1
            report.skipped shouldBe 1
            insights.saved.map { it.reviewId } shouldBe listOf(first.id)
        }

        test("potlačené a odložené recenze se analyzují taky — v podílech chybět nesmí") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            val suppressed = reviews.put(Delivery.review(orgId, app.id, state = ReviewState.SUPPRESSED))
            val ignored = reviews.put(Delivery.review(orgId, app.id, state = ReviewState.IGNORED))
            insights.awaiting(suppressed, ignored)
            provider.answer(
                AnalysisResult.Analyzed(listOf(analysis(suppressed.id.toString()), analysis(ignored.id.toString())), MODEL),
            )
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            useCase.analyzeMissing(orgId, app.id).analyzed shouldBe 2
        }

        test("bez nastaveného providera se nic nezapíše a report to řekne") {
            val apps = FakeAppRepository()
            val reviews = FakeReviewRepository()
            val insights = FakeReviewInsightRepository()
            val provider = FakeAnalysisProvider()
            val app = apps.put(Ingest.app(orgId))
            insights.awaiting(reviews.put(Delivery.review(orgId, app.id)))
            provider.answer(AnalysisResult.Unavailable)
            val useCase = AnalyzeReviewsUseCase(apps, reviews, insights, FakeAppTopicRepository(), provider)

            val report = useCase.analyzeMissing(orgId, app.id)

            report.unavailable shouldBe true
            report.analyzed shouldBe 0
            insights.saved.shouldBeEmpty()
        }
    })

private fun analysis(
    id: String,
    topics: List<TopicMention> = listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
) = ReviewAnalysis(
    id = id,
    sentiment = OverallSentiment.NEGATIVE,
    type = ReviewType.BUG,
    urgency = Urgency.MEDIUM,
    language = "cs",
    topics = topics,
    translation = null,
)
