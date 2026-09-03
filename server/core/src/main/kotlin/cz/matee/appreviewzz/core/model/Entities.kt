package cz.matee.appreviewzz.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class Organization(
    val id: OrganizationId,
    val name: String,
    val slug: String,
    val createdAt: Instant,
    /** Plán se zatím nevynucuje — rozhoduje jen o tom, komu se generuje měsíční report. */
    val plan: OrgPlan = OrgPlan.STARTER,
)

data class User(
    val id: UserId,
    val email: String,
    val displayName: String?,
    val createdAt: Instant,
    /** Správa platformy; `null` u drtivé většiny účtů. Nedává přístup k datům organizací. */
    val platformRole: PlatformRole? = null,
)

data class OrgMembership(
    val orgId: OrganizationId,
    val userId: UserId,
    val role: OrgRole,
    val createdAt: Instant,
)

/**
 * Sledovaná mobilní aplikace. Nastavení, které dnes leží v Google Sheets
 * a v jednotlivých n8n nodech, je tady na jednom místě a validované.
 */
data class App(
    val id: AppId,
    val orgId: OrganizationId,
    val name: String,
    val gpPackageName: String?,
    /**
     * Bucket s reportingem Play Console (`pubsite_prod_…`). Odvodit z balíčku se nedá, klient
     * ho opisuje z Play Console; bez něj se Android hodnocení berou ze scrapu veřejného listingu.
     */
    val gpReportingBucket: String?,
    val ascAppId: String?,
    val locale: MessageLocale,
    val timezone: String,
    /** Recenze starší než tohle se uloží bez notifikace (migrace, připojení staré appky). */
    val notifyFrom: Instant?,
    val aiInstructions: String?,
    /**
     * **Výjimka** od platformní výchozí hodnoty, ne nastavení appky. `null` (běžný stav)
     * znamená „stahuj tak často, jak řekne platforma" — efektivní číslo skládá
     * `IngestPolicy`, protože interval je knob na náš provoz, ne klientova preference.
     */
    val ingestIntervalMinutes: Int?,
    val dailyDigestAt: LocalTime,
    /** ISO den v týdnu (1 = pondělí), kdy chodí týdenní rozbor; čas se bere z [dailyDigestAt]. */
    val weeklyDigestDay: Int = 1,
    val enabled: Boolean,
    val createdAt: Instant,
) {
    init {
        require(gpPackageName != null || ascAppId != null) {
            "App $id nemá ani Google Play package, ani App Store ID"
        }
    }

    fun platforms(): Set<Platform> =
        buildSet {
            if (gpPackageName != null) add(Platform.ANDROID)
            if (ascAppId != null) add(Platform.IOS)
        }

    /** Čím se appka jmenuje v daném storu — vstup do konektoru. */
    fun storeIdentifier(platform: Platform): String? =
        when (platform) {
            Platform.ANDROID -> gpPackageName
            Platform.IOS -> ascAppId
        }
}

/** Co se o credentialu smí říct nahlas. Payload je jen ve vaultu, nikdy tady. */
data class CredentialMeta(
    val id: CredentialId,
    val orgId: OrganizationId,
    val type: CredentialType,
    val label: String,
    val fingerprint: String,
    /** Neutrální nápověda pro člověka v consoli — issuer ID, client_email, název workspace. */
    val hint: String?,
    val origin: CredentialOrigin,
    val validationStatus: ValidationStatus,
    val validationError: String?,
    val validatedAt: Instant?,
    val createdAt: Instant,
)

data class Channel(
    val id: ChannelId,
    val orgId: OrganizationId,
    val appId: AppId,
    val type: ChannelType,
    val credentialId: CredentialId?,
    /** Slack channel ID, resp. Teams conversation ID. */
    val targetRef: String,
    val targetLabel: String?,
    val locale: MessageLocale,
    val deliverReviews: Boolean,
    val deliverRatings: Boolean,
    /** Týdenní rozbory a alerty na výkyv (F8) — třetí druh zprávy vedle recenzí a hodnocení. */
    val deliverAnalyses: Boolean = true,
    val enabled: Boolean,
)

/**
 * Kanonická recenze napříč storey. `contentHash` drží otisk toho, co uživatel vidí —
 * jeho změna znamená, že recenzi někdo editoval.
 */
data class Review(
    val id: ReviewId,
    val orgId: OrganizationId,
    val appId: AppId,
    val platform: Platform,
    val storeReviewId: String,
    val authorName: String?,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val locale: String?,
    val territory: String?,
    val appVersion: String?,
    val device: String?,
    val submittedAt: Instant,
    val storeUpdatedAt: Instant?,
    val contentHash: String,
    val developerResponseBody: String?,
    val developerResponseAt: Instant?,
    val state: ReviewState,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)

