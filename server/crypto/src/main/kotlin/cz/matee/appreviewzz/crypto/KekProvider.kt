package cz.matee.appreviewzz.crypto

/**
 * Klíč šifrující klíče (KEK). Aplikace ho nikdy nedrží — umí si nechat vyrobit nový
 * datový klíč a rozbalit ten, který má uložený v databázi. Tohle rozhraní je jediné místo,
 * které se liší mezi naším cloudem (AWS KMS) a self-hostem (lokální keyset, Vault transit).
 */
interface KekProvider {
    /** URI, kterým je provider nakonfigurovaný; ukládá se ke každému DEK kvůli migracím. */
    val uri: String

    /** Vyrobí nový datový klíč: otevřenou podobu pro okamžité použití a zabalenou pro uložení. */
    fun generateDataKey(): DataKeyMaterial

    /** Rozbalí uložený datový klíč. Pro cloud znamená každé volání jedno `Decrypt` v KMS. */
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Otevřený DEK smí existovat jen v paměti a jen po dobu použití — proto ta zvláštní
 * dvojice a proto se do logů nedostane ani omylem ([toString] je redigovaný).
 */
class DataKeyMaterial(
    val plaintext: ByteArray,
    val wrapped: ByteArray,
) {
    override fun toString(): String = "DataKeyMaterial(plaintext=**redacted**, wrapped=${wrapped.size}B)"
}

/** Selhání na straně správce klíčů: nedostupné KMS, chybějící oprávnění, poškozený keyset. */
class KeyManagementException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
