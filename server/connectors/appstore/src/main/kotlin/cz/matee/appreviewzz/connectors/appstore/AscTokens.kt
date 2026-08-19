package cz.matee.appreviewzz.connectors.appstore

import java.security.Signature
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Podepisování ES256 tokenů pro App Store Connect. Apple nemá token endpoint — token
 * si podepíšeme sami a posíláme ho jako Bearer.
 *
 * Životnost je 15 minut (Apple povoluje nejvýš 20) a tokeny se cachují per klíč, aby se
 * nepodepisoval nový při každém requestu.
 */
class AscTokens(
    private val clock: Clock = Clock.System,
) {
    private val cache = ConcurrentHashMap<String, CachedToken>()

    fun bearerToken(key: AscApiKey): String {
        val now = clock.now()
        cache[key.keyId]?.takeIf { it.expiresAt > now + EXPIRY_MARGIN }?.let { return it.token }

        val header = """{"alg":"ES256","kid":"${key.keyId}","typ":"JWT"}"""
        val issuedAt = now.epochSeconds
        val expiresAt = now + TOKEN_LIFETIME
        // Týmový klíč se identifikuje Issuer ID, individuální claimem sub="user".
        val identity = if (key.isIndividual) """"sub":"user"""" else """"iss":"${key.issuerId}""""
        val claims =
            """{$identity,"iat":$issuedAt,"exp":${expiresAt.epochSeconds},"aud":"$AUDIENCE"}"""

        val unsigned = "${header.base64Url()}.${claims.base64Url()}"
        val token = "$unsigned.${sign(key, unsigned).base64Url()}"
        cache[key.keyId] = CachedToken(token, expiresAt)
        return token
    }

    /** Po rotaci klíče nebo po 401 nemá smysl posílat token z cache znovu. */
    fun invalidate(key: AscApiKey) {
        cache.remove(key.keyId)
    }

    private fun sign(
        key: AscApiKey,
        unsigned: String,
    ): ByteArray =
        // JWT chce holé R||S, ne DER, které vrací výchozí "SHA256withECDSA" — proto P1363.
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(key.privateKey)
            update(unsigned.toByteArray(Charsets.UTF_8))
            sign()
        }

    companion object {
        const val AUDIENCE = "appstoreconnect-v1"

        private val TOKEN_LIFETIME = 15.minutes
        private val EXPIRY_MARGIN = 1.minutes
    }

    private class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )
}

private fun String.base64Url(): String = toByteArray(Charsets.UTF_8).base64Url()

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