data class ReviewMessage(
    val id: ReviewMessageId,
    val orgId: OrganizationId,
    val reviewId: ReviewId,
    val channelId: ChannelId,
    val providerConversationId: String?,
    val providerMessageId: String?,
    val status: MessageStatus,
    val error: String?,
    val sentAt: Instant?,
    /** Znění recenze, kvůli kterému zpráva vznikla — editace dostane vlastní zprávu. */
    val contentHash: String,
    val createdAt: Instant,
)

data class Reply(
    val id: ReplyId,
    val orgId: OrganizationId,
    val reviewId: ReviewId,
    val body: String,
    val bodyHash: String,
    val authorUserId: UserId?,
    val authorExternalId: String?,
    val authorDisplayName: String?,
    val source: ReplySource,
    val status: ReplyStatus,
    val error: String?,
    val publishedAt: Instant?,
    val createdAt: Instant,
)

data class RatingSnapshot(
    val id: RatingSnapshotId,
    val orgId: OrganizationId,
    val appId: AppId,
    val platform: Platform,
    val date: LocalDate,
    val territory: String,
    val average: Double?,
    val totalCount: Long?,
    /** Počty hodnocení po hvězdách 1..5; zdroje bez histogramu nechávají prázdné. */
    val histogram: Map<Int, Long>,
    val source: RatingSource,
    val collectedAt: Instant,
)

data class AuditEntry(
    val orgId: OrganizationId,
    val actorType: ActorType,
    val actorUserId: UserId?,
    val actorLabel: String?,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
)

data class FailedJob(
    val id: FailedJobId,
    val orgId: OrganizationId?,
    val taskName: String,
    val taskInstance: String,
    val payload: String?,
    val errorClass: String?,
    val errorMessage: String?,
    val attempts: Int,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val resolvedAt: Instant?,
)

/**
 * Historie záloh databáze. Je to jediná stopa, ze které se dá poznat, že zálohy **přestaly
 * chodit** — proto se zapisuje i neúspěšný běh a proto se z ní počítá stáří poslední úspěšné
 * zálohy do metrik.
 */
data class BackupRun(
    val id: BackupRunId,
    val startedAt: Instant,
    val finishedAt: Instant,
    val status: BackupStatus,
    /** Kam se dump uložil (`s3://bucket/klíč`, `file:///cesta`); u selhání `null`. */
    val location: String?,
    val sizeBytes: Long?,
    /** SHA-256 dumpu — při obnově se ověřuje, že se soubor cestou nezměnil. */
    val checksum: String?,
    val error: String?,
)

/**
 * Výklad jedné recenze (F8): co v ní je, ne jen že přišla.
 *
 * Váže se na **znění** recenze — `contentHash` je otisk textu, ze kterého vznikl. Když
 * autor recenzi přepíše, otisk se rozejde a výklad se počítá znovu; totéž po změně
 * taxonomie. Bez toho by v grafu ležela vedle sebe čísla ze dvou různých pravítek.
 */
data class ReviewInsight(
    val reviewId: ReviewId,
    val orgId: OrganizationId,
    val appId: AppId,
    val contentHash: String,
    val taxonomyVersion: String,
    val promptVersion: String,
    /** Model, který výklad vyrobil; `"rules"` u recenzí bez textu (sentiment z hvězd). */
    val model: String,
    val sentiment: OverallSentiment,
    val type: ReviewType,
    val urgency: Urgency,
    /** Jazyk textu podle modelu (BCP-47). Store hlásí jazyk zařízení, což je něco jiného. */
    val language: String?,
    /** Překlad do jazyka týmu (F8.3); `null`, dokud se překládání nezapne. */
    val translation: String?,
    val topics: List<TopicMention>,
    val analyzedAt: Instant,
)

/**
 * Jedno téma v recenzi. Multi-label je záměr: jedna recenze mluví o ceně i o pádech
 * a sentiment se liší téma od tématu, takže součet podílů přesahuje 100 %.
 */
data class TopicMention(
    /** Klíč základní taxonomie, nebo `custom:<uuid>` u vlastního tématu aplikace. */
    val key: String,
    val sentiment: TopicSentiment,
    /** Doslovný úryvek recenze ověřený jako podřetězec; `null`, když se ověřit nedal. */
    val quote: String?,
)

/**
 * Vlastní téma aplikace. Popis jde doslova do promptu, proto anglicky — model taguje
 * v původním jazyce recenze proti anglické taxonomii.
 */
data class AppTopic(
    val id: AppTopicId,
    val orgId: OrganizationId,
    val appId: AppId,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val createdAt: Instant,
) {
    /** Klíč, pod kterým téma vystupuje ve výkladu i ve filtrech. */
    val key: String get() = Topic.CUSTOM_PREFIX + id.value
}
