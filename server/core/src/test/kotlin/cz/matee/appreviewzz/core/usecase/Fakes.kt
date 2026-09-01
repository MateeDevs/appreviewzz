package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialOrigin
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppSettings
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoredCredential
import cz.matee.appreviewzz.core.port.ValidationOutcome
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Náhrady portů pro test orchestrace. Dedup ani SQL se tady nesimulují — od toho je
 * `IngestPipelineTest` nad skutečnými repozitáři; tenhle test zajímá rozhodování use-casu.
 */
internal object Ingest {
    val now: Instant = Instant.parse("2026-08-19T12:00:00Z")

    fun app(
        orgId: OrganizationId,
        gpPackageName: String? = "cz.matee.islegrow",
        ascAppId: String? = null,
        notifyFrom: Instant? = null,
        enabled: Boolean = true,
        /** Týden před [now]: appka je zaběhlá, takže o notifikaci nerozhoduje čas jejího přidání. */
        createdAt: Instant = now.minus(kotlin.time.Duration.parse("7d")),
    ): App =
        App(
            id = AppId(Uuid.random()),
            orgId = orgId,
            name = "IsleGrow",
            gpPackageName = gpPackageName,
            gpReportingBucket = null,
            ascAppId = ascAppId,
            locale = MessageLocale.CS,
            timezone = "Europe/Prague",
            notifyFrom = notifyFrom,
            aiInstructions = null,
            ingestIntervalMinutes = 30,
            dailyDigestAt = LocalTime(8, 30),
            enabled = enabled,
            createdAt = createdAt,
        )

    fun credential(
        orgId: OrganizationId,
        type: CredentialType,
        status: ValidationStatus = ValidationStatus.VALID,
    ): CredentialMeta =
        CredentialMeta(
            id = CredentialId(Uuid.random()),
            orgId = orgId,
            type = type,
            label = "klíč",
            fingerprint = "sha256:abcd",
            hint = null,
            origin = CredentialOrigin.UPLOADED,
            validationStatus = status,
            validationError = null,
            validatedAt = null,
            createdAt = now,
        )

    fun observed(
        storeReviewId: String,
        platform: Platform = Platform.ANDROID,
        submittedAt: Instant = Instant.parse("2026-08-19T11:00:00Z"),
        developerResponseBody: String? = null,
    ): ObservedReview =
        ObservedReview(
            platform = platform,
            storeReviewId = storeReviewId,
            authorName = "Jana N.",
            starRating = 4,
            title = null,
            body = "Funguje dobře.",
            locale = "cs",
            territory = "CZ",
            appVersion = "3.2.1",
            device = "Pixel 8",
            submittedAt = submittedAt,
            storeUpdatedAt = null,
            developerResponseBody = developerResponseBody,
            developerResponseAt = null,
        )
}

internal class FakeAppRepository(
    private val apps: MutableList<App> = mutableListOf(),
) : AppRepository {
    fun put(app: App): App = app.also { apps += it }

    override fun findById(
        orgId: OrganizationId,
        id: AppId,
    ): App? = apps.firstOrNull { it.orgId == orgId && it.id == id }

    override fun listEnabled(): List<App> = apps.filter { it.enabled }

    override fun findAnyById(id: AppId): App? = apps.firstOrNull { it.id == id }

    override fun listWithIntervalOverride(): List<App> = apps.filter { it.ingestIntervalMinutes != null }

    override fun updateIngestInterval(
        id: AppId,
        minutes: Int?,
    ): App? {
        val index = apps.indexOfFirst { it.id == id }
        if (index < 0) return null
        return apps[index].copy(ingestIntervalMinutes = minutes).also { apps[index] = it }
    }

    override fun listByOrg(orgId: OrganizationId): List<App> = apps.filter { it.orgId == orgId }

    override fun create(
        orgId: OrganizationId,
        app: NewApp,
    ): App = notUsed()

    override fun updateSettings(
        orgId: OrganizationId,
        id: AppId,
        settings: AppSettings,
    ): App? = notUsed()

    override fun delete(
        orgId: OrganizationId,
        id: AppId,
    ): Boolean = notUsed()
}

internal class FakeCredentialRepository : CredentialRepository {
    private val attached = mutableMapOf<Triple<AppId, CredentialPurpose, CredentialType>, CredentialMeta>()
    val validations = mutableListOf<Pair<CredentialId, ValidationStatus>>()

    fun attach(
        appId: AppId,
        purpose: CredentialPurpose,
        meta: CredentialMeta,
    ) {
        attached[Triple(appId, purpose, meta.type)] = meta
    }

    override fun findForApp(
        orgId: OrganizationId,
        appId: AppId,
        purpose: CredentialPurpose,
        type: CredentialType,
    ): CredentialMeta? = attached[Triple(appId, purpose, type)]?.takeIf { it.orgId == orgId }

