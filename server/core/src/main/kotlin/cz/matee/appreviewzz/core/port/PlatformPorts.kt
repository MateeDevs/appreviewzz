package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.UserId
import kotlin.time.Instant

/**
 * Porty platformní konfigurace (F7.2).
 *
 * Žádná z metod nebere `orgId` — a je to ta jediná vědomá výjimka z pravidla nahoře
 * v [Repositories]: konfigurace platformy k žádné organizaci nepatří a nesmí se do ní
 * dát zamíchat.
 */
interface PlatformSettingRepository {
    /** Všechno uložené najednou; čte to cache, ne jednotlivý požadavek. */
    fun all(): Map<String, String>

    fun upsert(
        key: String,
        value: String,
        actor: UserId?,
        at: Instant,
    )

    /** Smazání vrací hodnotu o patro níž — na prostředí, resp. na výchozí hodnotu v kódu. */
    fun delete(key: String): Boolean
}

/**
 * Co se o platformním tajemství smí říct nahlas. Ciphertext tu schválně není: tenhle typ
 * se serializuje do API odpovědi.
 */
data class PlatformSecretMeta(
    val key: String,
    val fingerprint: String,
    val hint: String?,
    val updatedAt: Instant,
    val updatedBy: UserId?,
)

interface PlatformSecretRepository {
    fun listMeta(): List<PlatformSecretMeta>

    fun findMeta(key: String): PlatformSecretMeta?

    fun findSealed(key: String): SealedSecret?

    fun upsert(
        key: String,
        secret: SealedSecret,
        fingerprint: String,
        hint: String?,
        actor: UserId?,
        at: Instant,
    )

    fun delete(key: String): Boolean

    /** Pro rotaci datového klíče — bez org-scope, stejně jako u uživatelských tajemství. */
    fun listSealed(): List<Pair<String, SealedSecret>>

    fun reseal(
        key: String,
        secret: SealedSecret,
    )
}

/**
 * Šifrování tajemství platformy. Stejný KEK a stejný DEK jako u uživatelských tajemství
 * (`app_data_key`), jiné AAD — `platform:<klíč>`, takže se zapečetěná hodnota nedá přesunout
 * na jiný klíč ani vydávat za tajemství uživatele.
 */
interface PlatformSecretVault {
    fun seal(
        key: String,
        secret: SecretPayload,
    ): SealedSecret

    fun open(
        key: String,
        sealed: SealedSecret,
    ): SecretPayload
}

/** Záznam v platformním auditu. Hodnota tajemství se sem nedostane nikdy — jen jeho otisk. */
data class PlatformAuditEntry(
    val actorUserId: UserId?,
    val actorLabel: String?,
    val action: String,
    val targetKey: String?,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
)

interface PlatformAuditRepository {
    fun append(entry: PlatformAuditEntry)

    fun listRecent(limit: Int): List<PlatformAuditEntry>
}

/**
 * Provozní čísla pro přehled v platformní sekci. Schválně jen **agregáty** — kdo chce vidět
 * recenze klienta, musí být jeho členem.
 */
data class PlatformStats(
    val organizations: Long,
    val users: Long,
    val apps: Long,
    val enabledApps: Long,
    /** Nevyřešené položky DLQ napříč tenanty. */
    val failedJobs: Long,
    /** Aplikace s vlastní výjimkou intervalu — bez nich se přehled špatně čte. */
    val appsWithIntervalOverride: Long,
)

interface PlatformStatsRepository {
    fun stats(): PlatformStats
}
