package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppDataKey
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.BackupRun
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.FailedJob
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrgDataKey
import cz.matee.appreviewzz.core.model.OrgMembership
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.ReplyId
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.ReviewMessageId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserAccount
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.ValidationStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Porty do perzistence. Pravidlo, které tu drží multi-tenancy: **každá metoda, která
 * čte nebo mění data organizace, bere `orgId` jako první parametr** a implementace ho
 * překlápí do `WHERE org_id = ?`. Metody bez `orgId` jsou vědomé výjimky pro scheduler
 * a mají to napsané v komentáři.
 */
interface Transactor {
    /** Spustí blok v jedné databázové transakci; vnořené volání se připojí ke stávající. */
    fun <T> transaction(block: () -> T): T
}

interface OrganizationRepository {
    fun create(
        name: String,
        slug: String,
    ): Organization

    fun findById(id: OrganizationId): Organization?

    fun findBySlug(slug: String): Organization?

    fun list(): List<Organization>
}

interface UserRepository {
    fun create(
        email: String,
        displayName: String?,
    ): User

    fun findById(id: UserId): User?

    fun findByEmail(email: String): User?

    /**
     * Přihlašovací pohled na uživatele — hash hesla, ověření e-mailu, brzda proti hádání.
     * Volá ho výhradně autentizační use-case; zbytek aplikace si vystačí s [findById].
     */
    fun findAccountByEmail(email: String): UserAccount?

    fun findAccountById(id: UserId): UserAccount?

    fun setPassword(
        id: UserId,
        passwordHash: String,
        at: Instant,
    ): Boolean

    fun markEmailVerified(
        id: UserId,
        at: Instant,
    ): Boolean

    /**
     * Zápis výsledku pokusu o přihlášení. Kdy se účet zamkne, rozhoduje use-case —
     * repozitář jen uloží, na čem se dohodl.
     */
    fun recordLoginAttempt(
        id: UserId,
        failedLoginCount: Int,
        lockedUntil: Instant?,
        lastLoginAt: Instant?,
    )
}

interface MembershipRepository {
    fun upsert(
        orgId: OrganizationId,
        userId: UserId,
        role: OrgRole,
    ): OrgMembership

    fun listByOrg(orgId: OrganizationId): List<OrgMembership>

    /** Bez org-scope záměrně: tohle je pohled uživatele na to, do kterých organizací patří. */
    fun listByUser(userId: UserId): List<OrgMembership>

    fun roleOf(
        orgId: OrganizationId,
        userId: UserId,
    ): OrgRole?

    fun remove(
        orgId: OrganizationId,
        userId: UserId,
    ): Boolean
}

data class NewApp(
    val name: String,
    val gpPackageName: String? = null,
    val gpReportingBucket: String? = null,
    val ascAppId: String? = null,
    val locale: MessageLocale = MessageLocale.CS,
    val timezone: String = "Europe/Prague",
    val notifyFrom: Instant? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int = 30,
    val dailyDigestAt: LocalTime = LocalTime(8, 30),
)

/** Kompletní nastavení appky — update je nahrazení celku, ne patch po polích. */
data class AppSettings(
    val name: String,
    val gpReportingBucket: String?,
    val locale: MessageLocale,
    val timezone: String,
    val notifyFrom: Instant?,
    val aiInstructions: String?,
    val ingestIntervalMinutes: Int,
    val dailyDigestAt: LocalTime,
    val enabled: Boolean,
)

interface AppRepository {
    fun create(
        orgId: OrganizationId,
        app: NewApp,
    ): App

    fun findById(
        orgId: OrganizationId,
        id: AppId,
    ): App?

    fun listByOrg(orgId: OrganizationId): List<App>

    /** Bez org-scope záměrně: scheduler plánuje ingest napříč všemi tenanty. */
    fun listEnabled(): List<App>

    fun updateSettings(
        orgId: OrganizationId,
        id: AppId,
        settings: AppSettings,
    ): App?

    fun delete(
        orgId: OrganizationId,
        id: AppId,
    ): Boolean
}

interface DataKeyRepository {
    fun findActive(orgId: OrganizationId): OrgDataKey?

    fun findById(
        orgId: OrganizationId,
        id: DataKeyId,
    ): OrgDataKey?

