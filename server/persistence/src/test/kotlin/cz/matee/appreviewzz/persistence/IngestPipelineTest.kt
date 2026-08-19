package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.core.usecase.PlatformIngest
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Ingest use-case nad skutečnými repozitáři: dedup, watermark a stavy tady nedrží testovací
 * dvojník, ale Postgres. Fake je jen store (konektor má vlastní kontraktní testy) a vault.
 */
class IngestPipelineTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val auditLog = ExposedAuditLogRepository(exposed)

        val secrets = SecretResolver { _, _ -> SecretPayload("service-account-json") }
        var store: List<ObservedReview> = emptyList()
        var failure: StoreConnectorException? = null

        val source =
            object : ReviewSource {
                override val platform = Platform.ANDROID

                override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> = failure?.let { throw it } ?: store

                override suspend fun validate(context: StoreContext): ValidationOutcome = ValidationOutcome(true)
            }

        val useCase =
            IngestReviewsUseCase(
                apps = apps,
                credentials = credentials,
                reviews = reviews,
                secrets = secrets,
                audit = auditLog,
                sources = listOf(source),
            )

        fun setUpApp(notifyFrom: Instant? = null): Pair<OrganizationId, AppId> {
            val org = organizations.create("Matee", "matee")
            val app =
                apps.create(
                    org.id,
                    NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow", notifyFrom = notifyFrom),
                )
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Fixtures.seenAt)
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
            return org.id to app.id
        }

        beforeTest {
            TestDatabase.reset()
            store = emptyList()
            failure = null
        }

        test("druhý běh nad stejnými daty nic nezaloží ani neposílá — dedup je v databázi") {
            val (org, app) = setUpApp()
            store = listOf(Fixtures.observedReview(storeReviewId = "gp:1"))

            val first = runBlocking { useCase.ingest(org, app) }
            first.notifiable shouldHaveSize 1
            first.platforms
                .single()
                .shouldBeInstanceOf<PlatformIngest.Ingested>()
                .created shouldBe 1

            val second = runBlocking { useCase.ingest(org, app) }
            val ingested = second.platforms.single().shouldBeInstanceOf<PlatformIngest.Ingested>()
            ingested.created shouldBe 0
            ingested.unchanged shouldBe 1
            second.notifiable shouldHaveSize 0
            reviews.listByApp(org, app) shouldHaveSize 1
        }

        test("editovaná recenze se pozná podle otisku a jde do kanálu znovu") {
            val (org, app) = setUpApp()
            val original = Fixtures.observedReview(storeReviewId = "gp:1", starRating = 2)
            store = listOf(original)
            runBlocking { useCase.ingest(org, app) }

            store = listOf(original.copy(starRating = 5, body = "Po aktualizaci paráda."))
            val report = runBlocking { useCase.ingest(org, app) }

            val ingested = report.platforms.single().shouldBeInstanceOf<PlatformIngest.Ingested>()
            ingested.updated shouldBe 1
            report.notifiable
                .single()
                .review.state shouldBe ReviewState.UPDATED
            reviews.findByStoreId(org, app, Platform.ANDROID, "gp:1").shouldNotBeNull().starRating shouldBe 5
        }

        test("odpověď z Play Console recenzi vyřídí, editace po ní se pořád notifikuje") {
            val (org, app) = setUpApp()
            val original = Fixtures.observedReview(storeReviewId = "gp:1")
            store = listOf(original)
            runBlocking { useCase.ingest(org, app) }

            // Někdo odpověděl mimo náš systém — recenze je vyřízená, do kanálu nic nejde.
            val answered = original.copy(developerResponseBody = "Díky, mrkneme na to.")
            store = listOf(answered)
            val afterReply = runBlocking { useCase.ingest(org, app) }

            afterReply.notifiable shouldHaveSize 0
            afterReply.platforms
                .single()
                .shouldBeInstanceOf<PlatformIngest.Ingested>()
                .answeredInStore shouldBe 1
            reviews.findByStoreId(org, app, Platform.ANDROID, "gp:1").shouldNotBeNull().state shouldBe
                ReviewState.REPLIED

            // Autor pak recenzi přepsal: to už je novinka, kterou tým vidět chce.
            store = listOf(answered.copy(starRating = 5, body = "Po odpovědi měním na pět hvězd."))
            val afterEdit = runBlocking { useCase.ingest(org, app) }

            afterEdit.notifiable shouldHaveSize 1
            afterEdit.platforms
                .single()
                .shouldBeInstanceOf<PlatformIngest.Ingested>()
                .answeredInStore shouldBe 0
        }

        test("watermark potlačí historii a drží ji potlačenou i po editaci") {
            val cutover = Instant.parse("2026-08-19T09:00:00Z")
            val (org, app) = setUpApp(notifyFrom = cutover)
            val old =
                Fixtures.observedReview(
                    storeReviewId = "gp:old",
                    submittedAt = cutover.minus(kotlin.time.Duration.parse("2h")),
                )
            val fresh = Fixtures.observedReview(storeReviewId = "gp:new", submittedAt = cutover)
            store = listOf(old, fresh)

            val report = runBlocking { useCase.ingest(org, app) }

            report.notifiable.map { it.review.storeReviewId } shouldContainExactly listOf("gp:new")
            reviews.findByStoreId(org, app, Platform.ANDROID, "gp:old").shouldNotBeNull().state shouldBe
                ReviewState.SUPPRESSED

            store = listOf(old.copy(body = "Doplňuji po roce: pořád stejné."), fresh)
            val afterEdit = runBlocking { useCase.ingest(org, app) }

            afterEdit.notifiable shouldHaveSize 0
            reviews.findByStoreId(org, app, Platform.ANDROID, "gp:old").shouldNotBeNull().state shouldBe
                ReviewState.SUPPRESSED
        }

        test("neplatný klíč se propíše do stavu credentialu a do audit logu") {
            val (org, app) = setUpApp()
            failure = StoreConnectorException(StoreErrorKind.AUTH, "Service account nemá práva k appce")

            val report = runBlocking { useCase.ingest(org, app) }

            report.failures.single().kind shouldBe StoreErrorKind.AUTH
            credentials
                .findForApp(org, app, CredentialPurpose.REVIEWS, CredentialType.GP_SERVICE_ACCOUNT)
                .shouldNotBeNull()
                .validationStatus shouldBe ValidationStatus.INVALID
            auditLog.list(org).map { it.action } shouldContainExactly listOf("credential.validation_failed")
        }
    })
