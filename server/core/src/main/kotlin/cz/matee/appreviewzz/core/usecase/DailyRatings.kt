package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.PlatformRatings
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.RatingSnapshotRepository
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsDigestRepository
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Proč se přehled neposílal vůbec. Žádný z důvodů není chyba — jen stav, který má být vidět. */
enum class RatingsSkipReason {
    APP_NOT_FOUND,
    APP_DISABLED,

    /** Aplikace nemá zapnutý žádný kanál pro přehledy. */
    NO_CHANNEL,

    /** Ani jedna platforma nevrátila data — posílat prázdnou zprávu nemá smysl. */
    NO_DATA,
}

/** Selhání jedné platformy; ostatní se doručí dál. */
data class PlatformRatingsFailure(
    val platform: Platform,
    val kind: StoreErrorKind,
    val message: String,
)

data class RatingsDelivery(
    val channelId: ChannelId,
    val sent: Boolean,
    /** Přehled pro tenhle den už odešel — opakovaný běh jobu nesmí poslat druhý. */
    val alreadySent: Boolean = false,
    val error: String? = null,
)

data class RatingsReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val skipped: RatingsSkipReason? = null,
    val platforms: List<PlatformRatings> = emptyList(),
    val failures: List<PlatformRatingsFailure> = emptyList(),
    val deliveries: List<RatingsDelivery> = emptyList(),
) {
    val isRetryable: Boolean
        get() = failures.any { it.kind == StoreErrorKind.RATE_LIMITED || it.kind == StoreErrorKind.TRANSIENT }

    fun failureSummary(): String = failures.joinToString { "${it.platform}: ${it.kind} ${it.message}" }
}

/**
 * Denní přehled hodnocení (plán §5.6): posbírá čísla ze storů, uloží snapshot a pošle
 * do kanálů jednu zprávu za obě platformy.
 *
 * Tři věci, které dnešní n8n řešení nedělá:
 *
 * - **Snapshot se ukládá pro obě platformy.** Dneska se do databáze zapisují jen iOS řádky,
 *   protože Android jede přes cestu, která databázi vůbec nepoužívá — takže „kolik hodnocení
 *   přibylo" nejde u Androidu spočítat ani zpětně.
 * - **První běh se pozná a řekne to.** Bez včerejšího snapshotu dnešní výpočet prohlásí celý
 *   kumulativní histogram za „nová hodnocení" a vyrobí čísla v řádu tisíců.
 * - **Srovnává se s posledním předchozím snapshotem, ne s „včerejškem".** Když job jeden den
 *   neproběhne (výpadek, restart), včerejší řádek prostě není a dnešní pipeline spadne;
 *   tady se vezme poslední starší, jaký v databázi je.
 */
