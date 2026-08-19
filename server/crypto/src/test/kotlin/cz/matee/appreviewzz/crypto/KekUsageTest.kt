package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.SecretPayload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class KekUsageTest :
    FunSpec({
        test("počítá rozbalení, výrobu klíčů a selhání zvlášť") {
            val usage = KekUsage()
            val provider = MeteredKekProvider(FakeKekProvider(), usage)

            val material = provider.generateDataKey()
            provider.unwrap(material.wrapped)
            provider.unwrap(material.wrapped)

            usage.generateCount shouldBe 1
            usage.unwrapCount shouldBe 2
            usage.failureCount shouldBe 0
        }

        test("neúspěšné rozbalení se počítá jako selhání, ne jako rozbalení") {
            val usage = KekUsage()
            val provider = MeteredKekProvider(BrokenKekProvider(), usage)

            shouldThrow<KeyManagementException> { provider.unwrap(byteArrayOf(1, 2, 3)) }

            usage.unwrapCount shouldBe 0
            usage.failureCount shouldBe 1
        }

        // Metrika má ukazovat volání do správce klíčů, ne použití credentialu — jinak by práh
        // alarmu držel provoz, a ne to, co nás zajímá: kolikrát klíč opravdu opustil KMS.
        test("cache datového klíče drží počet rozbalení dole i při opakovaném čtení") {
            val usage = KekUsage()
            val vault =
                CredentialVault(
                    dataKeys = InMemoryDataKeyRepository(),
                    credentials = InMemoryCredentialRepository(),
                    kek = MeteredKekProvider(FakeKekProvider(), usage),
                )
            val orgId = OrganizationId(Uuid.random())
            val meta =
                vault.store(
                    orgId = orgId,
                    type = CredentialType.GP_SERVICE_ACCOUNT,
                    label = "test",
                    payload = SecretPayload("{}"),
                )

            repeat(5) { vault.load(orgId, meta.id) }

            usage.generateCount shouldBe 1
            usage.unwrapCount shouldBe 0
        }
    })

private class BrokenKekProvider : KekProvider {
    override val uri: String = "broken://kek"

    override fun generateDataKey(): DataKeyMaterial = throw KeyManagementException("KMS nedostupné")

    override fun unwrap(wrapped: ByteArray): ByteArray = throw KeyManagementException("KMS nedostupné")
}
