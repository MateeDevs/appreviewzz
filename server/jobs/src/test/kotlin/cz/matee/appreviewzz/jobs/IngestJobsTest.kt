package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AppSettings
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Plánovač nad skutečným Postgresem: ověřuje, že se ingest rozjede sám podle stavu databáze
 * a že vypnutí appky frontu uklidí. Zároveň je to jediný test schématu `scheduled_tasks` —
 * kdyby migrace neseděla s knihovnou, nic z tohohle by se nespustilo.
 */
class IngestJobsTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)

        var storeResponse: () -> List<ObservedReview> = { emptyList() }
        val source =
            object : ReviewSource {
                override val platform = Platform.ANDROID

                override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> = storeResponse()

                override suspend fun validate(context: StoreContext): ValidationOutcome = ValidationOutcome(true)
            }

        val jobs =
            IngestJobs(
                ingest =
                    IngestReviewsUseCase(
                        apps = apps,
                        credentials = credentials,
                        reviews = reviews,
                        secrets = SecretResolver { _, _ -> SecretPayload("service-account-json") },
                        audit = ExposedAuditLogRepository(exposed),
                        sources = listOf(source),
                    ),
                apps = apps,
                failedJobs = failedJobs,
                sweepInterval = Duration.ofSeconds(1),
            )

        fun observedReview(storeReviewId: String) =
            ObservedReview(
                platform = Platform.ANDROID,
                storeReviewId = storeReviewId,
                authorName = "Jana N.",
                starRating = 4,
                title = null,
                body = "Funguje dobře.",
                locale = "cs",
                territory = "CZ",
                appVersion = "3.2.1",
                device = "Pixel 8",
                submittedAt = Instant.parse("2026-08-19T09:30:00Z"),
                storeUpdatedAt = null,
                developerResponseBody = null,
                developerResponseAt = null,
            )

        fun setUpApp(): App {
            val org = organizations.create("Matee", "matee-${Uuid.random()}".take(30))
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Instant.parse("2026-08-19T08:00:00Z"))
            val credential =
                credentials.create(
                    org.id,
                    NewCredential(
                        id = CredentialId(Uuid.random()),
                        type = CredentialType.GP_SERVICE_ACCOUNT,
                        label = "IsleGrow GP",
                        dataKeyId = key.id,
                        ciphertext = byteArrayOf(1),
                        fingerprint = "sha256:abcd",
                    ),
                )
            credentials.attachToApp(org.id, app.id, credential.id, CredentialPurpose.REVIEWS)
            return app
        }

        fun disable(app: App) {
            apps.updateSettings(
                app.orgId,
                app.id,
                AppSettings(
                    name = app.name,
                    locale = app.locale,
                    timezone = app.timezone,
                    notifyFrom = app.notifyFrom,
                    aiInstructions = app.aiInstructions,
                    ingestIntervalMinutes = app.ingestIntervalMinutes,
                    dailyDigestAt = app.dailyDigestAt,
                    enabled = false,
                ),
            )
        }

        beforeTest {
            TestDatabase.reset()
            storeResponse = { emptyList() }
        }

        test("sweep rozjede ingest nové appky a po vypnutí ji odplánuje") {
            val app = setUpApp()
            storeResponse = { listOf(observedReview("gp:1")) }
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = jobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                // Nikdo ingest neplánoval ručně — sweep si appku najde v databázi sám.
                eventually(30.seconds) {
                    reviews.listByApp(app.orgId, app.id) shouldHaveSize 1
                }
                scheduler.getScheduledExecutionsForTask(IngestJobs.INGEST_TASK, IngestJobData::class.java) shouldHaveSize 1

                disable(app)

                eventually(30.seconds) {
                    scheduler
                        .getScheduledExecutionsForTask(IngestJobs.INGEST_TASK, IngestJobData::class.java)
                        .shouldHaveSize(0)
                }
            } finally {
                scheduler.stop()
            }
        }

        test("trvalá chyba storu skončí v DLQ, ale appka zůstává naplánovaná") {
            val app = setUpApp()
            storeResponse = { throw StoreConnectorException(StoreErrorKind.AUTH, "Klíč nemá práva k appce") }
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = jobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                eventually(30.seconds) {
                    val open = failedJobs.listOpenByOrg(app.orgId)
                    open shouldHaveSize 1
                    open.single().taskInstance shouldBe app.id.toString()
                }
                // Retry by jen tloukl do storu, ale ingest se nesmí zastavit natrvalo.
                scheduler.getScheduledExecutionsForTask(IngestJobs.INGEST_TASK, IngestJobData::class.java) shouldHaveSize 1
            } finally {
                scheduler.stop()
            }
        }

        test("payload úlohy se ukládá jako čitelný JSON") {
            val data = IngestJobData(orgId = OrganizationId(Uuid.random()).toString(), appId = "app-1", intervalMinutes = 30)

            val bytes = JsonTaskSerializer.serialize(data)

            bytes.toString(Charsets.UTF_8) shouldBe
                """{"orgId":"${data.orgId}","appId":"app-1","intervalMinutes":30}"""
            JsonTaskSerializer.deserialize(IngestJobData::class.java, bytes) shouldBe data
        }
    })
