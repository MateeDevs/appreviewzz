package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

/**
 * Nejdůležitější testy celého datového modelu: data jedné organizace nesmí být
 * dosažitelná pod identitou jiné, ani omylem, ani přes vazební tabulku.
 */
class TenantIsolationTest :
    FunSpec({
        val exposed = TestDatabase.database.exposed
        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val channels = ExposedChannelRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)

        beforeTest { TestDatabase.reset() }

        test("cizí appka, recenze ani credential nejsou pod jinou org vidět") {
            val alfa = organizations.create("Alfa", "alfa")
            val beta = organizations.create("Beta", "beta")

            val alfaApp = apps.create(alfa.id, NewApp(name = "Alfa app", gpPackageName = "cz.alfa"))
            val review =
                reviews
                    .upsert(alfa.id, alfaApp.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW)
                    .review
            val key = dataKeys.create(alfa.id, "local://keyset", byteArrayOf(1), Fixtures.seenAt)
            val credential =
                credentials.create(
                    alfa.id,
                    NewCredential(
                        id = CredentialId(Uuid.random()),
                        type = CredentialType.ASC_API_KEY,
                        label = "Alfa ASC",
                        dataKeyId = key.id,
                        ciphertext = byteArrayOf(7),
                        fingerprint = "sha256:alfa",
                    ),
                )

            apps.findById(beta.id, alfaApp.id) shouldBe null
            apps.listByOrg(beta.id).shouldBeEmpty()
            reviews.findById(beta.id, review.id) shouldBe null
            reviews.listByApp(beta.id, alfaApp.id).shouldBeEmpty()
            credentials.findMeta(beta.id, credential.id) shouldBe null
            credentials.loadForDecryption(beta.id, credential.id) shouldBe null
            credentials.listByOrg(beta.id).shouldBeEmpty()
        }

        test("cizí credential nejde připnout na vlastní appku") {
            val alfa = organizations.create("Alfa", "alfa")
            val beta = organizations.create("Beta", "beta")
            val key = dataKeys.create(alfa.id, "local://keyset", byteArrayOf(1), Fixtures.seenAt)
            val alfaCredential =
                credentials.create(
                    alfa.id,
                    NewCredential(
                        id = CredentialId(Uuid.random()),
                        type = CredentialType.ASC_API_KEY,
                        label = "Alfa ASC",
                        dataKeyId = key.id,
                        ciphertext = byteArrayOf(7),
                        fingerprint = "sha256:alfa",
                    ),
                )
            val betaApp = apps.create(beta.id, NewApp(name = "Beta app", ascAppId = "123456"))

            shouldThrow<IllegalArgumentException> {
                credentials.attachToApp(beta.id, betaApp.id, alfaCredential.id, CredentialPurpose.REVIEWS)
            }
        }

        test("kanál nejde pověsit na appku jiné organizace") {
            val alfa = organizations.create("Alfa", "alfa")
            val beta = organizations.create("Beta", "beta")
            val alfaApp = apps.create(alfa.id, NewApp(name = "Alfa app", gpPackageName = "cz.alfa"))

            shouldThrow<IllegalArgumentException> {
                channels.create(
                    beta.id,
                    NewChannel(appId = alfaApp.id, type = ChannelType.SLACK, targetRef = "C123"),
                )
            }
        }

        test("stejný balíček ve dvou organizacích je v pořádku") {
            val alfa = organizations.create("Alfa", "alfa")
            val beta = organizations.create("Beta", "beta")

            apps.create(alfa.id, NewApp(name = "Sdílený", gpPackageName = "cz.sdileny"))
            apps.create(beta.id, NewApp(name = "Sdílený", gpPackageName = "cz.sdileny"))

            apps.listByOrg(alfa.id).size shouldBe 1
            apps.listByOrg(beta.id).size shouldBe 1
        }
    })
