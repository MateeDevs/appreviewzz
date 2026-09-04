package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import cz.matee.appreviewzz.core.usecase.AnalyzeReviewsUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAppTopicRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewInsightRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Dlouhý backfill se nesmí odbýt jedním během — úloha zpracuje dávku a přeplánuje se. Bez toho
 * by jedna appka s desítkami tisíc recenzí držela vlákno plánovače hodiny.
 */
class AnalysisJobsTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val insights = ExposedReviewInsightRepository(exposed)
        val appTopics = ExposedAppTopicRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)

        val batches = CopyOnWriteArrayList<AnalysisRequest>()

        val provider =
            ReviewAnalysisProvider { request ->
                batches += request
                AnalysisResult.Analyzed(
                    request.items.map {
                        ReviewAnalysis(
                            id = it.id,
                            sentiment = OverallSentiment.NEGATIVE,
                            type = ReviewType.BUG,
                            urgency = Urgency.MEDIUM,
                            language = "cs",
                            topics = listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
                            translation = null,
                        )
                    },
                    "test-model",
                )
            }

        val analysisJobs =
            AnalysisJobs(
                analyze =
                    AnalyzeReviewsUseCase(
                        apps = apps,
                        reviews = reviews,
                        insights = insights,
                        appTopics = appTopics,
                        provider = provider,
                    ),
                failedJobs = failedJobs,
                // Dvě recenze na běh: třetí se musí doplnit až přeplánovanou úlohou.
                batchLimit = 2,
            )

        fun observedReview(storeReviewId: String) =
            ObservedReview(
                platform = Platform.ANDROID,
                storeReviewId = storeReviewId,
                authorName = "Jana N.",
                starRating = 2,
                title = null,
                body = "Po updatu se nedostanu dál.",
                locale = "cs",
                territory = "CZ",
                appVersion = "3.2.1",
                device = "Pixel 8",
                submittedAt = Instant.parse("2026-08-19T09:30:00Z"),
                storeUpdatedAt = null,
                developerResponseBody = null,
                developerResponseAt = null,
            )

        beforeTest {
            TestDatabase.reset()
            batches.clear()
        }

        test("dávka doběhne a úloha se přeplánuje, dokud zbývají recenze bez výkladu") {
            val org = organizations.create("Matee", "matee-${Uuid.random()}".take(30))
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            listOf("gp:1", "gp:2", "gp:3").forEach {
                reviews.upsert(org.id, app.id, observedReview(it), Instant.parse("2026-08-19T10:00:00Z"), ReviewState.NEW)
            }

            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs =
                        IngestJobs(
                            // Ingest tu jen musí existovat: sweep je vypnutý dlouhým intervalem
                            // a žádná appka nemá klíč, takže do storu se nikdy nesáhne.
                            ingest =
                                IngestReviewsUseCase(
                                    apps = apps,
                                    credentials = ExposedCredentialRepository(exposed),
                                    reviews = reviews,
                                    secrets = { _, _ -> SecretPayload("nepoužito") },
                                    audit = ExposedAuditLogRepository(exposed),
                                    sources = emptyList(),
                                ),
                            apps = apps,
                            failedJobs = failedJobs,
                            sweepInterval = Duration.ofHours(1),
                        ),
                    analysisJobs = analysisJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                analysisJobs.schedule(scheduler, org.id, app.id)
                eventually(60.seconds) {
                    insights.coverage(org.id, app.id, Topic.TAXONOMY_VERSION).missing shouldBe 0
                }
                // Tři recenze, dvě na běh — musela proběhnout aspoň dvě volání do AI.
                batches.size shouldBeGreaterThanOrEqual 2
            } finally {
                scheduler.stop()
            }
        }
    })