class DailyRatingsUseCase(
    private val apps: AppRepository,
    private val channels: ChannelRepository,
    private val credentials: CredentialRepository,
    private val snapshots: RatingSnapshotRepository,
    private val digests: RatingsDigestRepository,
    private val secrets: SecretResolver,
    ratingsSources: List<RatingsSource>,
    notificationChannels: List<NotificationChannel>,
    private val clock: Clock = Clock.System,
) {
    private val sourcesByPlatform = ratingsSources.groupBy { it.platform }
    private val channelByType = notificationChannels.associateBy { it.type }

    suspend fun run(
        orgId: OrganizationId,
        appId: AppId,
    ): RatingsReport {
        val app = apps.findById(orgId, appId) ?: return RatingsReport(orgId, appId, RatingsSkipReason.APP_NOT_FOUND)
        if (!app.enabled) return RatingsReport(orgId, appId, RatingsSkipReason.APP_DISABLED)

        val today = today(app)
        val failures = mutableListOf<PlatformRatingsFailure>()
        val parts =
            RatingsDigest.PLATFORM_ORDER
                .filter { it in app.platforms() }
                .mapNotNull { platform ->
                    try {
                        collect(app, platform, today)
                    } catch (error: StoreConnectorException) {
                        logger.warn { "Hodnocení $platform pro ${app.id} selhala (${error.kind}): ${error.message}" }
                        failures += PlatformRatingsFailure(platform, error.kind, error.message.orEmpty())
                        null
                    }
                }
        if (parts.isEmpty()) return RatingsReport(orgId, appId, RatingsSkipReason.NO_DATA, failures = failures)

        val targets = channels.listByApp(orgId, appId).filter { it.enabled && it.deliverRatings }
        if (targets.isEmpty()) {
            // Snapshoty jsou uložené, jen je nemá komu poslat — historie tím neutrpí.
            return RatingsReport(orgId, appId, RatingsSkipReason.NO_CHANNEL, platforms = parts, failures = failures)
        }

        val deliveries =
            targets.map { channel ->
                val implementation = channelByType[channel.type]
                val credentialId = channel.credentialId
                when {
                    implementation == null ->
                        RatingsDelivery(channel.id, sent = false, error = "Kanál typu ${channel.type.name} tenhle proces neumí")

                    credentialId == null ->
                        RatingsDelivery(channel.id, sent = false, error = "Chybí připojená instalace")

                    !digests.claim(orgId, appId, channel.id, today, clock.now()) ->
                        RatingsDelivery(channel.id, sent = false, alreadySent = true)

                    else ->
                        try {
                            implementation.postRatingsDigest(
                                ChannelTarget(channel.targetRef, secrets.resolve(orgId, credentialId)),
                                RatingsDigest(
                                    appName = app.name,
                                    locale = channel.locale,
                                    timezone = app.timezone,
                                    date = today,
                                    platforms = parts,
                                ),
                            )
                            RatingsDelivery(channel.id, sent = true)
                        } catch (error: ChannelException) {
                            logger.warn { "Přehled hodnocení do kanálu ${channel.id} selhal (${error.kind}): ${error.message}" }
                            RatingsDelivery(channel.id, sent = false, error = error.message)
                        }
                }
            }

        logger.info {
            "Přehled hodnocení ${app.id}: platforem=${parts.size} odesláno=${deliveries.count { it.sent }} " +
                "z ${targets.size} kanálů"
        }
        return RatingsReport(orgId, appId, platforms = parts, failures = failures, deliveries = deliveries)
    }

    /**
     * Sesbírá hodnocení jedné platformy, uloží snapshoty a spočítá srovnání s minulým během.
     *
     * Zdrojů je pro platformu víc a **slučují se, ne vylučují**: u průměru a počtu vyhrává
     * ten s vyšší prioritou (oficiální data), histogram doplní ten, kdo ho má. Jinak by iOS
     * nikdy nedostal rozpad po hvězdách a Android by ho měl jen bez Play Console.
     */
    private suspend fun collect(
        app: App,
        platform: Platform,
        today: LocalDate,
    ): PlatformRatings? {
        val identifier = app.storeIdentifier(platform) ?: return null
        val context =
            RatingsContext(
                appIdentifier = identifier,
                credential = ratingsCredential(app, platform),
                reportingBucket = app.gpReportingBucket,
            )

        val collected =
            sourcesByPlatform[platform]
                .orEmpty()
                .sortedByDescending { it.priority }
                .flatMap { source -> source.fetchRatings(context) }
        if (collected.isEmpty()) return null

        val perTerritory = collected.groupBy { it.territory }.mapValues { (_, parts) -> merge(parts) }
        val global =
            perTerritory[ObservedRatings.GLOBAL]
                ?: ObservedRatings.aggregate(perTerritory.values.toList(), perTerritory.values.first().source)
                ?: return null

        // Předchozí snapshot se čte **před** zápisem dnešního: jinak by se srovnávalo se sebou.
        val previous = previousSnapshot(app, platform, global.asOf ?: today)
        val stored = persist(app, platform, perTerritory, global, today)

        return PlatformRatings(
            platform = platform,
            average = stored.average,
            totalCount = stored.totalCount,
            previousAverage = previous?.average,
            previousCount = previous?.totalCount,
            newRatings = newRatings(stored.histogram, previous?.histogram),
            asOf = stored.date,
            previousAsOf = previous?.date,
        )
    }

    /**
     * Sloučení zdrojů jednoho storefrontu. Průměr a počet z toho nejdůvěryhodnějšího, který
     * je má; histogram od kohokoli, kdo ho dodal.
     */
    private fun merge(parts: List<ObservedRatings>): ObservedRatings {
        val authoritative = parts.firstOrNull { it.average != null } ?: parts.first()
        val histogram = parts.firstOrNull { it.histogram.isNotEmpty() }?.histogram.orEmpty()
        return authoritative.copy(
            histogram = histogram,
            totalCount = authoritative.totalCount ?: histogram.values.sum().takeIf { histogram.isNotEmpty() },
        )
    }

    /** Uloží rozpad po storefrontech i globální řádek a vrátí ten globální. */
    private fun persist(
        app: App,
        platform: Platform,
        perTerritory: Map<String, ObservedRatings>,
        global: ObservedRatings,
        today: LocalDate,
    ): RatingSnapshot {
        val collectedAt = clock.now()
        perTerritory
            .filterKeys { it != ObservedRatings.GLOBAL }
            .values
            .forEach { snapshots.upsert(app.orgId, it.toNewSnapshot(app.id, today), collectedAt) }
        return snapshots.upsert(
            app.orgId,
            global.copy(territory = ObservedRatings.GLOBAL).toNewSnapshot(app.id, today),
            collectedAt,
        )
    }

    /**
     * Poslední snapshot **staršího dne**. Ne „včerejšek": když job jeden den neproběhne,
     * včerejší řádek neexistuje a dnešní n8n na tom spadne, resp. vyrobí nesmysly.
     */
    private fun previousSnapshot(
        app: App,
        platform: Platform,
        currentDate: LocalDate,
    ): RatingSnapshot? =
        snapshots
            .listRecent(app.orgId, app.id, platform, ObservedRatings.GLOBAL, HISTORY_WINDOW)
            .firstOrNull { it.date < currentDate }

    /**
     * Kolik hodnocení po hvězdách přibylo. Záporné rozdíly se ořezávají: store hodnocení
     * občas maže a „−3 nové pětky" nikomu nic neřeknou.
     */
    private fun newRatings(
        current: Map<Int, Long>,
        previous: Map<Int, Long>?,
    ): Map<Int, Long> {
        if (current.isEmpty() || previous.isNullOrEmpty()) return emptyMap()
        return (1..MAX_STARS).associateWith { stars ->
            ((current[stars] ?: 0) - (previous[stars] ?: 0)).coerceAtLeast(0)
        }
    }

    /**
     * Klíč pro čtení hodnocení. Google Play ho potřebuje kvůli reportingovému bucketu;
     * iOS čísla jsou veřejná, takže se kvůli dennímu průměru nerozbaluje nic z vaultu.
     */
    private fun ratingsCredential(
        app: App,
        platform: Platform,
    ) = when (platform) {
        Platform.ANDROID ->
            credentials
                .findForApp(app.orgId, app.id, CredentialPurpose.RATINGS, CredentialType.GP_SERVICE_ACCOUNT)
                ?.let { secrets.resolve(app.orgId, it.id) }
                ?: credentials
                    .findForApp(app.orgId, app.id, CredentialPurpose.REVIEWS, CredentialType.GP_SERVICE_ACCOUNT)
                    ?.let { secrets.resolve(app.orgId, it.id) }

        Platform.IOS -> null
    }

    /** Dnešek v zóně aplikace — digest má chodit v čase klienta, ne serveru. */
    private fun today(app: App): LocalDate =
        clock.now().toLocalDateTime(runCatching { TimeZone.of(app.timezone) }.getOrDefault(TimeZone.UTC)).date

    private fun ObservedRatings.toNewSnapshot(
        appId: AppId,
        today: LocalDate,
    ) = NewRatingSnapshot(
        appId = appId,
        platform = platform,
        // Datum zdroje, ne dneška: Play export je den dva pozadu a snapshot má být poctivý.
        date = asOf ?: today,
        territory = territory,
        average = average ?: histogramAverage(),
        totalCount = totalCount ?: histogramCount(),
        histogram = histogram,
        source = source,
    )

    private companion object {
        const val MAX_STARS = 5

        /** Kolik dní zpět se hledá srovnání; víc než měsíc už není „od minule". */
        const val HISTORY_WINDOW = 30
    }
}
