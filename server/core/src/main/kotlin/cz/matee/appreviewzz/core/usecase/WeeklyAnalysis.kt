package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.AnalysisDigest
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisDigestRepository
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Proč se rozbor neposílal. Žádný z důvodů není chyba — jen stav, který má být vidět. */
enum class AnalysisSkipReason {
    APP_NOT_FOUND,
    APP_DISABLED,

    /** Aplikace nemá zapnutý žádný kanál pro rozbory. */
    NO_CHANNEL,

    /**
     * Ani jedna recenze v období nemá výklad. Bez výkladu není co počítat — a poslat
     * prázdný rozbor by vypadalo, že se za týden nic nedělo.
     */
    NO_INSIGHTS,
}

data class AnalysisDelivery(
    val channelId: ChannelId,
    val sent: Boolean,
    /** Rozbor za tenhle týden už do kanálu odešel — opakovaný běh nesmí poslat druhý. */
    val alreadySent: Boolean = false,
    val error: String? = null,
)

data class WeeklyAnalysisReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val skipped: AnalysisSkipReason? = null,
    val aggregates: AnalysisAggregates? = null,
    val deliveries: List<AnalysisDelivery> = emptyList(),
) {
    fun failureSummary(): String = deliveries.mapNotNull { it.error }.joinToString()
}

/**
 * Týdenní rozbor recenzí do kanálu (F8).
 *
 * Období je **minulé pondělí až neděle v zóně aplikace** — ne posledních sedm dní: klient
 * porovnává týdny mezi sebou a klouzavé okno by mu to znemožnilo. Předchozí týden se počítá
 * taky, protože bez srovnání není z čísla „devět zmínek o pádech" poznat, jestli je to
 * dobře nebo špatně.
 */