    override fun recordValidation(
        orgId: OrganizationId,
        id: CredentialId,
        status: ValidationStatus,
        error: String?,
        at: Instant,
    ): CredentialMeta? {
        validations += id to status
        return attached.values.firstOrNull { it.id == id }?.copy(validationStatus = status, validationError = error)
    }

    override fun create(
        orgId: OrganizationId,
        credential: NewCredential,
    ): CredentialMeta = notUsed()

    override fun findMeta(
        orgId: OrganizationId,
        id: CredentialId,
    ): CredentialMeta? = notUsed()

    override fun listByOrg(
        orgId: OrganizationId,
        type: CredentialType?,
    ): List<CredentialMeta> = notUsed()

    override fun loadForDecryption(
        orgId: OrganizationId,
        id: CredentialId,
    ): StoredCredential? = notUsed()

    override fun replacePayload(
        orgId: OrganizationId,
        id: CredentialId,
        credential: NewCredential,
    ): CredentialMeta? = notUsed()

    override fun reencrypt(
        orgId: OrganizationId,
        id: CredentialId,
        dataKeyId: DataKeyId,
        ciphertext: ByteArray,
    ): Boolean = notUsed()

    override fun attachToApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ) = notUsed()

    override fun detachFromApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ): Boolean = notUsed()

    override fun delete(
        orgId: OrganizationId,
        id: CredentialId,
    ): Boolean = notUsed()
}

/**
 * Zapisuje, s čím se upsert volal, a každou recenzi hlásí jako novou. Stavy a dedup
 * ověřuje integrační test nad Postgresem, ne tenhle dvojník.
 */
internal class RecordingReviewRepository : ReviewRepository {
    data class Call(
        val observed: ObservedReview,
        val initialState: ReviewState,
    )

    val calls = mutableListOf<Call>()
    val stateUpdates = mutableListOf<Pair<ReviewId, ReviewState>>()

    override fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        seenAt: Instant,
        initialState: ReviewState,
    ): ReviewUpsertResult {
        calls += Call(observed, initialState)
        val review =
            Review(
                id = ReviewId(Uuid.random()),
                orgId = orgId,
                appId = appId,
                platform = observed.platform,
                storeReviewId = observed.storeReviewId,
                authorName = observed.authorName,
                starRating = observed.starRating,
                title = observed.title,
                body = observed.body,
                locale = observed.locale,
                territory = observed.territory,
                appVersion = observed.appVersion,
                device = observed.device,
                submittedAt = observed.submittedAt,
                storeUpdatedAt = observed.storeUpdatedAt,
                contentHash = observed.contentHash(),
                developerResponseBody = observed.developerResponseBody,
                developerResponseAt = observed.developerResponseAt,
                state = initialState,
                firstSeenAt = seenAt,
                lastSeenAt = seenAt,
            )
        return ReviewUpsertResult(review, ReviewUpsertOutcome.CREATED)
    }

    override fun updateState(
        orgId: OrganizationId,
        id: ReviewId,
        state: ReviewState,
    ): Boolean {
        stateUpdates += id to state
        return true
    }

    override fun findById(
        orgId: OrganizationId,
        id: ReviewId,
    ): Review? = notUsed()

    override fun findByStoreId(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        storeReviewId: String,
    ): Review? = notUsed()

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState>,
        limit: Int,
    ): List<Review> = notUsed()

    override fun listAwaitingStoreReply(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        submittedAfter: Instant,
        submittedBefore: Instant,
        limit: Int,
    ): List<Review> = notUsed()
}

internal class RecordingAuditLog : AuditLogRepository {
    val entries = mutableListOf<AuditEntry>()

    override fun append(entry: AuditEntry) {
        entries += entry
    }

    override fun list(
        orgId: OrganizationId,
        limit: Int,
    ): List<AuditEntry> = entries.filter { it.orgId == orgId }
}

/** Konektor, který místo storu vrací připravený výsledek (nebo připravené selhání). */
internal class FakeReviewSource(
    override val platform: Platform,
    private val response: () -> List<ObservedReview>,
) : ReviewSource {
    var receivedIdentifier: String? = null
        private set
    var receivedSecret: String? = null
        private set

    override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> {
        receivedIdentifier = context.appIdentifier
        receivedSecret = context.credential.value
        return response()
    }

    override suspend fun validate(context: StoreContext): ValidationOutcome = notUsed()
}

/** Ingest si čas bere z hodin, aby šel `last_seen_at` v testu porovnat na rovnost. */
internal fun fixedClock(instant: Instant = Ingest.now): Clock =
    object : Clock {
        override fun now(): Instant = instant
    }

internal fun secretResolver(payload: String = "service-account-json") = SecretResolver { _, _ -> SecretPayload(payload) }

private fun notUsed(): Nothing = error("Metoda se v testu ingestu nepoužívá")
