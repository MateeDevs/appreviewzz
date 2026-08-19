package cz.matee.appreviewzz.crypto

import java.security.SecureRandom

/**
 * KEK v paměti — chová se jako KMS (zabalí a rozbalí), ale počítá volání, aby šlo ověřit,
 * že cache DEK skutečně šetří dotazy do správce klíčů.
 */
class FakeKekProvider(
    override val uri: String = "fake://test-kek",
) : KekProvider {
    private val masterKey = ByteArray(Aead.KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

    var generateCalls: Int = 0
        private set
    var unwrapCalls: Int = 0
        private set

    override fun generateDataKey(): DataKeyMaterial {
        generateCalls++
        val dek = ByteArray(Aead.KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        return DataKeyMaterial(plaintext = dek, wrapped = Aead.encrypt(masterKey, dek, WRAP_AAD))
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        unwrapCalls++
        return Aead.decrypt(masterKey, wrapped, WRAP_AAD)
    }

    private companion object {
        val WRAP_AAD = "fake-kek".toByteArray()
    }
}
