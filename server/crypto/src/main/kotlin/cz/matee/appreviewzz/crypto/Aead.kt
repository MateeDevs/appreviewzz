package cz.matee.appreviewzz.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import java.security.GeneralSecurityException

/**
 * AES-256-GCM nad holým klíčem (Tink primitivum — generování nonce a jeho prefix řeší za nás).
 *
 * Před ciphertext přidáváme verzi formátu. Až se jednou bude měnit schéma šifrování,
 * půjde stará data přečíst a přešifrovat místo hádání, čím byla zašifrovaná.
 */
internal object Aead {
    const val FORMAT_VERSION: Byte = 1
    const val KEY_SIZE_BYTES = 32

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "DEK musí mít $KEY_SIZE_BYTES bajtů, má ${key.size}" }
        val ciphertext = AesGcmJce(key).encrypt(plaintext, associatedData)
        return byteArrayOf(FORMAT_VERSION) + ciphertext
    }

    /**
     * Selže, když nesedí klíč, AAD (ciphertext z jiné organizace nebo jiného credentialu)
     * nebo když byl ciphertext v databázi upravený. Nerozlišujeme to — venku by rozdíl mezi
     * „špatný klíč" a „změněná data" byl jen nápověda pro útočníka.
     */
    fun decrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(ciphertext.isNotEmpty()) { "Prázdný ciphertext" }
        val version = ciphertext[0]
        if (version != FORMAT_VERSION) {
            throw GeneralSecurityException("Neznámá verze formátu ciphertextu: $version")
        }
        return AesGcmJce(key).decrypt(ciphertext.copyOfRange(1, ciphertext.size), associatedData)
    }
}