    /** Založí nový aktivní DEK; případný předchozí zneaktivní (rotace). */
    fun create(
        orgId: OrganizationId,
        kekUri: String,
        wrappedDek: ByteArray,
        at: Instant,
    ): OrgDataKey
}

/**
 * DEK pro tajemství vázaná na uživatele, ne na organizaci (F5.3). Deployment má nejvýš
 * jeden aktivní a vzniká líně — instalace, kde si nikdo nezapne druhý faktor, ho nemá.
 */
interface AppDataKeyRepository {
    fun findActive(): AppDataKey?

    fun findById(id: Uuid): AppDataKey?

    fun create(
        kekUri: String,
        wrappedDek: ByteArray,
        at: Instant,
    ): AppDataKey
}

/**
 * Zašifrovaný payload + metadata; šifrování samo řeší vault (F1.2).
 *
 * ID vzniká **před** zápisem, protože je součástí AAD (`org_id:credential_id:type`) —
 * ciphertext je tak kryptograficky svázaný s řádkem, do kterého patří.
 */
data class NewCredential(
    val id: CredentialId,
    val type: CredentialType,
    val label: String,
    val dataKeyId: DataKeyId,
    val ciphertext: ByteArray,
    val fingerprint: String,
    val hint: String? = null,
) {
    // ByteArray v data class: equals/hashCode musí být ruční, jinak se porovnává reference.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is NewCredential &&
                    id == other.id &&
                    type == other.type &&
                    label == other.label &&
                    dataKeyId == other.dataKeyId &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    fingerprint == other.fingerprint &&
                    hint == other.hint
            )

    override fun hashCode(): Int =
        listOf(id, type, label, dataKeyId, ciphertext.contentHashCode(), fingerprint, hint)
            .fold(7) { acc, part -> 31 * acc + part.hashCode() }
}

/** To, co si z databáze bere vault, aby mohl dešifrovat. Nikdy neopouští worker. */
data class StoredCredential(
    val meta: CredentialMeta,
    val dataKeyId: DataKeyId,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is StoredCredential &&
                    meta == other.meta &&
                    dataKeyId == other.dataKeyId &&
                    ciphertext.contentEquals(other.ciphertext)
            )

    override fun hashCode(): Int = 31 * (31 * meta.hashCode() + dataKeyId.hashCode()) + ciphertext.contentHashCode()
}

interface CredentialRepository {
    fun create(
        orgId: OrganizationId,
        credential: NewCredential,
    ): CredentialMeta

    /** Metadata pro console — bez ciphertextu, aby se payload nedal omylem serializovat ven. */
    fun findMeta(
        orgId: OrganizationId,
        id: CredentialId,
    ): CredentialMeta?

    fun listByOrg(
        orgId: OrganizationId,
        type: CredentialType? = null,
    ): List<CredentialMeta>

    /** Ciphertext + wrapped DEK reference; volá výhradně vault. */
    fun loadForDecryption(
        orgId: OrganizationId,
        id: CredentialId,
    ): StoredCredential?

    fun replacePayload(
        orgId: OrganizationId,
        id: CredentialId,
        credential: NewCredential,
    ): CredentialMeta?

    /**
     * Přešifrování pod nový DEK při rotaci klíče. Na rozdíl od [replacePayload] nemění
     * obsah ani výsledek validace — mění se jen to, čím je payload zabalený.
     */
    fun reencrypt(
        orgId: OrganizationId,
        id: CredentialId,
        dataKeyId: DataKeyId,
        ciphertext: ByteArray,
    ): Boolean

    fun recordValidation(
        orgId: OrganizationId,
        id: CredentialId,
        status: ValidationStatus,
        error: String?,
        at: Instant,
    ): CredentialMeta?

    fun attachToApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    )

    fun detachFromApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ): Boolean

    fun findForApp(
        orgId: OrganizationId,
        appId: AppId,
        purpose: CredentialPurpose,
        type: CredentialType,
    ): CredentialMeta?

    fun delete(
        orgId: OrganizationId,
        id: CredentialId,
    ): Boolean
}

data class NewChannel(
    val appId: AppId,
    val type: ChannelType,
    val targetRef: String,
    val targetLabel: String? = null,
    val credentialId: CredentialId? = null,
    val locale: MessageLocale = MessageLocale.CS,
    val deliverReviews: Boolean = true,
    val deliverRatings: Boolean = true,
)

