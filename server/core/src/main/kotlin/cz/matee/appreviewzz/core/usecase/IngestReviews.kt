package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.PublicReviewSource
import cz.matee.appreviewzz.core.port.ReviewAuthorKey
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.ReviewTimeKey
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

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
        /** Recenze, které už založil import z exportu a teď se přejmenovaly na ID z API. */
        val adopted: Int = 0,
        /** iOS recenze, které autor přepsal a App Store je vydal pod novým ID. */
        val rewritten: Int = 0,
        /** Recenze, které z výpisu storu zmizely — autor je smazal. */
        val removed: Int = 0,
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
 *   historii, ale do kanálu nejdou. Ve výchozím stavu je to čas přidání appky do systému, takže
 *   připojení staré appky kanál nezaplaví, i když watermark nikdo ručně nevyplnil.
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
    /** Veřejné zdroje pro konkurenci (C4). Bez nich se konkurenční appka jen přeskočí. */
    publicSources: List<PublicReviewSource> = emptyList(),
) {
    private val sourceByPlatform: Map<Platform, ReviewSource> = sources.associateBy { it.platform }
    private val publicSourceByPlatform: Map<Platform, PublicReviewSource> = publicSources.associateBy { it.platform }

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
        val identifier =
            app.storeIdentifier(platform)
                ?: return PlatformIngest.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        if (app.competitor) return ingestPublic(app, platform, identifier)
        val source =
            sourceByPlatform[platform]
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
        return store(app, platform, observed, detectRemoved = source.listsWithoutGaps)
    }

    /**
     * Konkurence (C4): veřejný zdroj, žádný credential, žádné ověřování. Chyba storu se
     * hlásí stejně jako u vlastní appky, aby ji bylo vidět v delivery health.
     */
    private suspend fun ingestPublic(
        app: App,
        platform: Platform,
        identifier: String,
    ): PlatformIngest {
        val source =
            publicSourceByPlatform[platform]
                ?: return PlatformIngest.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        val observed =
            try {
                source.fetchPublicReviews(identifier)
            } catch (error: StoreConnectorException) {
                logger.warn { "Veřejný ingest ${app.id}/$platform selhal (${error.kind}): ${error.message}" }
                return PlatformIngest.Failed(platform, error.kind, error.message.orEmpty())
            }
        return store(app, platform, observed)
    }

    private fun store(
        app: App,
        platform: Platform,
        observed: List<ObservedReview>,
        detectRemoved: Boolean = false,
    ): PlatformIngest.Ingested {
        val seenAt = clock.now()
        var created = 0
        var updated = 0
        var unchanged = 0
        var suppressed = 0
        var answeredInStore = 0
        var adopted = 0
        var rewritten = 0
        val notifiable = mutableListOf<ReviewUpsertResult>()
        val archived = archivedTwins(app, platform, observed)
        val originals = rewrittenOriginals(app, platform, observed)

        // Chronologicky: v kanálu má starší recenze přistát dřív než novější.
        observed.sortedBy { it.submittedAt }.forEach { fresh ->
            val original =
                originals[fresh.storeReviewId]?.takeIf { reviews.adopt(app.orgId, app.id, it.storeReviewId, fresh) != null }
            // Přepsaná recenze si nechává původní čas odeslání; čas přepsání jde do storeUpdatedAt.
            // Upsert ji pak vezme jako editaci (UPDATED) — stejně jako na Androidu, kde ID zůstává.
            val review =
                if (original == null) {
                    fresh
                } else {
                    rewritten++
                    fresh.copy(submittedAt = original.submittedAt, storeUpdatedAt = fresh.storeUpdatedAt ?: fresh.submittedAt)
                }
            // Konkurence se nikdy nenotifikuje — slouží rozborům, ne kanálu.
            val initialState =
                if (app.competitor || isUnderWatermark(app, review)) ReviewState.SUPPRESSED else ReviewState.NEW
            val twin = archived.twinOf(review)?.let { reviews.adopt(app.orgId, app.id, it, review) }
            var result = reviews.upsert(app.orgId, app.id, review, seenAt, initialState)
            if (twin != null) {
                adopted++
                result = releaseAdopted(result, initialState)
            }
            when (result.outcome) {
                ReviewUpsertOutcome.CREATED -> {
                    created++
                    if (result.review.state == ReviewState.SUPPRESSED) suppressed++
                }

                ReviewUpsertOutcome.UPDATED -> updated++
                ReviewUpsertOutcome.UNCHANGED -> unchanged++
            }

            when {
                markAnsweredInStore(reviews, result) -> answeredInStore++
                result.isNotifiable() -> notifiable += result
            }
        }

        // Prázdný výpis nic nedokazuje — spíš výpadek než to, že autoři smazali všechno.
        val removed =
            if (detectRemoved && observed.isNotEmpty()) {
                val listedSince = observed.minOf { it.storeUpdatedAt ?: it.submittedAt }
                reviews.markUnlistedRemoved(app.orgId, app.id, platform, listedSince, seenAt)
            } else {
                0
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
            adopted = adopted,
            rewritten = rewritten,
            removed = removed,
        )
    }

    /**
     * Přepsané iOS recenze: nové ID ze storu → původní řádek u nás.
     *
     * App Store po editaci vydá recenzi pod novým ID a s novým `createdDate`, starou odpověď
     * k ní ale nechá. Bez párování vznikne druhá recenze s čerstvým datem a odpovědí starou
     * třeba měsíce, a původní zůstane viset vedle ní. Páruje se podle autora — přezdívka je
     * v App Storu unikátní a jeden účet má k aplikaci jedinou recenzi. Pojistky proti spojení
     * dvou různých recenzí: původní ID store už nevrací, území sedí a původní je starší.
     */
    private fun rewrittenOriginals(
        app: App,
        platform: Platform,
        observed: List<ObservedReview>,
    ): Map<String, ReviewAuthorKey> {
        if (platform != Platform.IOS) return emptyMap()
        val authors = observed.mapNotNullTo(mutableSetOf()) { it.authorName }
        val known = reviews.listAuthorKeys(app.orgId, app.id, platform, authors)
        if (known.isEmpty()) return emptyMap()
        val knownIds = known.mapTo(mutableSetOf()) { it.storeReviewId }
        val visibleIds = observed.mapTo(mutableSetOf()) { it.storeReviewId }
        val goneByAuthor = known.filter { it.storeReviewId !in visibleIds }.groupBy { it.authorName }
        return observed
            .filter { it.storeReviewId !in knownIds }
            .mapNotNull { fresh ->
                goneByAuthor[fresh.authorName]
                    .orEmpty()
                    .filter { it.territory == fresh.territory && it.submittedAt < fresh.submittedAt }
                    .maxByOrNull { it.submittedAt }
                    ?.let { fresh.storeReviewId to it }
            }.toMap()
    }

    /**
     * Recenze, které založil import z exportu dřív, než je stihlo API. Import čte export až
     * do dneška, takže čerstvá recenze s textem může přijít oběma cestami — a ID se nepotkají
     * (`csv:<uuid>` proti `gp:AOqpTO…`). Páruje se proto časem, jedním dotazem na běh.
     */
    private fun archivedTwins(
        app: App,
        platform: Platform,
        observed: List<ObservedReview>,
    ): ArchivedTwins {
        if (observed.isEmpty()) return ArchivedTwins(emptyList())
        val times = observed.map { it.submittedAt }
        return ArchivedTwins(
            reviews.listArchivedTimeKeys(
                app.orgId,
                app.id,
                platform,
                times.min() - ArchivedTwins.TOLERANCE,
                times.max() + ArchivedTwins.TOLERANCE,
            ),
        )
    }

    /**
     * Převzatá recenze z archivu je založená jako potlačená — import nikdy nic nedoručuje.
     * Když ji ale API vidí nad watermarkem, je to čerstvá recenze, o které kanál ještě
     * neví, a patří do něj stejně, jako kdyby ji API stáhlo první.
     */
    private fun releaseAdopted(
        result: ReviewUpsertResult,
        initialState: ReviewState,
    ): ReviewUpsertResult {
        val review = result.review
        if (review.state != ReviewState.SUPPRESSED || initialState != ReviewState.NEW) return result
        reviews.updateState(review.orgId, review.id, ReviewState.NEW)
        return ReviewUpsertResult(review.copy(state = ReviewState.NEW), ReviewUpsertOutcome.CREATED)
    }

    /**
     * Recenze starší než watermark se ukládá bez notifikace. Rozhoduje čas vzniku ve storu,
     * ne čas editace — jinak by stará recenze prošla watermarkem jen proto, že ji autor přepsal.
     *
     * Appka bez nastaveného watermarku (řádky z doby, kdy se hodnota nevyplňovala) se řídí časem
     * svého založení: co bylo ve storu dřív, než jsme appku vůbec znali, není novinka. Bez toho
     * první ingest vysype do kanálu celou historii — přesně to, kvůli čemu watermark existuje.
     */
    private fun isUnderWatermark(
        app: App,
        review: ObservedReview,
    ): Boolean {
        val notifyFrom = app.notifyFrom ?: app.createdAt
        return review.submittedAt < notifyFrom
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
}

