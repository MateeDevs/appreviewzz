package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.ValidationStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val SERVICE_ACCOUNT_JSON =
    """{"type":"service_account","project_id":"islegrow","private_key":"-----BEGIN PRIVATE KEY-----AAAA"}"""

class CredentialVaultTest :
    FunSpec({
        lateinit var kek: FakeKekProvider
        lateinit var dataKeys: InMemoryDataKeyRepository
        lateinit var credentials: InMemoryCredentialRepository
        lateinit var vault: CredentialVault

        beforeTest {
            kek = FakeKekProvider()
            dataKeys = InMemoryDataKeyRepository()
            credentials = InMemoryCredentialRepository()
            vault = CredentialVault(dataKeys, credentials, kek)
        }

        test("uložený credential jde přečíst zpátky, v úložišti leží jen ciphertext") {
            val org = OrganizationId(Uuid.random())

            val meta =
                vault.store(
                    orgId = org,
                    type = CredentialType.GP_SERVICE_ACCOUNT,
                    label = "IsleGrow GP",
                    payload = SecretPayload(SERVICE_ACCOUNT_JSON),
                    hint = "svc@islegrow.iam.gserviceaccount.com",
                )

            vault.load(org, meta.id).value shouldBe SERVICE_ACCOUNT_JSON
            credentials.rawCiphertext(meta.id).toString(Charsets.UTF_8) shouldNotContain "private_key"
            meta.fingerprint shouldBe SecretPayload(SERVICE_ACCOUNT_JSON).fingerprint()
        }

        test("AAD binding: ciphertext jiného credentialu téže organizace se nedešifruje") {
            val org = OrganizationId(Uuid.random())
            val slack = vault.store(org, CredentialType.SLACK_INSTALL, "Slack", SecretPayload("xoxb-token"))
            val asc = vault.store(org, CredentialType.ASC_API_KEY, "ASC", SecretPayload("asc-klic"))
            val sameKey = requireNotNull(dataKeys.findActive(org)).id

            // Stejná organizace, stejný datový klíč — liší se jen credential_id a typ v AAD.
            credentials.overwriteCiphertext(
                id = asc.id,
                ciphertext = credentials.rawCiphertext(slack.id),
                dataKeyId = sameKey,
            )

            shouldThrow<KeyManagementException> { vault.load(org, asc.id) }
        }

        test("datový klíč jiné organizace není v téhle organizaci dohledatelný") {
            val alfa = OrganizationId(Uuid.random())
            val beta = OrganizationId(Uuid.random())
            val alfaCredential = vault.store(alfa, CredentialType.ASC_API_KEY, "Alfa", SecretPayload("alfa"))
            val betaCredential = vault.store(beta, CredentialType.ASC_API_KEY, "Beta", SecretPayload("beta"))

            credentials.overwriteCiphertext(
                id = betaCredential.id,
                ciphertext = credentials.rawCiphertext(alfaCredential.id),
                dataKeyId = requireNotNull(dataKeys.findActive(alfa)).id,
            )

            shouldThrow<KeyManagementException> { vault.load(beta, betaCredential.id) }
        }

        test("každá organizace má vlastní DEK, takže stejný payload dá jiný ciphertext") {
            val alfa = OrganizationId(Uuid.random())
            val beta = OrganizationId(Uuid.random())
            val payload = SecretPayload("stejne-tajemstvi")

            val alfaCredential = vault.store(alfa, CredentialType.SLACK_INSTALL, "Alfa", payload)
            val betaCredential = vault.store(beta, CredentialType.SLACK_INSTALL, "Beta", payload)

            requireNotNull(dataKeys.findActive(alfa)).id shouldNotBe requireNotNull(dataKeys.findActive(beta)).id
            credentials.rawCiphertext(alfaCredential.id).toList() shouldNotBe
                credentials.rawCiphertext(betaCredential.id).toList()
        }

        test("cizí organizace credential vůbec nenajde") {
            val alfa = OrganizationId(Uuid.random())
            val beta = OrganizationId(Uuid.random())
            val credential = vault.store(alfa, CredentialType.TEAMS_BOT_REF, "Alfa", SecretPayload("tajemstvi"))

            shouldThrow<CredentialNotFoundException> { vault.load(beta, credential.id) }
        }

        test("rozbalený DEK se cachuje, KMS se neptáme při každém čtení") {
            val org = OrganizationId(Uuid.random())
            val credential = vault.store(org, CredentialType.ASC_API_KEY, "ASC", SecretPayload("tajemstvi"))

            repeat(5) { vault.load(org, credential.id) }
            // Klíč vznikl v paměti při zakládání, unwrap tedy zatím neproběhl vůbec.
            kek.unwrapCalls shouldBe 0

            vault.clearCache()
            repeat(3) { vault.load(org, credential.id) }
            kek.unwrapCalls shouldBe 1
        }

        test("rotace DEK přešifruje credentials a obsah zůstane čitelný") {
            val org = OrganizationId(Uuid.random())
            val gp = vault.store(org, CredentialType.GP_SERVICE_ACCOUNT, "GP", SecretPayload(SERVICE_ACCOUNT_JSON))
            val slack = vault.store(org, CredentialType.SLACK_INSTALL, "Slack", SecretPayload("xoxb-token"))
            val keyBefore = requireNotNull(dataKeys.findActive(org)).id
            val ciphertextBefore = credentials.rawCiphertext(gp.id).toList()

            vault.rotateDataKey(org) shouldBe 2

            requireNotNull(dataKeys.findActive(org)).id shouldNotBe keyBefore
            credentials.rawCiphertext(gp.id).toList() shouldNotBe ciphertextBefore
            vault.load(org, gp.id).value shouldBe SERVICE_ACCOUNT_JSON
            vault.load(org, slack.id).value shouldBe "xoxb-token"
        }

        test("rotace obsahu credentialu zneplatní výsledek validace") {
            val org = OrganizationId(Uuid.random())
            val credential = vault.store(org, CredentialType.ASC_API_KEY, "ASC", SecretPayload("stary-klic"))
            credentials.recordValidation(org, credential.id, ValidationStatus.VALID, null, Clock.System.now())

            val updated = requireNotNull(vault.replace(org, credential.id, SecretPayload("novy-klic")))

            updated.validationStatus shouldBe ValidationStatus.UNKNOWN
            vault.load(org, credential.id).value shouldBe "novy-klic"
        }

        test("payload se nedostane do logu ani přes toString") {
            val payload = SecretPayload("xoxb-velmi-tajne")

            payload.toString() shouldNotContain "xoxb"
            payload.toString() shouldContain "redacted"
        }
    })