interface ChannelRepository {
    fun create(
        orgId: OrganizationId,
        channel: NewChannel,
    ): Channel

    fun findById(
        orgId: OrganizationId,
        id: ChannelId,
    ): Channel?

    fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<Channel>

    fun setEnabled(
        orgId: OrganizationId,
        id: ChannelId,
        enabled: Boolean,
    ): Boolean

    fun delete(
        orgId: OrganizationId,
        id: ChannelId,
    ): Boolean
}

enum class ReviewUpsertOutcome {
    /** Recenzi jsme viděli poprvé. */
    CREATED,

    /** Známé ID, ale změněný obsah — někdo recenzi editoval. */
    UPDATED,

    /** Beze změny; jen jsme posunuli `last_seen_at`. */
    UNCHANGED,
}

data class ReviewUpsertResult(
    val review: Review,
    val outcome: ReviewUpsertOutcome,
    /** U [ReviewUpsertOutcome.UPDATED] popisuje, co přesně se změnilo; jinak prázdné. */
    val changes: Set<ReviewChange> = emptySet(),
) {
    /**
     * Recenze, kterou má smysl poslat do kanálu: buď je nová, nebo ji autor přepsal.
     * Potlačené (pod watermarkem) se nedoručují nikdy.
     *
     * Samotná změna odpovědi vývojáře notifikace není — to, že někdo odpověděl v Play Console,
     * se ve stávající zprávě projeví jako „✅ odpovězeno", ne jako nová zpráva v kanálu.
     */
    fun isNotifiable(): Boolean =
        review.state != ReviewState.SUPPRESSED &&
            when (outcome) {
                ReviewUpsertOutcome.CREATED -> true
                ReviewUpsertOutcome.UPDATED -> changes.any { it != ReviewChange.DEVELOPER_RESPONSE }
                ReviewUpsertOutcome.UNCHANGED -> false
            }
}

interface ReviewRepository {
    /**
     * Idempotentní zápis pozorované recenze. Dedup není seznam zpracovaných ID jako v n8n,
     * ale unikátní klíč `(app_id, platform, store_review_id)` — editace se pozná podle otisku
     * obsahu a uloží se jako další revize.
     *
     * @param initialState stav pro nově založenou recenzi (watermark rozhoduje mezi NEW a SUPPRESSED)
     */
    fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        seenAt: Instant,
        initialState: ReviewState,
    ): ReviewUpsertResult

    fun findById(
        orgId: OrganizationId,
        id: ReviewId,
    ): Review?

    fun findByStoreId(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        storeReviewId: String,
    ): Review?

    fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState> = ReviewState.entries.toSet(),
        limit: Int = 100,
    ): List<Review>

    fun updateState(
        orgId: OrganizationId,
        id: ReviewId,
        state: ReviewState,
    ): Boolean
}

interface ReviewMessageRepository {
    /**
     * Založí záznam ve stavu PENDING pro konkrétní znění recenze. Když už zpráva pro tuhle
     * trojici (recenze, kanál, otisk znění) existuje, vrátí stávající — doručení je tak
     * idempotentní, ale editovaná recenze dostane vlastní zprávu.
     */
    fun claim(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
        contentHash: String,
    ): ReviewMessage

    fun markSent(
        orgId: OrganizationId,
        id: ReviewMessageId,
        conversationId: String?,
        messageId: String?,
        sentAt: Instant,
    ): Boolean

    fun markFailed(
        orgId: OrganizationId,
        id: ReviewMessageId,
        error: String,
    ): Boolean

    /** Poslední odeslaná zpráva v kanálu — tu se při „✅ odpovězeno" upravuje. */
    fun findLatestSent(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ): ReviewMessage?

    fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<ReviewMessage>

    /** Routing příchozí interakce: ze Slack `channel+ts`, resp. Teams activity ID zpět na recenzi. */
    fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage?
}

data class NewReply(
    val reviewId: ReviewId,
    val body: String,
    val source: ReplySource,
    val authorUserId: UserId? = null,
    val authorExternalId: String? = null,
    val authorDisplayName: String? = null,
)

