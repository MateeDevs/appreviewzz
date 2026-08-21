package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AppSettings
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.usecase.DailyRatingsUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingSnapshotRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingsDigestRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalTime
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Plánovač denních přehledů nad skutečným Postgresem. Zajímá nás hlavně sweep: appka se má
 * rozjet bez restartu a změna času přehledu v consoli se má propsat do fronty.
 */
class RatingsJobsTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val snapshots = ExposedRatingSnapshotRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)

        val source =
            object : RatingsSource {
                override val platform = Platform.ANDROID
                override val priority = 100

                override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> =
                    listOf(
                        ObservedRatings(
                            platform = Platform.ANDROID,
                            territory = ObservedRatings.GLOBAL,
                            average = 4.31,
                            totalCount = 1200,
                            histogram = emptyMap(),
                            source = RatingSource.GP_SCRAPE,
                        ),
                    )
            }

        val idleIngest =
            IngestReviewsUseCase(
                apps = apps,
                credentials = ExposedCredentialRepository(exposed),
                reviews = ExposedReviewRepository(exposed),
                secrets = SecretResolver { _, _ -> SecretPayload("service-account-json") },
                audit = ExposedAuditLogRepository(exposed),
                sources = emptyList(),
            )

        val jobs =
            RatingsJobs(
                ratings =
                    DailyRatingsUseCase(
                        apps = apps,
                        channels = ExposedChannelRepository(exposed),
                        credentials = ExposedCredentialRepository(exposed),
                        snapshots = snapshots,
                        digests = ExposedRatingsDigestRepository(exposed),
                        secrets = SecretResolver { _, _ -> SecretPayload("service-account-json") },
                        ratingsSources = listOf(source),
                        notificationChannels = emptyList(),
                    ),
                apps = apps,
                failedJobs = failedJobs,
                sweepInterval = Duration.ofSeconds(1),
            )

        fun setUpApp(digestAt: LocalTime = LocalTime(8, 30)): App {
            val org = organizations.create("Matee", "matee-${Uuid.random()}".take(30))
            return apps.create(
                org.id,
                NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow", dailyDigestAt = digestAt),
            )
        }

        fun retime(
            app: App,
            digestAt: LocalTime,
        ) = apps.updateSettings(
            app.orgId,
            app.id,
            AppSettings(
                name = app.name,
                gpReportingBucket = app.gpReportingBucket,
                locale = app.locale,
                timezone = app.timezone,
                notifyFrom = app.notifyFrom,
                aiInstructions = app.aiInstructions,
                ingestIntervalMinutes = app.ingestIntervalMinutes,
                dailyDigestAt = digestAt,
                enabled = true,
            ),
        )

        beforeTest { TestDatabase.reset() }

        test("sweep naplánuje přehled nové appky a přeplánuje ho po změně času") {
            val app = setUpApp()
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    // Scheduler chce ingest vždycky; tady je bez zdrojů, ať nic nestahuje.
                    jobs = IngestJobs(ingest = idleIngest, apps = apps, failedJobs = failedJobs),
                    ratingsJobs = jobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                // Nikdo přehled neplánoval ručně — sweep si appku najde v databázi sám.
                eventually(30.seconds) {
                    scheduler.getScheduledExecutionsForTask(RatingsJobs.RATINGS_TASK, RatingsJobData::class.java) shouldHaveSize 1
                }
                val original =
                    scheduler
                        .getScheduledExecutionsForTask(RatingsJobs.RATINGS_TASK, RatingsJobData::class.java)
                        .single()
                        .executionTime

                retime(app, LocalTime(21, 15))

                eventually(30.seconds) {
                    val scheduled =
                        scheduler
                            .getScheduledExecutionsForTask(RatingsJobs.RATINGS_TASK, RatingsJobData::class.java)
                            .single()
                    scheduled.data.at shouldBe "21:15"
                    (scheduled.executionTime != original) shouldBe true
                }
            } finally {
                scheduler.stop()
            }
        }

        test("cron se skládá z času i zóny aplikace, ne ze serverového času") {
            val data = RatingsJobData(orgId = "org", appId = "app", at = "08:30", timezone = "America/New_York")

            // Klient v New Yorku má přehled v 8:30 svého času; dnešní n8n ho pošle podle
            // zóny instance, tedy v jednu ráno.
            data.schedule.toString() shouldContain "pattern=0 30 8 * * *"
            data.schedule.toString() shouldContain "zone=America/New_York"
        }

        test("neznámá zóna nesmí shodit plánovač") {
            val data = RatingsJobData(orgId = "org", appId = "app", at = "08:30", timezone = "Middle/Earth")

            data.schedule.toString() shouldContain "zone=UTC"
        }

        test("payload úlohy se ukládá jako čitelný JSON") {
            val data =
                RatingsJobData(
                    orgId = OrganizationId(Uuid.random()).toString(),
                    appId = "app-1",
                    at = "08:30",
                    timezone = "Europe/Prague",
                )

            val bytes = JsonTaskSerializer.serialize(data)

            bytes.toString(Charsets.UTF_8) shouldBe
                """{"orgId":"${data.orgId}","appId":"app-1","at":"08:30","timezone":"Europe/Prague"}"""
            JsonTaskSerializer.deserialize(RatingsJobData::class.java, bytes) shouldBe data
        }
    })
