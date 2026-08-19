package cz.matee.appreviewzz.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class Organization(
    val id: OrganizationId,
    val name: String,
    val slug: String,
    val createdAt: Instant,
)

data class User(
    val id: UserId,
    val email: String,
    val displayName: String?,
    val createdAt: Instant,
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
    val ascAppId: String?,
    val locale: MessageLocale,
    val timezone: String,
    /** Recenze starší než tohle se uloží bez notifikace (migrace, připojení staré appky). */
    val notifyFrom: Instant?,
    val aiInstructions: String?,
    val ingestIntervalMinutes: Int,
    val dailyDigestAt: LocalTime,
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
