package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.ReviewArchiveContext
import cz.matee.appreviewzz.core.port.ReviewArchiveSource
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewTimeKey
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Proč se import nespustil pro celou appku. Obojí je běžný provozní stav, ne chyba. */
enum class HistoryAppSkipReason {
    NOT_FOUND,
    DISABLED,
}

/** Proč se historie nedotáhla pro jednu platformu, zatímco druhá mohla běžet. */
enum class HistoryPlatformSkipReason {
    /** Proces nemá pro tuhle platformu archivní zdroj (role bez konektorů). */
    NO_SOURCE,

    /** Klient nepřipojil klíč k recenzím. */
    MISSING_CREDENTIAL,

    /**
     * Android bez reportingového bucketu. Jinou cestou k historii Play Storu se nedostaneme —
     * `reviews.list` vrací týden a nic víc.
     */
    NO_BUCKET,
}

sealed interface PlatformHistoryImport {
    val platform: Platform

    data class Imported(
        override val platform: Platform,
        /** Kolik řádků archiv za období vrátil. */
        val fetched: Int,
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        /** Řádky, které v databázi už jsou pod ID z API — poznané podle času odeslání. */
        val alreadyKnown: Int,
    ) : PlatformHistoryImport

    data class Skipped(
        override val platform: Platform,
        val reason: HistoryPlatformSkipReason,
    ) : PlatformHistoryImport

    data class Failed(
        override val platform: Platform,
        val kind: StoreErrorKind,
        val message: String,
    ) : PlatformHistoryImport {
        val isRetryable: Boolean
            get() = kind == StoreErrorKind.RATE_LIMITED || kind == StoreErrorKind.TRANSIENT
    }
}

data class HistoryImportReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val platforms: List<PlatformHistoryImport> = emptyList(),
    val skipped: HistoryAppSkipReason? = null,
    val since: Instant? = null,
    val until: Instant? = null,
) {
    val created: Int get() = platforms.filterIsInstance<PlatformHistoryImport.Imported>().sumOf { it.created }
    val fetched: Int get() = platforms.filterIsInstance<PlatformHistoryImport.Imported>().sumOf { it.fetched }
    val alreadyKnown: Int get() = platforms.filterIsInstance<PlatformHistoryImport.Imported>().sumOf { it.alreadyKnown }

    val failures: List<PlatformHistoryImport.Failed>
        get() = platforms.filterIsInstance<PlatformHistoryImport.Failed>()

    val isRetryable: Boolean get() = failures.any { it.isRetryable }

    fun failureSummary(): String = failures.joinToString { "${it.platform}: ${it.message}" }
}

/**
 * Import historie recenzí (A10).
 *
 * Vzniklo z jednoho zjištění: appka přidaná dnes nemá **co rozebírat**. Google Play API vrací
 * jen ~týden zpět a jen recenze s textem, takže „rozbor za poslední měsíc" by u nové appky
 * byl prázdný list. Historie se proto bere odjinud než běžný ingest — u Androidu z měsíčních
 * exportů v reportingovém bucketu, u iOS hlubším stránkováním App Store Connectu.
 *
 * Tři pravidla, na kterých to celé stojí:
 *
 * - **Archiv se nečte až do teď.** Poslední týden drží ingest z API, který má jména autorů
 *   a ID, na která jde odpovědět. Kdyby import sáhl do jeho okna, tytéž recenze by u Androidu
 *   přišly podruhé pod `csv:` ID — klíč `(app, platform, store_review_id)` je nespáruje.
 * - **Na hranici se páruje časem odeslání.** Recenzi, kterou API stáhlo, když byla čerstvá,
 *   archiv vrátí i za rok. Bez porovnání času by po roce přibyla znovu.
 * - **Nic z importu se nedoručuje.** Jsou to data stará dny až roky a část z nich jsou
 *   hodnocení bez textu, ke kterým není co napsat. Zakládají se rovnou jako
 *   [ReviewState.SUPPRESSED], bez ohledu na watermark.
 */
