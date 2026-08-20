package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.SessionId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.UserSession
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import kotlin.time.Instant

/**
 * Hašování hesel. Implementace (argon2id) sedí v modulu `crypto` — doména o parametrech
 * funkce nic neví a při jejich zvýšení se nemění ani řádek use-case.
 */
interface PasswordHasher {
    fun hash(password: SecretPayload): String

    /** Ověření v konstantním čase vůči obsahu hashe; `false` i pro poškozený zápis. */
    fun verify(
        password: SecretPayload,
        hash: String,
    ): Boolean
}

interface SessionRepository {
    fun create(
        userId: UserId,
        tokenHash: ByteArray,
        createdAt: Instant,
        expiresAt: Instant,
        userAgent: String?,
        clientIp: String?,
    ): UserSession

    /** Vrací jen relaci, která není odvolaná ani prošlá — kontrolu času nedělá volající. */
    fun findValid(
        tokenHash: ByteArray,
        at: Instant,
    ): UserSession?

    fun touch(
        id: SessionId,
        at: Instant,
    )

    fun revoke(
        id: SessionId,
        at: Instant,
    ): Boolean

    /** Po resetu hesla: padá všechno, včetně relace, ze které se to stalo. */
    fun revokeAllOfUser(
        userId: UserId,
        at: Instant,
    ): Int

    /**
     * Po změně hesla přihlášeným člověkem. Odhlásit ho z prohlížeče, ve kterém si heslo
     * právě změnil, nedává smysl — všechny ostatní relace ale padnou.
     */
    fun revokeAllOfUserExcept(
        userId: UserId,
        keep: SessionId,
        at: Instant,
    ): Int

    fun listActive(
        userId: UserId,
        at: Instant,
    ): List<UserSession>

    /** Úklid prošlých řádků; volá ho plánovaná úloha, ne request. */
    fun deleteExpired(before: Instant): Int
}

interface UserTokenRepository {
    fun create(
        userId: UserId,
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        expiresAt: Instant,
        at: Instant,
    )

    /**
     * Vymění otisk za uživatele a token zneplatní. Atomicky: dvě souběžná kliknutí na týž
     * odkaz uspějí nejvýš jednou.
     */
    fun consume(
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        at: Instant,
    ): UserId?

    /** Vydání nového odkazu zneplatní předchozí — jinak by staré e-maily zůstaly použitelné. */
    fun invalidateAll(
        userId: UserId,
        purpose: UserTokenPurpose,
        at: Instant,
    ): Int
}

/** Jeden e-mail. Odesílatele i transport řeší implementace, doména zná jen adresáta a text. */
data class OutgoingMail(
    val to: String,
    val subject: String,
    val body: String,
)

interface Mailer {
    /**
     * Odešle e-mail, nebo vyhodí [MailException]. Selhání pošty **nesmí shodit operaci**,
     * kvůli které e-mail vzniká — pozvánka i registrace platí, i když pošta zrovna nejede,
     * a odkaz se dá poslat znovu.
     */
    fun send(mail: OutgoingMail)
}

class MailException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