/**
 * Archivní recenze poskládané podle vteřiny. API dává u Androidu čas **poslední změny**,
 * export čas odeslání a změny zvlášť — proto se zkouší oba. Tolerance jedné vteřiny kryje
 * rozdíl mezi milisekundami exportu a sekundami s nanosekundami z API.
 */
private class ArchivedTwins(
    keys: List<ReviewTimeKey>,
) {
    private val bySecond: Map<Long, List<ReviewTimeKey>> =
        keys
            .flatMap { key -> listOfNotNull(key.submittedAt, key.storeUpdatedAt).distinct().map { it.epochSeconds to key } }
            .groupBy({ it.first }, { it.second })

    fun twinOf(review: ObservedReview): String? {
        if (review.storeReviewId.startsWith(ObservedReview.ARCHIVE_ID_PREFIX)) return null
        val second = review.submittedAt.epochSeconds
        return (second - 1..second + 1)
            .firstNotNullOfOrNull { bySecond[it]?.firstOrNull() }
            ?.storeReviewId
    }

    companion object {
        val TOLERANCE = 1.seconds
    }
}

private fun PlatformIngest.describe(): String =
    when (this) {
        is PlatformIngest.Ingested ->
            "$platform fetched=$fetched new=$created updated=$updated unchanged=$unchanged " +
                "suppressed=$suppressed answered=$answeredInStore adopted=$adopted rewritten=$rewritten removed=$removed " +
                "notify=${notifiable.size}"

        is PlatformIngest.Skipped -> "$platform skipped=$reason"
        is PlatformIngest.Failed -> "$platform failed=$kind"
    }
