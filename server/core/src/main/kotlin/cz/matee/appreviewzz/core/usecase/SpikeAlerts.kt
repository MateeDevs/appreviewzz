package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.AnalysisAlertMessage
import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisAlertRepository
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.NewAnalysisAlert
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Co běh zjistil. Prázdný seznam je normální stav — většina dnů žádný výkyv nemá. */
data class SpikeAlertReport(
    val alerts: List<AnalysisAlert> = emptyList(),
    val sent: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * Alert na výkyv v recenzích (B4).
 *
 * Spouští se na konci dotagování appky, ne z vlastního plánovače: výkyv se pozná až
 * z výkladů, a ty vznikají právě tam. Dvojí odeslání hlídá unikátní klíč v databázi —
 * dotagování jede po dávkách a jedna appka jich za den spolkne klidně deset.
 *
 * Posílá se **nejvýš jedna zpráva na den a druh**: záporný výkyv, a k němu nejvýš jeden
 * výkyv tématu. Tři zprávy o téže věci jsou pro tým šum, ne informace.
 */
@Suppress("LongParameterList")
class SpikeAlertUseCase(
    private val apps: AppRepository,
    private val organizations: OrganizationRepository,
    private val channels: ChannelRepository,
    private val aggregates: AnalysisAggregateRepository,
    private val alerts: AnalysisAlertRepository,
    private val appTopics: AppTopicRepository,
    private val secrets: SecretResolver,
    private val links: ConsoleLinks,
    notificationChannels: List<NotificationChannel>,
    private val clock: Clock = Clock.System,
) {
    private val channelByType = notificationChannels.associateBy { it.type }

    suspend fun run(
        orgId: OrganizationId,
        appId: AppId,
    ): SpikeAlertReport {
        val app = apps.findById(orgId, appId)?.takeIf { it.enabled } ?: return SpikeAlertReport()
        val zone = zoneOf(app)
        val today = clock.now().toLocalDateTime(zone).date
        val from = today.minus(SpikeDetection.BASELINE_DAYS, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val to = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)

        val daily = aggregates.daily(orgId, appId, from, to, app.timezone)
        val dailyTopics = aggregates.dailyTopics(orgId, appId, from, to, app.timezone)

        val found =
            buildList {
                negativeSpike(app, today, daily)?.let { add(it) }
                topicSpike(app, today, dailyTopics)?.let { add(it) }
            }
        if (found.isEmpty()) return SpikeAlertReport()

        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        val slug = organizations.findById(orgId)?.slug.orEmpty()
        val dayFrom = today.atStartOfDayIn(zone)
        val targets = channels.listByApp(orgId, appId).filter { it.enabled && it.deliverAnalyses }

        var sent = 0
        val errors = mutableListOf<String>()
        found.forEach { alert ->
            logger.info {
                "Výkyv ${alert.kind} u appky $appId za ${alert.windowDate}: " +
                    "${alert.observed} proti obvyklým ${"%.1f".format(alert.expected)} (z=${"%.1f".format(alert.zScore)})"
            }
            targets.forEach { channel ->
                val implementation = channelByType[channel.type]
                val credentialId = channel.credentialId
                if (implementation == null || credentialId == null) return@forEach
                try {
                    implementation.postAnalysisAlert(
                        ChannelTarget(channel.targetRef, secrets.resolve(orgId, credentialId)),
                        message(app, slug, alert, channel.locale, names, dayFrom, to, dailyTopics),
                    )
                    sent++
                } catch (error: ChannelException) {
                    logger.warn { "Alert do kanálu ${channel.id} selhal (${error.kind}): ${error.message}" }
                    errors += error.message.orEmpty()
                }
            }
        }
        return SpikeAlertReport(alerts = found, sent = sent, errors = errors)
    }

    /**
     * Výkyv záporných recenzí. Rezervace řádku předchází odeslání: kdybychom zapisovali
     * až potom, pád mezi tím by druhý den poslal zprávu znovu.
     */
    private fun negativeSpike(
        app: App,
        today: LocalDate,
        daily: List<DayCounts>,
    ): AnalysisAlert? {
        val counts = daily.map { DayCount(it.date, it.negative) }
        val current = counts.firstOrNull { it.date == today } ?: DayCount(today, 0)
        val spike = SpikeDetection.detect(counts.filter { it.date != today }, current) ?: return null
        return alerts.insertIfAbsent(
            app.orgId,
            NewAnalysisAlert(
                appId = app.id,
                kind = AlertKind.NEGATIVE_SPIKE,
                topicKey = null,
                windowDate = spike.date,
                observed = spike.observed,
                expected = spike.expected,
                zScore = spike.zScore,
            ),
            clock.now(),
        )
    }

    /** Nejvýraznější výkyv tématu; víc než jeden by z alertu udělal seznam. */
    private fun topicSpike(
        app: App,
        today: LocalDate,
        dailyTopics: List<DayTopicCount>,
    ): AnalysisAlert? {
        val byTopic = dailyTopics.groupBy { it.topicKey }
        val best =
            byTopic
                .mapNotNull { (key, days) ->
                    val current = days.firstOrNull { it.date == today } ?: return@mapNotNull null
                    val history = days.filter { it.date != today }.map { DayCount(it.date, it.count) }
                    SpikeDetection.detect(history, DayCount(today, current.count))?.let { key to it }
                }.maxByOrNull { it.second.zScore }
                ?: return null

        return alerts.insertIfAbsent(
            app.orgId,
            NewAnalysisAlert(
                appId = app.id,
                kind = AlertKind.TOPIC_SPIKE,
                topicKey = best.first,
                windowDate = best.second.date,
                observed = best.second.observed,
                expected = best.second.expected,
                zScore = best.second.zScore,
            ),
            clock.now(),
        )
    }

    @Suppress("LongParameterList")
    private fun message(
        app: App,
        slug: String,
        alert: AnalysisAlert,
        locale: MessageLocale,
        names: Map<String, String>,
        from: Instant,
        to: Instant,
        dailyTopics: List<DayTopicCount>,
    ): AnalysisAlertMessage {
        val topToday =
            dailyTopics
                .filter { it.date == alert.windowDate }
                .maxByOrNull { it.count }
        val quoteKey = alert.topicKey ?: topToday?.topicKey
        // Verze se bere z dnešního období, ne z celé baseline: alert má ukázat, co se děje
        // teď, a verze z předloňska by čtenáře poslala hledat úplně jinam.
        val version =
            aggregates
                .aggregate(app.orgId, app.id, from, to)
                .versions
                .maxByOrNull { it.count }
        return AnalysisAlertMessage(
            appName = app.name,
            locale = locale,
            alert = alert,
            topicName = alert.topicKey?.let { AnalysisAggregates.nameOf(it, locale, names) },
            topTopic = topToday?.let { AnalysisAggregates.nameOf(it.topicKey, locale, names) to it.count },
            topVersion = version?.let { it.version to it.count },
            quotes =
                quoteKey?.let { aggregates.topQuotes(app.orgId, app.id, it, from, to, MAX_QUOTES) }.orEmpty(),
            consoleUrl = links.reviews(slug, app.id, alert.topicKey ?: topToday?.topicKey),
        )
    }

    private fun zoneOf(app: App): TimeZone = runCatching { TimeZone.of(app.timezone) }.getOrDefault(TimeZone.UTC)

    private companion object {
        /** Dva citáty stačí; tři už nikdo nečte. */
        const val MAX_QUOTES = 2
    }
}