interface ReplyRepository {
    /**
     * Založí odpověď ve stavu PENDING. Když stejný text pro tutéž recenzi už existuje
     * (dvojklik na „Odeslat"), vrátí původní záznam místo druhého zápisu.
     */
    fun create(
        orgId: OrganizationId,
        reply: NewReply,
    ): Reply

    fun markPublished(
        orgId: OrganizationId,
        id: ReplyId,
        publishedAt: Instant,
    ): Boolean

    fun markFailed(
        orgId: OrganizationId,
        id: ReplyId,
        error: String,
    ): Boolean

    fun findById(
        orgId: OrganizationId,
        id: ReplyId,
    ): Reply?

    fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<Reply>

    fun listByStatus(
        orgId: OrganizationId,
        status: ReplyStatus,
        limit: Int = 100,
    ): List<Reply>
}

data class NewRatingSnapshot(
    val appId: AppId,
    val platform: Platform,
    val date: LocalDate,
    val territory: String = "GLOBAL",
    val average: Double?,
    val totalCount: Long?,
    val histogram: Map<Int, Long> = emptyMap(),
    val source: RatingSource,
)

interface RatingSnapshotRepository {
    /** Denní snapshot je idempotentní: opakovaný běh přepíše hodnoty téhož dne. */
    fun upsert(
        orgId: OrganizationId,
        snapshot: NewRatingSnapshot,
        collectedAt: Instant,
    ): RatingSnapshot

    fun findByDate(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        date: LocalDate,
        territory: String = "GLOBAL",
    ): RatingSnapshot?

    /** Historie pro graf v consoli i pro srovnání v přehledu; nejnovější první. */
    fun listRecent(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        territory: String = "GLOBAL",
        limit: Int = 30,
    ): List<RatingSnapshot>
}

/**
 * Které dny už přehled odešel. Bez toho by opakovaný běh jobu poslal digest dvakrát —
 * a ten druhý by ještě ukazoval nulovou deltu, protože srovnávací snapshot je už z dneška.
 */
interface RatingsDigestRepository {
    /**
     * Rezervuje odeslání přehledu. Vrátí `true`, když je den pro tenhle kanál volný;
     * `false` znamená, že už odešel a nic se posílat nemá.
     */
    fun claim(
        orgId: OrganizationId,
        appId: AppId,
        channelId: ChannelId,
        date: LocalDate,
        sentAt: Instant,
    ): Boolean

    fun lastSent(
        orgId: OrganizationId,
        channelId: ChannelId,
    ): LocalDate?
}

interface AuditLogRepository {
    fun append(entry: AuditEntry)

    fun list(
        orgId: OrganizationId,
        limit: Int = 100,
    ): List<AuditEntry>
}

interface FailedJobRepository {
    /** První selhání založí záznam, další jen inkrementují pokusy — DLQ nemá být changelog. */
    fun record(
        taskName: String,
        taskInstance: String,
        orgId: OrganizationId?,
        payload: String?,
        errorClass: String?,
        errorMessage: String?,
        failedAt: Instant,
    ): FailedJob

    fun resolve(
        taskName: String,
        taskInstance: String,
        resolvedAt: Instant,
    ): Boolean

    /** Bez org-scope: ops pohled napříč tenanty. Console si filtruje sama. */
    fun listOpen(limit: Int = 100): List<FailedJob>

    fun listOpenByOrg(
        orgId: OrganizationId,
        limit: Int = 100,
    ): List<FailedJob>
}

interface BackupRunRepository {
    fun record(run: BackupRun): BackupRun

    /** Podklad pro metriku stáří zálohy — když je `null` nebo starý, zálohy nechodí. */
    fun lastSuccessful(): BackupRun?

    fun listRecent(limit: Int = 20): List<BackupRun>
}

/** Zkratka pro logování/audit bez nutnosti tahat `Instant` přes půlku aplikace. */
fun auditEntry(
    orgId: OrganizationId,
    action: String,
    actorType: ActorType = ActorType.SYSTEM,
    actorUserId: UserId? = null,
    actorLabel: String? = null,
    targetType: String? = null,
    targetId: String? = null,
    metadata: Map<String, String> = emptyMap(),
): AuditEntry =
    AuditEntry(
        orgId = orgId,
        actorType = actorType,
        actorUserId = actorUserId,
        actorLabel = actorLabel,
        action = action,
        targetType = targetType,
        targetId = targetId,
        metadata = metadata,
    )
