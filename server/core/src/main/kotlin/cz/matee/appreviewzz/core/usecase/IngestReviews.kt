package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Proč se pro appku ingest vůbec nespustil. Obojí je normální provozní stav, ne chyba. */
enum class AppSkipReason {
    /** Appka mezi naplánováním a během zmizela (smazaná organizace, smazaná appka). */
    NOT_FOUND,

    /** Klient si ji v consoli vypnul. */
    DISABLED,
}

/** Proč se ingest nespustil pro jednu platformu, zatímco druhá běžet mohla. */
enum class PlatformSkipReason {
    /** Appka má identifikátor storu, ale konektor pro něj v tomhle procesu není. */
    NO_CONNECTOR,

    /** Klient klíč k recenzím zatím nepřipojil, nebo ho odpojil. */
    MISSING_CREDENTIAL,
}

/** Výsledek ingestu jedné platformy. Platformy jsou na sobě nezávislé — iOS spadne, Android doběhne. */
sealed interface PlatformIngest {
    val platform: Platform

    data class Ingested(
        override val platform: Platform,
        /** Kolik recenzí store vrátil (ne kolik jich bylo nových). */
        val fetched: Int,
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        /** Podmnožina [created]: založené pod watermarkem, tedy rovnou bez notifikace. */
        val suppressed: Int,
        /** Recenze, u kterých se odpověď objevila ve storu mimo náš systém (Play Console, ASC). */
        val answeredInStore: Int,
        /** Co má smysl poslat do kanálů, v pořadí, v jakém to vzniklo ve storu. */
        val notifiable: List<ReviewUpsertResult>,
    ) : PlatformIngest

    data class Skipped(
        override val platform: Platform,
        val reason: PlatformSkipReason,
    ) : PlatformIngest

    data class Failed(
        override val platform: Platform,
        val kind: StoreErrorKind,
        val message: String,
    ) : PlatformIngest {
        /** `AUTH` a spol. chtějí člověka; opakovat má smysl jen limit a výpadek. */
        val isRetryable: Boolean
            get() = kind == StoreErrorKind.RATE_LIMITED || kind == StoreErrorKind.TRANSIENT
    }
}

data class IngestReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val platforms: List<PlatformIngest>,
    val appSkipped: AppSkipReason? = null,
) {
    /** Recenze k doručení napříč platformami, seřazené podle času vzniku ve storu. */
    val notifiable: List<ReviewUpsertResult>
        get() =
            platforms
                .filterIsInstance<PlatformIngest.Ingested>()
                .flatMap { it.notifiable }
                .sortedBy { it.review.submittedAt }

    val failures: List<PlatformIngest.Failed>
        get() = platforms.filterIsInstance<PlatformIngest.Failed>()

    /** Podklad pro scheduler: má smysl běh zopakovat, nebo to jen zaplní DLQ? */
    val isRetryable: Boolean
        get() = failures.any { it.isRetryable }
}

/**
 * Ingest recenzí jedné aplikace: **fetch → dedup → watermark → stav** (plán §5.5).
 *
 * Use-case žije v jádru a zná jen porty — konektory, vault ani databázi nevidí. Proti dnešnímu
 * n8n řešení tu jsou tři věci navíc:
 *
 * - **Dedup je upsert** nad `(app, platform, store_review_id)`, ne seznam zpracovaných ID, takže
 *   editace recenze nezapadne (dnes zapadne) a opakovaný běh nic nezduplikuje.
 * - **Watermark `notify_from`** rozhoduje o stavu už při zakládání: starší recenze se uloží kvůli
 *   historii, ale do kanálu nejdou. Připojení staré appky tak kanál nezaplaví.
 * - **Odpověď nalezená ve storu** (někdo odpověděl v Play Console) recenzi rovnou překlopí do
 *   `REPLIED` místo toho, aby se poslala jako „nová".
 *
 * Selhání storu se nepropaguje ven jako výjimka — vrací se v [IngestReport], protože o retry
 * rozhoduje volající (scheduler) podle druhu chyby, ne stack trace.
 */
