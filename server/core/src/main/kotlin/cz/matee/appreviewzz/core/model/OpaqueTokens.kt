package cz.matee.appreviewzz.core.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Náhodné tokeny pro session cookie a odkazy v e-mailu.
 *
 * Do databáze jde vždy jen otisk — token má tolik entropie, že se nedá hádat, takže
 * na rozdíl od hesla nepotřebuje sůl ani pomalou funkci, ale z dumpu se přesto vyčíst nedá.
 */
object OpaqueTokens {
    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): SecretPayload {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return SecretPayload(encoder.encodeToString(bytes))
    }

    fun hash(token: SecretPayload): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.value.toByteArray(Charsets.UTF_8))
}
