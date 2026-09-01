package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ValidationStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

/**
 * Kdy je appka doopravdy nastavená. Rozdíl mezi „chybí klíč" a „klíč se ověřuje" není
 * kosmetika: první je úkol pro klienta, druhé je čekání na store.
 */
class AppSetupCheckTest :
    StringSpec({

        val orgId = OrganizationId(Uuid.random())

        "appka s ověřeným klíčem a zapnutým kanálem je hotová" {
            val app = Ingest.app(orgId)
            val credentials = FakeCredentialRepository()
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(orgId, CredentialType.GP_SERVICE_ACCOUNT, ValidationStatus.VALID),
            )
            val channels = FakeChannelRepository(mutableListOf(Delivery.channel(orgId, app.id)))

            val setup = AppSetupCheck(credentials, channels).of(app)

            setup.ready shouldBe true
            setup.gaps.shouldBeEmpty()
        }

        "nahraný, ale neověřený klíč není hotovo — je to čekání na store" {
            val app = Ingest.app(orgId)
            val credentials = FakeCredentialRepository()
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(orgId, CredentialType.GP_SERVICE_ACCOUNT, ValidationStatus.UNKNOWN),
            )
            val channels = FakeChannelRepository(mutableListOf(Delivery.channel(orgId, app.id)))

            val setup = AppSetupCheck(credentials, channels).of(app)

            setup.ready shouldBe false
            setup.gaps shouldBe listOf(SetupGap.STORE_KEY_WAITING)
            setup.platformsWaitingForKey shouldBe listOf(Platform.ANDROID)
            // Chybějící klíč to není — klient nemá co doplňovat.
            setup.platformsWithoutKey.shouldBeEmpty()
        }

        "rozbitý klíč se hlásí stejně jako neověřený, ne jako hotovo" {
            val app = Ingest.app(orgId)
            val credentials = FakeCredentialRepository()
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(orgId, CredentialType.GP_SERVICE_ACCOUNT, ValidationStatus.INVALID),
            )
            val channels = FakeChannelRepository(mutableListOf(Delivery.channel(orgId, app.id)))

            AppSetupCheck(credentials, channels).of(app).gaps shouldBe listOf(SetupGap.STORE_KEY_WAITING)
        }

        "appka bez klíče hlásí chybějící klíč i chybějící kanál" {
            val app = Ingest.app(orgId)

            val setup = AppSetupCheck(FakeCredentialRepository(), FakeChannelRepository()).of(app)

            setup.gaps shouldBe listOf(SetupGap.STORE_KEY, SetupGap.CHANNEL)
            setup.platformsWithoutKey shouldBe listOf(Platform.ANDROID)
        }
    })