class IngestReviewsUseCase(
    private val apps: AppRepository,
    private val credentials: CredentialRepository,
    private val reviews: ReviewRepository,
    private val secrets: SecretResolver,
    private val audit: AuditLogRepository,
    sources: List<ReviewSource>,
    private val clock: Clock = Clock.System,
) {
    private val sourceByPlatform: Map<Platform, ReviewSource> = sources.associateBy { it.platform }

    init {
        require(sourceByPlatform.size == sources.size) {
            "Pro jednu platformu je zaregistrovaný víc než jeden ReviewSource"
        }
    }

    suspend fun ingest(
        orgId: OrganizationId,
        appId: AppId,
    ): IngestReport {
        val app =
            apps.findById(orgId, appId)
                ?: return IngestReport(orgId, appId, emptyList(), AppSkipReason.NOT_FOUND)
        if (!app.enabled) return IngestReport(orgId, appId, emptyList(), AppSkipReason.DISABLED)

        // Pevné pořadí platforem (ne pořadí v Set): report i logy pak jdou porovnávat mezi běhy.
        val results = Platform.entries.filter { it in app.platforms() }.map { ingestPlatform(app, it) }
        logger.info { "Ingest ${app.name} (${app.id}): ${results.joinToString { it.describe() }}" }
        return IngestReport(orgId, appId, results)
    }

    private suspend fun ingestPlatform(
        app: App,
        platform: Platform,
    ): PlatformIngest {
        val source =
            sourceByPlatform[platform]
                ?: return PlatformIngest.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        val identifier =
            app.storeIdentifier(platform)
                ?: return PlatformIngest.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        val credential =
            credentials.findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, credentialType(platform))
                ?: return PlatformIngest.Skipped(platform, PlatformSkipReason.MISSING_CREDENTIAL)

        val observed =
            try {
                // Credential se rozbaluje až tady, těsně před použitím, a dál než do konektoru nejde.
                val context = StoreContext(identifier, secrets.resolve(app.orgId, credential.id))
                source.fetchReviews(context)
            } catch (error: StoreConnectorException) {
                if (error.kind == StoreErrorKind.AUTH) invalidate(credential, error)
                logger.warn { "Ingest ${app.id}/$platform selhal (${error.kind}): ${error.message}" }
                return PlatformIngest.Failed(platform, error.kind, error.message.orEmpty())
            }

        revalidate(credential)
        return store(app, platform, observed)
    }

    private fun store(
        app: App,
        platform: Platform,
        observed: List<ObservedReview>,
    ): PlatformIngest.Ingested {
        val seenAt = clock.now()
        var created = 0
        var updated = 0
        var unchanged = 0
        var suppressed = 0
        var answeredInStore = 0
        val notifiable = mutableListOf<ReviewUpsertResult>()

        // Chronologicky: v kanálu má starší recenze přistát dřív než novější.
        observed.sortedBy { it.submittedAt }.forEach { review ->
            val initialState = if (isUnderWatermark(app, review)) ReviewState.SUPPRESSED else ReviewState.NEW
            val result = reviews.upsert(app.orgId, app.id, review, seenAt, initialState)
            when (result.outcome) {
                ReviewUpsertOutcome.CREATED -> {
                    created++
                    if (result.review.state == ReviewState.SUPPRESSED) suppressed++
                }

                ReviewUpsertOutcome.UPDATED -> updated++
                ReviewUpsertOutcome.UNCHANGED -> unchanged++
            }

            when {
                markAnsweredInStore(result) -> answeredInStore++
                result.isNotifiable() -> notifiable += result
            }
        }

        return PlatformIngest.Ingested(
            platform = platform,
            fetched = observed.size,
            created = created,
            updated = updated,
            unchanged = unchanged,
            suppressed = suppressed,
            answeredInStore = answeredInStore,
            notifiable = notifiable,
        )
    }

    /**
     * Recenze starší než watermark se ukládá bez notifikace. Rozhoduje čas vzniku ve storu,
     * ne čas editace — jinak by stará recenze prošla watermarkem jen proto, že ji autor přepsal.
     */
    private fun isUnderWatermark(
        app: App,
        review: ObservedReview,
    ): Boolean {
        val notifyFrom = app.notifyFrom ?: return false
        return review.submittedAt < notifyFrom
    }

    /**
     * Odpověď, která se ve storu objevila mimo náš systém (Play Console, App Store Connect).
     * Recenze je tím pádem vyřízená a nemá cenu ji posílat do kanálu jako novou.
     *
     * Pozor na záměnu s opačným případem: když autor po naší odpovědi recenzi přepíše, přijde
     * změna textu nebo hvězdiček **spolu** s odpovědí, kterou už známe — a to je věc, kterou tým
     * vidět chce. Proto se sem počítá jen běh, ve kterém je odpověď jedinou změnou.
     *
     * @return true, když se recenze právě překlopila do [ReviewState.REPLIED]
     */
    private fun markAnsweredInStore(result: ReviewUpsertResult): Boolean {
        val review = result.review
        if (review.developerResponseBody == null) return false
        val responseIsTheNews =
            when (result.outcome) {
                ReviewUpsertOutcome.CREATED -> true
                ReviewUpsertOutcome.UPDATED -> result.changes == setOf(ReviewChange.DEVELOPER_RESPONSE)
                ReviewUpsertOutcome.UNCHANGED -> false
            }
        if (!responseIsTheNews || review.state !in REPLYABLE_STATES) return false

        reviews.updateState(review.orgId, review.id, ReviewState.REPLIED)
        return true
    }

    /** Klíč přestal fungovat: console to musí ukázat dřív, než se klient začne divit. */
    private fun invalidate(
        credential: CredentialMeta,
        error: StoreConnectorException,
    ) {
        if (credential.validationStatus == ValidationStatus.INVALID) return
        val message = error.message.orEmpty()
        credentials.recordValidation(credential.orgId, credential.id, ValidationStatus.INVALID, message, clock.now())
        audit.append(
            auditEntry(
                orgId = credential.orgId,
                action = "credential.validation_failed",
                targetType = "credential",
                targetId = credential.id.toString(),
                metadata = mapOf("kind" to error.kind.name),
            ),
        )
    }

    /**
     * Povedený fetch je důkaz, že klíč funguje. Zapisuje se jen při změně stavu — jinak by
     * každý běh každých 30 minut přepisoval řádek credentialu a plnil audit log.
     */
    private fun revalidate(credential: CredentialMeta) {
        if (credential.validationStatus == ValidationStatus.VALID) return
        credentials.recordValidation(credential.orgId, credential.id, ValidationStatus.VALID, null, clock.now())
        audit.append(
            auditEntry(
                orgId = credential.orgId,
                action = "credential.validation_recovered",
                targetType = "credential",
                targetId = credential.id.toString(),
            ),
        )
    }

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }

    private companion object {
        /** Stavy, ze kterých dává smysl přejít do REPLIED. IGNORED i SUPPRESSED zůstávají, kde jsou. */
        val REPLYABLE_STATES = setOf(ReviewState.NEW, ReviewState.NOTIFIED, ReviewState.UPDATED)
    }
}

private fun PlatformIngest.describe(): String =
    when (this) {
        is PlatformIngest.Ingested ->
            "$platform fetched=$fetched new=$created updated=$updated unchanged=$unchanged " +
                "suppressed=$suppressed answered=$answeredInStore notify=${notifiable.size}"

        is PlatformIngest.Skipped -> "$platform skipped=$reason"
        is PlatformIngest.Failed -> "$platform failed=$kind"
    }
