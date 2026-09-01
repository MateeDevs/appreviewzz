package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Ověřovací zdroj, který si test řídí — a počítá, kolikrát se ho někdo zeptal. */
private class ValidatingSource(
    override val platform: Platform,
    private val outcome: () -> ValidationOutcome,
) : ReviewSource {
    var calls = 0
        private set

    override suspend fun fetchReviews(context: StoreContext) = emptyList<cz.matee.appreviewzz.core.model.ObservedReview>()

    override suspend fun validate(context: StoreContext): ValidationOutcome {
        calls++
        return outcome()
    }
}

/**
 * Doověřování klíčů na pozadí. Celý smysl je jeden: klient pozve service account do Play
 * Console, zavře prohlížeč, a stav se v consoli překlopí sám.
 */
class RevalidateCredentialsTest :
    StringSpec({

        val orgId = OrganizationId(Uuid.random())

        fun setup(
            status: ValidationStatus,
            createdAgo: kotlin.time.Duration = 1.hours,
            validatedAgo: kotlin.time.Duration? = null,
        ): Triple<FakeAppRepository, FakeCredentialRepository, cz.matee.appreviewzz.core.model.CredentialMeta> {
            val apps = FakeAppRepository()
            val app = apps.put(Ingest.app(orgId, createdAt = Ingest.now - createdAgo))
            val credentials = FakeCredentialRepository()
            val meta =
                Ingest
                    .credential(orgId, CredentialType.GP_SERVICE_ACCOUNT, status)
                    .copy(
                        createdAt = Ingest.now - createdAgo,
                        validatedAt = validatedAgo?.let { Ingest.now - it },
                    )
            credentials.attach(app.id, CredentialPurpose.REVIEWS, meta)
            return Triple(apps, credentials, meta)
        }

        "klíč, na jehož práva se čeká, se doověří a překlopí do VALID" {
            val (apps, credentials, meta) = setup(ValidationStatus.UNKNOWN)
            val source = ValidatingSource(Platform.ANDROID) { ValidationOutcome(valid = true) }

            val report =
                RevalidateCredentialsUseCase(
                    apps = apps,
                    credentials = credentials,
                    secrets = secretResolver(),
                    sources = listOf(source),
                    clock = fixedClock(),
                ).revalidate()

            report.checked shouldBe 1
            report.nowValid shouldBe 1
            credentials.validations shouldBe listOf(meta.id to ValidationStatus.VALID)
        }

        "pořád nepropsaná pozvánka zůstane INVALID a zkusí se příště znovu" {
            val (apps, credentials, meta) = setup(ValidationStatus.INVALID, validatedAgo = 1.hours)
            val source =
                ValidatingSource(Platform.ANDROID) {
                    ValidationOutcome(valid = false, message = "Service account nemá přístup k aplikaci")
                }

            val report =
                RevalidateCredentialsUseCase(
                    apps = apps,
                    credentials = credentials,
                    secrets = secretResolver(),
                    sources = listOf(source),
                    clock = fixedClock(),
                ).revalidate()

            report.nowValid shouldBe 0
            report.stillFailing shouldBe 1
            credentials.validations shouldBe listOf(meta.id to ValidationStatus.INVALID)
        }

        "fungující klíč se nekontroluje — od toho je ingest" {
            val (apps, credentials, _) = setup(ValidationStatus.VALID)
            val source = ValidatingSource(Platform.ANDROID) { ValidationOutcome(valid = true) }

            RevalidateCredentialsUseCase(
                apps = apps,
                credentials = credentials,
                secrets = secretResolver(),
                sources = listOf(source),
                clock = fixedClock(),
            ).revalidate()

            source.calls shouldBe 0
            credentials.validations.shouldBeEmpty()
        }

        "po dvou dnech se to vzdá: tohle už není čekání na Google, ale neodeslaná pozvánka" {
            val (apps, credentials, _) = setup(ValidationStatus.INVALID, createdAgo = 100.hours, validatedAgo = 60.hours)
            val source = ValidatingSource(Platform.ANDROID) { ValidationOutcome(valid = true) }

            val report =
                RevalidateCredentialsUseCase(
                    apps = apps,
                    credentials = credentials,
                    secrets = secretResolver(),
                    sources = listOf(source),
                    clock = fixedClock(),
                ).revalidate()

            report.checked shouldBe 0
            source.calls shouldBe 0
        }

        "výjimka konektoru neshodí běh, jen se zapíše jako neúspěch" {
            val (apps, credentials, meta) = setup(ValidationStatus.UNKNOWN)
            val source =
                ValidatingSource(Platform.ANDROID) {
                    throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Google zrovna neodpovídá")
                }

            val report =
                RevalidateCredentialsUseCase(
                    apps = apps,
                    credentials = credentials,
                    secrets = secretResolver(),
                    sources = listOf(source),
                    clock = fixedClock(),
                ).revalidate()

            report.stillFailing shouldBe 1
            credentials.validations shouldBe listOf(meta.id to ValidationStatus.INVALID)
        }
    })