class ImportReviewHistoryUseCase(
    private val apps: AppRepository,
    private val credentials: CredentialRepository,
    private val reviews: ReviewRepository,
    private val secrets: SecretResolver,
    archives: List<ReviewArchiveSource>,
    private val clock: Clock = Clock.System,
) {
    private val sourceByPlatform = archives.associateBy { it.platform }

    /**
     * @param months kolik měsíců zpět. `null` je průběžný běh (poslední dva měsíce) — ten
     *   jede denně kvůli hodnocením bez textu, která přes API nepřijdou nikdy.
     */
    suspend fun run(
        orgId: OrganizationId,
        appId: AppId,
        months: Int? = null,
    ): HistoryImportReport {
        val app =
            apps.findById(orgId, appId)
                ?: return HistoryImportReport(orgId, appId, skipped = HistoryAppSkipReason.NOT_FOUND)
        if (!app.enabled) return HistoryImportReport(orgId, appId, skipped = HistoryAppSkipReason.DISABLED)

        val until = clock.now() - API_WINDOW
        val since = until - DAYS_PER_MONTH * (months ?: RECENT_MONTHS)
        val results = Platform.entries.filter { it in app.platforms() }.map { importPlatform(app, it, since, until) }

        logger.info { "Import historie ${app.name} (${app.id}): ${results.joinToString { it.describe() }}" }
        return HistoryImportReport(orgId, appId, platforms = results, since = since, until = until)
    }

    private suspend fun importPlatform(
        app: App,
        platform: Platform,
        since: Instant,
        until: Instant,
    ): PlatformHistoryImport {
        val source =
            sourceByPlatform[platform]
                ?: return PlatformHistoryImport.Skipped(platform, HistoryPlatformSkipReason.NO_SOURCE)
        val identifier =
            app.storeIdentifier(platform)
                ?: return PlatformHistoryImport.Skipped(platform, HistoryPlatformSkipReason.NO_SOURCE)
        val credential =
            credentials.findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, credentialType(platform))
                ?: return PlatformHistoryImport.Skipped(platform, HistoryPlatformSkipReason.MISSING_CREDENTIAL)
        if (platform == Platform.ANDROID && app.gpReportingBucket == null) {
            return PlatformHistoryImport.Skipped(platform, HistoryPlatformSkipReason.NO_BUCKET)
        }

        val observed =
            try {
                source.fetchArchive(
                    ReviewArchiveContext(
                        appIdentifier = identifier,
                        credential = secrets.resolve(app.orgId, credential.id),
                        reportingBucket = app.gpReportingBucket,
                        since = since,
                        until = until,
                    ),
                )
            } catch (error: StoreConnectorException) {
                logger.warn { "Historie ${app.id}/$platform selhala (${error.kind}): ${error.message}" }
                return PlatformHistoryImport.Failed(platform, error.kind, error.message.orEmpty())
            }

        return store(app, platform, observed, since, until)
    }

    private fun store(
        app: App,
        platform: Platform,
        observed: List<ObservedReview>,
        since: Instant,
        until: Instant,
    ): PlatformHistoryImport.Imported {
        val known =
            KnownReviews(
                reviews.listTimeKeys(app.orgId, app.id, platform, since - MATCH_TOLERANCE, until + MATCH_TOLERANCE),
            )
        val seenAt = clock.now()
        var created = 0
        var updated = 0
        var unchanged = 0
        var alreadyKnown = 0

        observed.sortedBy { it.submittedAt }.forEach { review ->
            if (known.matchesOtherSource(review)) {
                alreadyKnown++
                return@forEach
            }
            // Vždycky SUPPRESSED: historie ani hodnocení bez textu nemají co dělat v kanálu.
            val result = reviews.upsert(app.orgId, app.id, review, seenAt, ReviewState.SUPPRESSED)
            when (result.outcome) {
                ReviewUpsertOutcome.CREATED -> created++
                ReviewUpsertOutcome.UPDATED -> updated++
                ReviewUpsertOutcome.UNCHANGED -> unchanged++
            }
        }

        return PlatformHistoryImport.Imported(
            platform = platform,
            fetched = observed.size,
            created = created,
            updated = updated,
            unchanged = unchanged,
            alreadyKnown = alreadyKnown,
        )
    }

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }

    /**
     * Recenze, které pro tuhle appku už známe, poskládané podle vteřiny odeslání.
     *
     * Porovnává se **čas, ne obsah**: archiv Androidu nese jiné ID než API a text se dá
     * editovat, kdežto okamžik odeslání se nemění. Tolerance jedné vteřiny je tu proto, že
     * API dává čas v sekundách a nanosekundách, kdežto export v milisekundách. Když se dvě
     * recenze přece jen trefí do téže vteřiny, přijdeme o jeden řádek historie — pořád lepší
     * než tatáž recenze dvakrát v inboxu.
     */
    private class KnownReviews(
        keys: List<ReviewTimeKey>,
    ) {
        private val bySecond: Map<Long, List<ReviewTimeKey>> = keys.groupBy { it.submittedAt.epochSeconds }

        fun matchesOtherSource(review: ObservedReview): Boolean {
            // Editovanou recenzi má Android z API pod časem *poslední změny*, archiv pod časem
            // odeslání — proto se zkouší obojí.
            val candidates = listOfNotNull(review.submittedAt, review.storeUpdatedAt)
            return candidates.any { time ->
                (time.epochSeconds - 1..time.epochSeconds + 1).any { second ->
                    bySecond[second].orEmpty().any { it.storeReviewId != review.storeReviewId }
                }
            }
        }
    }

    companion object {
        /** Dokud recenze spadá do okna `reviews.list`, patří ingestu z API. */
        val API_WINDOW = 7.days

        /** Průběžný běh: aktuální a předchozí měsíc, stejně jako u exportu hodnocení. */
        const val RECENT_MONTHS = 2

        private val DAYS_PER_MONTH = 31.days
        private val MATCH_TOLERANCE = 1.seconds
    }
}

private fun PlatformHistoryImport.describe(): String =
    when (this) {
        is PlatformHistoryImport.Imported ->
            "$platform fetched=$fetched new=$created updated=$updated unchanged=$unchanged known=$alreadyKnown"

        is PlatformHistoryImport.Skipped -> "$platform skipped=$reason"
        is PlatformHistoryImport.Failed -> "$platform failed=$kind"
    }
