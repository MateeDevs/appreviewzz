package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.ReviewRefreshSource
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}

/** Výsledek dohledávání pro jednu platformu jedné aplikace. */
sealed interface PlatformRefresh {
    val platform: Platform

    data class Refreshed(
        override val platform: Platform,
        /** Kolik recenzí se ověřovalo. */
        val checked: Int,
        /** Kolik z nich se právě překlopilo do REPLIED. */
        val answered: Int,
        /** Recenze, které store už nezná — smazané autorem. */
        val gone: Int,
    ) : PlatformRefresh

    data class Skipped(
        override val platform: Platform,
        val reason: PlatformSkipReason,
    ) : PlatformRefresh

    data class Failed(
        override val platform: Platform,
        val kind: StoreErrorKind,
        val message: String,
    ) : PlatformRefresh
}

data class RefreshReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val platforms: List<PlatformRefresh>,
    val appSkipped: AppSkipReason? = null,
) {
    val answered: Int
        get() = platforms.filterIsInstance<PlatformRefresh.Refreshed>().sumOf { it.answered }
}

/**
 * Dohledání odpovědí, které někdo napsal ve storu mimo náš systém a **ingest je nemohl vidět**.
 *
 * Google Play `reviews.list` vrací jen ~týden zpět. Odpověď napsaná v Play Console později
 * proto do systému nikdy nedorazí a recenze u nás zůstane „čeká na odpověď" navždy — přesně
 * to se dělo u recenzí, na které tým odpovídal se zpožděním. `reviews.get` ale okno nemá,
 * takže se recenze dá dotáhnout po ID, které v databázi máme.
 *
 * Tři meze, které tomu drží náklady i chování v mezích:
 *
 * - **[refreshAfter]** — spodní hranice stáří. Recenze, které ingest ještě vidí, se nechávají
 *   jemu: kdyby je přepsalo dohledávání, ingest by pak editaci od autora vyhodnotil jako
 *   „beze změny" a do kanálu by nic neposlal.
 * - **[maxAge]** — kam už nemá cenu se vracet. Bez ní by každý běh procházel celou historii.
 * - **[batchSize]** — strop volání na jednu appku a běh. Jedno HTTP volání na recenzi je
 *   nejdražší část celé úlohy.
 *
 * Selhání storu se nepropaguje ven jako výjimka — vrací se v [RefreshReport], stejně jako
 * u ingestu, protože o retry rozhoduje volající.
 */
class RefreshStoreRepliesUseCase(
    private val apps: AppRepository,
    private val credentials: CredentialRepository,
    private val reviews: ReviewRepository,
    private val secrets: SecretResolver,
    sources: List<ReviewRefreshSource>,
    private val clock: Clock = Clock.System,
    private val refreshAfter: Duration = DEFAULT_REFRESH_AFTER,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val sourceByPlatform: Map<Platform, ReviewRefreshSource> = sources.associateBy { it.platform }

    init {
        require(sourceByPlatform.size == sources.size) {
            "Pro jednu platformu je zaregistrovaný víc než jeden ReviewRefreshSource"
        }
    }

    suspend fun refresh(
        orgId: OrganizationId,
        appId: AppId,
    ): RefreshReport {
        val app =
            apps.findById(orgId, appId)
                ?: return RefreshReport(orgId, appId, emptyList(), AppSkipReason.NOT_FOUND)
        if (!app.enabled) return RefreshReport(orgId, appId, emptyList(), AppSkipReason.DISABLED)

        val results = Platform.entries.filter { it in app.platforms() }.map { refreshPlatform(app, it) }
        val answered = results.filterIsInstance<PlatformRefresh.Refreshed>().sumOf { it.answered }
        if (answered > 0) {
            logger.info { "Dohledání odpovědí ${app.name} (${app.id}): $answered recenzí odpovězeno ve storu" }
        }
        return RefreshReport(orgId, appId, results)
    }

    private suspend fun refreshPlatform(
        app: App,
        platform: Platform,
    ): PlatformRefresh {
        // Platforma bez konektoru pro dohledávání (dnes iOS) není chyba — ASC vrací historii celou.
        val source =
            sourceByPlatform[platform]
                ?: return PlatformRefresh.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        val identifier =
            app.storeIdentifier(platform)
                ?: return PlatformRefresh.Skipped(platform, PlatformSkipReason.NO_CONNECTOR)
        val credential =
            credentials.findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, credentialType(platform))
                ?: return PlatformRefresh.Skipped(platform, PlatformSkipReason.MISSING_CREDENTIAL)

        val now = clock.now()
        val pending =
            reviews.listAwaitingStoreReply(
                orgId = app.orgId,
                appId = app.id,
                platform = platform,
                submittedAfter = now - maxAge,
                submittedBefore = now - refreshAfter,
                limit = batchSize,
            )
        if (pending.isEmpty()) return PlatformRefresh.Refreshed(platform, checked = 0, answered = 0, gone = 0)

        var answered = 0
        var gone = 0
        val context = StoreContext(identifier, secrets.resolve(app.orgId, credential.id))
        pending.forEach { review ->
            val observed =
                try {
                    source.fetchReview(context, review.storeReviewId)
                } catch (error: StoreConnectorException) {
                    logger.warn { "Dohledání ${app.id}/$platform selhalo (${error.kind}): ${error.message}" }
                    return PlatformRefresh.Failed(platform, error.kind, error.message.orEmpty())
                }
            if (observed == null) {
                gone++
                return@forEach
            }
            // Stav se předává, i když se nepoužije: kdyby řádek mezitím zmizel, ať se recenze
            // nezaloží znovu jako nová a nespustí notifikaci o měsíce staré recenzi.
            val result = reviews.upsert(app.orgId, app.id, observed, now, review.state)
            if (markAnsweredInStore(reviews, result)) answered++
        }
        return PlatformRefresh.Refreshed(platform, checked = pending.size, answered = answered, gone = gone)
    }

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }

    companion object {
        /** Okno `reviews.list` je ~7 dní; den navrch, aby se úlohy nepřekrývaly na hraně. */
        val DEFAULT_REFRESH_AFTER: Duration = 8.days

        /** Půl roku zpět. Dál už je „čeká na odpověď" spíš archiv než pracovní fronta. */
        val DEFAULT_MAX_AGE: Duration = 180.days

        const val DEFAULT_BATCH_SIZE = 50
    }
}
