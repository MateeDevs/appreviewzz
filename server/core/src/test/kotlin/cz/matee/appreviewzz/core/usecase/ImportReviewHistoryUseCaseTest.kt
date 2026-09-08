package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.ReviewArchiveContext
import cz.matee.appreviewzz.core.port.ReviewArchiveSource
import cz.matee.appreviewzz.core.port.ReviewTimeKey
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val BUCKET = "pubsite_prod_8713874562713668954"

/** Zdroj archivu, který vrátí, co mu test nadiktuje — a pamatuje si, na co se ho ptali. */
private class FakeArchiveSource(
    override val platform: Platform,
    private val reviews: (ReviewArchiveContext) -> List<ObservedReview>,
) : ReviewArchiveSource {
    var lastContext: ReviewArchiveContext? = null

    override suspend fun fetchArchive(context: ReviewArchiveContext): List<ObservedReview> {
        lastContext = context
        return reviews(context)
    }
}

class ImportReviewHistoryUseCaseTest :
    FunSpec({
        lateinit var apps: FakeAppRepository
        lateinit var credentials: FakeCredentialRepository
        lateinit var reviews: RecordingReviewRepository

        val org = OrganizationId(Uuid.random())

        fun useCase(vararg archives: ReviewArchiveSource) =
            ImportReviewHistoryUseCase(
                apps = apps,
                credentials = credentials,
                reviews = reviews,
                secrets = secretResolver(),
                archives = archives.toList(),
                clock = fixedClock(),
            )

        fun archived(
            storeReviewId: String,
            submittedAt: Instant,
            storeUpdatedAt: Instant? = null,
        ) = Ingest
            .observed(storeReviewId, submittedAt = submittedAt)
            .copy(authorName = null, storeUpdatedAt = storeUpdatedAt)

        beforeTest {
            apps = FakeAppRepository()
            credentials = FakeCredentialRepository()
            reviews = RecordingReviewRepository()
        }

        fun appWithBucket(bucket: String? = BUCKET) =
            apps.put(Ingest.app(org, gpReportingBucket = bucket)).also {
                credentials.attach(it.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT))
            }

        test("bez reportingového bucketu se do archivu nesahá") {
            val app = appWithBucket(bucket = null)
            val source = FakeArchiveSource(Platform.ANDROID) { error("Archiv se neměl volat") }

            val report = runBlocking { useCase(source).run(org, app.id) }

            report.platforms shouldContainExactly
                listOf(PlatformHistoryImport.Skipped(Platform.ANDROID, HistoryPlatformSkipReason.NO_BUCKET))
            reviews.calls.shouldHaveSize(0)
        }

        /**
         * Archiv se nesmí potkat s oknem `reviews.list`. Kdyby se četl až do teď, tytéž recenze
         * by přišly podruhé pod `csv:` ID — a klíč `(app, platform, store_review_id)` je nespáruje.
         */
        test("období končí týden zpátky a začíná podle počtu měsíců") {
            val app = appWithBucket()
            val source = FakeArchiveSource(Platform.ANDROID) { emptyList() }

            runBlocking { useCase(source).run(org, app.id, months = 3) }

            val context = source.lastContext.shouldNotBeNull()
            context.reportingBucket shouldBe BUCKET
            context.until shouldBe Ingest.now - ImportReviewHistoryUseCase.API_WINDOW
            (context.until - context.since).inWholeDays shouldBe 93
        }

        test("bez zadaných měsíců jede průběžný běh nad posledními dvěma měsíci") {
            val app = appWithBucket()
            val source = FakeArchiveSource(Platform.ANDROID) { emptyList() }

            runBlocking { useCase(source).run(org, app.id) }

            val context = source.lastContext.shouldNotBeNull()
            (context.until - context.since).inWholeDays shouldBe 62
        }

        /**
         * Historie ani hodnocení bez textu nemají co dělat v kanálu: jsou stará dny až roky
         * a část z nich nemá text, ke kterému by se dalo napsat cokoli.
         */
        test("recenze z archivu se zakládají jako potlačené") {
            val app = appWithBucket()
            val source =
                FakeArchiveSource(Platform.ANDROID) {
                    listOf(archived("csv:a", Instant.parse("2026-08-01T10:00:00Z")))
                }

            val report = runBlocking { useCase(source).run(org, app.id) }

            reviews.calls.map { it.initialState } shouldContainExactly listOf(ReviewState.SUPPRESSED)
            report.created shouldBe 1
        }

        test("řádek, který už známe z API, se pozná podle času odeslání a přeskočí") {
            val app = appWithBucket()
            val submitted = Instant.parse("2026-08-01T10:00:00.440Z")
            // Tatáž recenze, jak ji před měsícem stáhlo API: jiné ID, čas na vteřinu stejný.
            reviews.timeKeys += ReviewTimeKey("gp:AOqpTOoriginal", Instant.parse("2026-08-01T10:00:00Z"))
            val source = FakeArchiveSource(Platform.ANDROID) { listOf(archived("csv:a", submitted)) }

            val report = runBlocking { useCase(source).run(org, app.id) }

            reviews.calls.shouldHaveSize(0)
            report.alreadyKnown shouldBe 1
            report.created shouldBe 0
        }

        test("vlastní řádek z minulého běhu import nezastaví — odpověď vývojáře musí jít doplnit") {
            val app = appWithBucket()
            val submitted = Instant.parse("2026-08-01T10:00:00Z")
            reviews.timeKeys += ReviewTimeKey("csv:a", submitted)
            val source = FakeArchiveSource(Platform.ANDROID) { listOf(archived("csv:a", submitted)) }

            val report = runBlocking { useCase(source).run(org, app.id) }

            reviews.calls.shouldHaveSize(1)
            report.alreadyKnown shouldBe 0
        }

        /** Editovanou recenzi má Android z API pod časem změny, export pod časem odeslání. */
        test("párování bere i čas poslední změny") {
            val app = appWithBucket()
            reviews.timeKeys += ReviewTimeKey("gp:AOqpTOoriginal", Instant.parse("2026-08-05T09:00:00Z"))
            val source =
                FakeArchiveSource(Platform.ANDROID) {
                    listOf(
                        archived(
                            "csv:a",
                            submittedAt = Instant.parse("2026-08-01T10:00:00Z"),
                            storeUpdatedAt = Instant.parse("2026-08-05T09:00:00Z"),
                        ),
                    )
                }

            runBlocking { useCase(source).run(org, app.id) }.alreadyKnown shouldBe 1
        }

        test("výpadek úložiště je chyba k zopakování, odepřený přístup ne") {
            val app = appWithBucket()

            val transient =
                FakeArchiveSource(Platform.ANDROID) {
                    throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage je nedostupné")
                }
            runBlocking { useCase(transient).run(org, app.id) }.isRetryable shouldBe true

            val denied =
                FakeArchiveSource(Platform.ANDROID) {
                    throw StoreConnectorException(StoreErrorKind.AUTH, "Service account nemá přístup")
                }
            val report = runBlocking { useCase(denied).run(org, app.id) }
            report.isRetryable shouldBe false
            report.failures
                .single()
                .shouldBeInstanceOf<PlatformHistoryImport.Failed>()
                .kind shouldBe StoreErrorKind.AUTH
        }

        test("smazaná ani vypnutá appka nespadne, jen se přeskočí") {
            val disabled = apps.put(Ingest.app(org, gpReportingBucket = BUCKET, enabled = false))
            val useCase = useCase(FakeArchiveSource(Platform.ANDROID) { emptyList() })

            runBlocking { useCase.run(org, disabled.id) }.skipped shouldBe HistoryAppSkipReason.DISABLED
        }
    })