class WeeklyAnalysisUseCase(
    private val apps: AppRepository,
    private val organizations: OrganizationRepository,
    private val channels: ChannelRepository,
    private val insights: ReviewInsightRepository,
    private val aggregates: AnalysisAggregateRepository,
    private val appTopics: AppTopicRepository,
    private val digests: AnalysisDigestRepository,
    private val secrets: SecretResolver,
    private val links: ConsoleLinks,
    notificationChannels: List<NotificationChannel>,
    private val clock: Clock = Clock.System,
) {
    private val channelByType = notificationChannels.associateBy { it.type }

    suspend fun run(
        orgId: OrganizationId,
        appId: AppId,
        periodStart: LocalDate? = null,
    ): WeeklyAnalysisReport {
        val app = apps.findById(orgId, appId) ?: return WeeklyAnalysisReport(orgId, appId, AnalysisSkipReason.APP_NOT_FOUND)
        if (!app.enabled) return WeeklyAnalysisReport(orgId, appId, AnalysisSkipReason.APP_DISABLED)

        val zone = zoneOf(app)
        val start = periodStart ?: lastFullWeekStart(app)
        val end = start.plus(WEEK_DAYS - 1, DateTimeUnit.DAY)
        val previousStart = start.minus(WEEK_DAYS, DateTimeUnit.DAY)

        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        // Horní mez je půlnoc následujícího dne: interval je zleva uzavřený, zprava otevřený,
        // takže recenze z neděle 23:59 do týdne patří a z pondělí 00:00 ne.
        val from = start.atStartOfDayIn(zone)
        val to = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val current = aggregates.aggregate(orgId, appId, from, to)
        if (current.reviews == 0 && insights.coverage(orgId, appId, Topic.TAXONOMY_VERSION).analyzed == 0) {
            return WeeklyAnalysisReport(orgId, appId, AnalysisSkipReason.NO_INSIGHTS)
        }
        val previous = aggregates.aggregate(orgId, appId, previousStart.atStartOfDayIn(zone), from)
        val replies = aggregates.replyStats(orgId, appId, from, to)

        val targets = channels.listByApp(orgId, appId).filter { it.enabled && it.deliverAnalyses }
        if (targets.isEmpty()) {
            return WeeklyAnalysisReport(
                orgId,
                appId,
                AnalysisSkipReason.NO_CHANNEL,
                aggregates = compose(app, start, end, current, previous, replies, names, app.locale),
            )
        }

        val slug = organizations.findById(orgId)?.slug.orEmpty()
        val deliveries =
            targets.map { channel ->
                val implementation = channelByType[channel.type]
                val credentialId = channel.credentialId
                val summary = compose(app, start, end, current, previous, replies, names, channel.locale)
                when {
                    implementation == null ->
                        AnalysisDelivery(channel.id, sent = false, error = "Kanál typu ${channel.type.name} tenhle proces neumí")

                    credentialId == null ->
                        AnalysisDelivery(channel.id, sent = false, error = "Chybí připojená instalace")

                    !digests.claim(orgId, appId, channel.id, start, clock.now()) ->
                        AnalysisDelivery(channel.id, sent = false, alreadySent = true)

                    else ->
                        try {
                            implementation.postAnalysisDigest(
                                ChannelTarget(channel.targetRef, secrets.resolve(orgId, credentialId)),
                                digest(app, slug, summary, channel.locale, start, end),
                            )
                            AnalysisDelivery(channel.id, sent = true)
                        } catch (error: ChannelException) {
                            logger.warn { "Rozbor do kanálu ${channel.id} selhal (${error.kind}): ${error.message}" }
                            AnalysisDelivery(channel.id, sent = false, error = error.message)
                        }
                }
            }

        val report =
            WeeklyAnalysisReport(
                orgId = orgId,
                appId = appId,
                aggregates = compose(app, start, end, current, previous, replies, names, app.locale),
                deliveries = deliveries,
            )
        logger.info {
            "Týdenní rozbor ${app.id} za $start–$end: recenzí=${report.aggregates?.reviews} " +
                "odesláno=${deliveries.count { it.sent }} z ${targets.size} kanálů"
        }
        return report
    }

    /** Období: minulé pondělí–neděle v zóně aplikace. Dnešek se nepočítá, týden musí být celý. */
    fun lastFullWeekStart(app: App): LocalDate {
        val today = clock.now().toLocalDateTime(zoneOf(app)).date
        val thisWeekMonday = today.minus(today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
        return thisWeekMonday.minus(WEEK_DAYS, DateTimeUnit.DAY)
    }

    private fun compose(
        app: App,
        start: LocalDate,
        end: LocalDate,
        current: AnalysisPeriod,
        previous: AnalysisPeriod,
        replies: ReplyStats,
        names: Map<String, String>,
        locale: MessageLocale,
    ): AnalysisAggregates =
        AnalysisAggregates.of(
            periodStart = start,
            periodEnd = end,
            current = current,
            previous = previous,
            replies = replies,
            dataSince = aggregates.dataSince(app.orgId, app.id),
            locale = locale,
            names = names,
        )

    private fun digest(
        app: App,
        slug: String,
        summary: AnalysisAggregates,
        locale: MessageLocale,
        start: LocalDate,
        end: LocalDate,
    ): AnalysisDigest {
        val zone = zoneOf(app)
        val leadTopic = summary.topics.firstOrNull()
        return AnalysisDigest(
            appName = app.name,
            locale = locale,
            aggregates = summary,
            quote =
                leadTopic?.let {
                    aggregates.topQuote(
                        app.orgId,
                        app.id,
                        it.key,
                        start.atStartOfDayIn(zone),
                        end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
                    )
                },
            consoleUrl = links.reviews(slug, app.id, leadTopic?.key),
        )
    }

    /** Neznámá zóna nesmí shodit rozbor — v nejhorším se týden počítá v UTC. */
    private fun zoneOf(app: App): TimeZone = runCatching { TimeZone.of(app.timezone) }.getOrDefault(TimeZone.UTC)

    private companion object {
        const val WEEK_DAYS = 7
    }
}
