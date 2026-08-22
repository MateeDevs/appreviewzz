package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.SessionId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.UserSession
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.core.model.UserTotp
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
     * Uživatel, kterému platný token patří — **bez** jeho zneplatnění. Používá se tam, kde
     * po přečtení tokenu ještě může přijít neúspěch, po kterém má token dál platit: špatně
     * opsaný kód z autentizační appky nemá stát nové přihlášení heslem.
     */
    fun findValid(
        purpose: UserTokenPurpose,
        tokenHash: ByteArray,
        at: Instant,
    ): UserId?

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

    /**
     * Úklid uplatněných a prošlých tokenů. Volá ho plánovaná úloha, ne request.
     *
     * Není to jen kosmetika: otisk uplatněného tokenu už k ničemu neslouží, ale pořád je to
     * řádek navázaný na uživatele, který by v dumpu databáze ukazoval, kdy si kdo měnil heslo.
     */
    fun deleteSpent(before: Instant): Int
}

/**
 * Šifrování tajemství, která patří **uživateli, ne organizaci** (F5.3) — dnes TOTP seed.
 *
 * Vlastní port, protože credential vault je celý postavený na klíči per organizace a uživatel
 * žádnou mít nemusí (zakládá si účet dřív). Implementace v modulu `crypto` používá týž KEK,
 * takže z dumpu databáze bez přístupu ke správci klíčů je i tenhle sloupec bezcenný.
 */
interface UserSecretVault {
    /** @param purpose vstupuje do AAD — zapečetěné tajemství nejde použít v jiné roli. */
    fun seal(
        userId: UserId,
        purpose: String,
        secret: SecretPayload,
    ): SealedSecret

    fun open(
        userId: UserId,
        purpose: String,
        sealed: SealedSecret,
    ): SecretPayload
}

/**
 * Druhý faktor. Záchranné kódy jsou tu schválně vedle TOTP: patří k sobě provozně (zapnutím
 * druhého faktoru vzniknou, vypnutím zmizí) a odděleně by šlo snadno smazat jedno bez druhého.
 */
interface UserMfaRepository {
    fun find(userId: UserId): UserTotp?

    /** Rozdělané nastavení nahradí předchozí; potvrzené se nepřepisuje bez [delete]. */
    fun startSetup(
        userId: UserId,
        secret: SealedSecret,
        at: Instant,
    )

    fun confirm(
        userId: UserId,
        at: Instant,
        step: Long,
    )

    /** Zápis použitého kroku — tentýž kód podruhé neprojde. */
    fun recordStep(
        userId: UserId,
        step: Long,
    )

    /** Vypnutí druhého faktoru: mizí tajemství i všechny záchranné kódy. */
    fun delete(userId: UserId)

    /**
     * Všechna uložená tajemství. Bez org-scope záměrně — používá to **rotace datového klíče**,
     * která se ze své podstaty dívá přes celý deployment.
     */
    fun listSealed(): List<Pair<UserId, SealedSecret>>

    /** Přešifrování pod nový datový klíč. Obsah tajemství se nemění, jen klíč, pod kterým leží. */
    fun reseal(
        userId: UserId,
        secret: SealedSecret,
    )

    fun replaceRecoveryCodes(
        userId: UserId,
        hashes: List<ByteArray>,
        at: Instant,
    )

    /**
     * Uplatní záchranný kód. Atomicky — dvě souběžná odeslání téhož kódu uspějí nejvýš jednou.
     */
    fun consumeRecoveryCode(
        userId: UserId,
        hash: ByteArray,
        at: Instant,
    ): Boolean

    fun remainingRecoveryCodes(userId: UserId): Int
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
