package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.ReviewAuthorKey
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.ReviewTimeKey
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IngestReviewsUseCaseTest :
    FunSpec({
        lateinit var apps: FakeAppRepository
        lateinit var credentials: FakeCredentialRepository
        lateinit var reviews: RecordingReviewRepository
        lateinit var audit: RecordingAuditLog

        val org = OrganizationId(Uuid.random())

        fun useCase(vararg sources: ReviewSource) =
            IngestReviewsUseCase(
                apps = apps,
                credentials = credentials,
                reviews = reviews,
                secrets = secretResolver(),
                audit = audit,
                sources = sources.toList(),
                clock = fixedClock(),
            )

        beforeTest {
            apps = FakeAppRepository()
            credentials = FakeCredentialRepository()
            reviews = RecordingReviewRepository()
            audit = RecordingAuditLog()
        }

        test("smazaná ani vypnutá appka nespadne, jen se přeskočí") {
            val disabled = apps.put(Ingest.app(org, enabled = false))
            val useCase = useCase(FakeReviewSource(Platform.ANDROID) { emptyList() })

            runBlocking { useCase.ingest(org, AppId(Uuid.random())) }.appSkipped shouldBe AppSkipReason.NOT_FOUND
            runBlocking { useCase.ingest(org, disabled.id) }.appSkipped shouldBe AppSkipReason.DISABLED
            reviews.calls.shouldHaveSize(0)
        }

        test("appka bez připojeného klíče se přeskočí, do storu se nesahá") {
            val app = apps.put(Ingest.app(org))
            val source = FakeReviewSource(Platform.ANDROID) { error("Konektor se neměl volat") }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            report.platforms shouldContainExactly
                listOf(PlatformIngest.Skipped(Platform.ANDROID, PlatformSkipReason.MISSING_CREDENTIAL))
            report.isRetryable shouldBe false
        }

        test("recenze starší než watermark se ukládají rovnou jako potlačené") {
            val watermark = Instant.parse("2026-08-19T10:00:00Z")
            val app = apps.put(Ingest.app(org, notifyFrom = watermark))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            val source =
                FakeReviewSource(Platform.ANDROID) {
                    listOf(
                        // Schválně v opačném pořadí, než ve kterém mají dorazit do kanálu.
                        Ingest.observed("gp:new", submittedAt = watermark.plus(kotlin.time.Duration.parse("1h"))),
                        Ingest.observed("gp:old", submittedAt = watermark.minus(kotlin.time.Duration.parse("1h"))),
                    )
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.calls.map { it.observed.storeReviewId to it.initialState } shouldContainExactly
                listOf(
                    "gp:old" to ReviewState.SUPPRESSED,
                    "gp:new" to ReviewState.NEW,
                )
            val ingested = report.platforms.single().shouldBeInstanceOf<PlatformIngest.Ingested>()
            ingested.created shouldBe 2
            ingested.suppressed shouldBe 1
            report.notifiable.map { it.review.storeReviewId } shouldContainExactly listOf("gp:new")
        }

        /**
         * Import čte export až do teď, takže recenzi s textem může založit dřív než API.
         * Ingest ji pak nesmí založit podruhé — převezme archivní řádek pod svým ID a pošle
         * ji do kanálu, protože import nic nedoručuje.
         */
        test("recenzi, kterou už založil import z exportu, ingest převezme a doručí") {
            val app = apps.put(Ingest.app(org))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT))
            val submitted = Instant.parse("2026-08-19T11:00:00.123456Z")
            reviews.archivedKeys += ReviewTimeKey("csv:c999e68a", Instant.parse("2026-08-19T11:00:00.123Z"))
            val source = FakeReviewSource(Platform.ANDROID) { listOf(Ingest.observed("gp:AOqpTOfresh", submittedAt = submitted)) }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.adoptions shouldContainExactly listOf("csv:c999e68a" to "gp:AOqpTOfresh")
            val ingested = report.platforms.single().shouldBeInstanceOf<PlatformIngest.Ingested>()
            ingested.adopted shouldBe 1
            report.notifiable.map { it.review.storeReviewId } shouldContainExactly listOf("gp:AOqpTOfresh")
            reviews.stateUpdates.map { it.second } shouldContainExactly listOf(ReviewState.NEW)
        }

        test("převzatá recenze pod watermarkem zůstane potlačená") {
            val watermark = Instant.parse("2026-08-19T12:00:00Z")
            val app = apps.put(Ingest.app(org, notifyFrom = watermark))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT))
            val edited = Instant.parse("2026-08-19T11:00:00Z")
            // Stará recenze, kterou autor přepsal: export ji má pod časem odeslání z roku 2023.
            reviews.archivedKeys += ReviewTimeKey("csv:1f964d28", Instant.parse("2023-12-01T17:27:04Z"), edited)
            val source = FakeReviewSource(Platform.ANDROID) { listOf(Ingest.observed("gp:AOqpTOold", submittedAt = edited)) }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.adoptions.map { it.first } shouldContainExactly listOf("csv:1f964d28")
            report.notifiable.shouldBeEmpty()
            reviews.stateUpdates.shouldBeEmpty()
        }

        /**
         * App Store vydá přepsanou recenzi pod novým ID s novým datem a starou odpověď k ní
         * nechá. Bez párování vznikla druhá recenze „před týdnem" s odpovědí z června.
         */
        test("přepsanou iOS recenzi pod novým ID ingest spáruje s původní") {
            val app = apps.put(Ingest.app(org, gpPackageName = null, ascAppId = "1499998888"))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.ASC_API_KEY))
            val original = Instant.parse("2026-06-02T07:11:06Z")
            val rewritten = Instant.parse("2026-09-30T10:35:06Z")
            reviews.authorKeys += ReviewAuthorKey("asc:old", "Jana N.", "CZ", original)
            val source =
                FakeReviewSource(Platform.IOS) {
                    listOf(Ingest.observed("asc:new", platform = Platform.IOS, submittedAt = rewritten))
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.adoptions shouldContainExactly listOf("asc:old" to "asc:new")
            val stored = reviews.calls.single().observed
            stored.submittedAt shouldBe original
            stored.storeUpdatedAt shouldBe rewritten
            report.platforms
                .single()
                .shouldBeInstanceOf<PlatformIngest.Ingested>()
                .rewritten shouldBe 1
        }

        test("iOS recenzi téhož autora ingest nespáruje, když store vrací i tu původní nebo nesedí území") {
            val app = apps.put(Ingest.app(org, gpPackageName = null, ascAppId = "1499998888"))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.ASC_API_KEY))
            val older = Instant.parse("2026-06-02T07:11:06Z")
            reviews.authorKeys += ReviewAuthorKey("asc:visible", "Jana N.", "CZ", older)
            reviews.authorKeys += ReviewAuthorKey("asc:elsewhere", "Jana N.", "SK", older)
            val source =
                FakeReviewSource(Platform.IOS) {
                    listOf(
                        Ingest.observed("asc:visible", platform = Platform.IOS, submittedAt = older),
                        Ingest.observed("asc:new", platform = Platform.IOS),
                    )
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.adoptions.shouldBeEmpty()
            report.platforms
                .single()
                .shouldBeInstanceOf<PlatformIngest.Ingested>()
                .rewritten shouldBe 0
        }

        test("u storu s výpisem bez mezer hledá smazané recenze od nejstarší ve výpisu") {
            val app = apps.put(Ingest.app(org, gpPackageName = null, ascAppId = "1499998888"))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.ASC_API_KEY))
            val oldest = Instant.parse("2026-01-10T08:00:00Z")
            val source =
                FakeReviewSource(Platform.IOS, listsWithoutGaps = true) {
                    listOf(
                        Ingest.observed("asc:new", platform = Platform.IOS),
                        Ingest.observed("asc:old", platform = Platform.IOS, submittedAt = oldest),
                    )
                }

            runBlocking { useCase(source).ingest(org, app.id) }

            reviews.unlistedChecks shouldContainExactly listOf(oldest)
        }

        test("prázdný výpis ani store s mezerami ve výpisu smazané recenze nehledají") {
            val app = apps.put(Ingest.app(org, ascAppId = "1499998888"))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT))
            credentials.attach(app.id, CredentialPurpose.REVIEWS, Ingest.credential(org, CredentialType.ASC_API_KEY))
            val android = FakeReviewSource(Platform.ANDROID) { listOf(Ingest.observed("gp:1")) }
            val ios = FakeReviewSource(Platform.IOS, listsWithoutGaps = true) { emptyList() }

            runBlocking { useCase(android, ios).ingest(org, app.id) }

            reviews.unlistedChecks.shouldBeEmpty()
        }

        test("appka bez watermarku notifikuje jen recenze mladší, než je sama") {
            // Řádky založené dřív, než se watermark vyplňoval sám. Bez fallbacku na čas
            // založení by první ingest vysypal do kanálu celou historii ze storu.
            val app = apps.put(Ingest.app(org, notifyFrom = null, createdAt = Ingest.now))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            val source =
                FakeReviewSource(Platform.ANDROID) {
                    listOf(
                        Ingest.observed("gp:history", submittedAt = Ingest.now.minus(kotlin.time.Duration.parse("30d"))),
                        Ingest.observed("gp:fresh", submittedAt = Ingest.now.plus(kotlin.time.Duration.parse("1h"))),
                    )
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            reviews.calls.map { it.observed.storeReviewId to it.initialState } shouldContainExactly
                listOf(
                    "gp:history" to ReviewState.SUPPRESSED,
                    "gp:fresh" to ReviewState.NEW,
                )
            report.notifiable.map { it.review.storeReviewId } shouldContainExactly listOf("gp:fresh")
        }

        test("odpověď nalezená ve storu recenzi rovnou vyřídí místo notifikace") {
            val app = apps.put(Ingest.app(org))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            val source =
                FakeReviewSource(Platform.ANDROID) {
                    listOf(Ingest.observed("gp:1", developerResponseBody = "Díky za zpětnou vazbu!"))
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            val ingested = report.platforms.single().shouldBeInstanceOf<PlatformIngest.Ingested>()
            ingested.answeredInStore shouldBe 1
            report.notifiable.shouldHaveSize(0)
            reviews.stateUpdates.map { it.second } shouldContainExactly listOf(ReviewState.REPLIED)
        }

        test("neplatný klíč shodí validaci credentialu a do auditu, retry nemá smysl") {
            val app = apps.put(Ingest.app(org))
            val credential = Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT)
            credentials.attach(app.id, CredentialPurpose.REVIEWS, credential)
            val source =
                FakeReviewSource(Platform.ANDROID) {
                    throw StoreConnectorException(StoreErrorKind.AUTH, "Service account nemá práva k appce")
                }

            val report = runBlocking { useCase(source).ingest(org, app.id) }

            val failed = report.platforms.single().shouldBeInstanceOf<PlatformIngest.Failed>()
            failed.kind shouldBe StoreErrorKind.AUTH
            failed.isRetryable shouldBe false
            report.isRetryable shouldBe false
            credentials.validations shouldContainExactly listOf(credential.id to ValidationStatus.INVALID)
            audit.entries.map { it.action } shouldContainExactly listOf("credential.validation_failed")
        }

        test("dočasná chyba jednoho storu nebrání druhému a hlásí se jako opakovatelná") {
            val app = apps.put(Ingest.app(org, ascAppId = "1499998888"))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                // Klíč, který se vrátil do hry — povedený fetch ho musí označit zpátky za platný.
                Ingest.credential(org, CredentialType.ASC_API_KEY, status = ValidationStatus.INVALID),
            )
            val android =
                FakeReviewSource(Platform.ANDROID) {
                    throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "429")
                }
            val ios = FakeReviewSource(Platform.IOS) { listOf(Ingest.observed("asc:1", platform = Platform.IOS)) }

            val report = runBlocking { useCase(android, ios).ingest(org, app.id) }

            report.failures.single().kind shouldBe StoreErrorKind.RATE_LIMITED
            report.isRetryable shouldBe true
            report.notifiable.map { it.review.storeReviewId } shouldContainExactly listOf("asc:1")
            ios.receivedIdentifier shouldBe "1499998888"
            credentials.validations.map { it.second } shouldContainExactly listOf(ValidationStatus.VALID)
            audit.entries.map { it.action } shouldContainExactly listOf("credential.validation_recovered")
        }

        test("konektor dostane identifikátor appky a rozbalený credential, ne id credentialu") {
            val app = apps.put(Ingest.app(org, gpPackageName = "cz.matee.mujup"))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            val source = FakeReviewSource(Platform.ANDROID) { emptyList() }

            runBlocking { useCase(source).ingest(org, app.id) }

            source.receivedIdentifier shouldBe "cz.matee.mujup"
            source.receivedSecret shouldBe "service-account-json"
        }
    })
