package cz.matee.appreviewzz.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.GeneralSecurityException
import java.security.SecureRandom

class AeadTest :
    FunSpec({
        fun key() = ByteArray(Aead.KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

        val plaintext = "-----BEGIN PRIVATE KEY-----MIIEvQ".toByteArray()
        val aad = "org:credential:ASC_API_KEY".toByteArray()

        test("šifrování a dešifrování se stejným AAD") {
            val key = key()
            val ciphertext = Aead.encrypt(key, plaintext, aad)

            Aead.decrypt(key, ciphertext, aad).toList() shouldBe plaintext.toList()
            ciphertext.first() shouldBe Aead.FORMAT_VERSION
        }

        test("stejný vstup dá pokaždé jiný ciphertext (náhodný nonce)") {
            val key = key()

            Aead.encrypt(key, plaintext, aad).toList() shouldNotBe Aead.encrypt(key, plaintext, aad).toList()
        }

        test("jiné AAD ciphertext neotevře") {
            val key = key()
            val ciphertext = Aead.encrypt(key, plaintext, aad)

            shouldThrow<GeneralSecurityException> {
                Aead.decrypt(key, ciphertext, "org:jiny-credential:ASC_API_KEY".toByteArray())
            }
        }

        test("jiný klíč ciphertext neotevře") {
            val ciphertext = Aead.encrypt(key(), plaintext, aad)

            shouldThrow<GeneralSecurityException> { Aead.decrypt(key(), ciphertext, aad) }
        }

        test("změna jediného bajtu ciphertext zneplatní") {
            val key = key()
            val ciphertext = Aead.encrypt(key, plaintext, aad)
            ciphertext[ciphertext.lastIndex] = (ciphertext[ciphertext.lastIndex] + 1).toByte()

            shouldThrow<GeneralSecurityException> { Aead.decrypt(key, ciphertext, aad) }
        }

        test("neznámá verze formátu se pozná dřív než chyba v dešifrování") {
            val key = key()
            val ciphertext = Aead.encrypt(key, plaintext, aad)
            ciphertext[0] = 99

            val error = shouldThrow<GeneralSecurityException> { Aead.decrypt(key, ciphertext, aad) }
            error.message shouldBe "Neznámá verze formátu ciphertextu: 99"
        }

        test("klíč jiné délky než 256 bitů se odmítne") {
            shouldThrow<IllegalArgumentException> { Aead.encrypt(ByteArray(16), plaintext, aad) }
        }
    })
