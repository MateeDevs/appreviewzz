package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.AnalysisDigest
import cz.matee.appreviewzz.core.model.AnalysisCadence
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisDigestRepository
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.NarrativeQuote
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.topQuote
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Proč se rozbor neposílal. Žádný z důvodů není chyba — jen stav, který má být vidět. */
enum class AnalysisSkipReason {
    APP_NOT_FOUND,
    APP_DISABLED,

    /** Aplikace nemá zapnutý žádný kanál pro rozbory. */
    NO_CHANNEL,

    /**
     * Ani jedna recenze v období nemá výklad. Bez výkladu není co počítat — a poslat
     * prázdný rozbor by vypadalo, že se za období nic nedělo.
     */
    NO_INSIGHTS,

    /**
     * Od minulého rozboru se nenasbíralo dost recenzí s textem. **Termín se nepromlčuje** —
     * období zůstane otevřené a přičte se k příštímu běhu, takže z málo dat vznikne delší
     * období, ne zpráva „zatím málo dat".
     */
    NOT_ENOUGH_REVIEWS,
}

data class AnalysisDelivery(
    val channelId: ChannelId,
    val sent: Boolean,
    /** Rozbor za tenhle týden už do kanálu odešel — opakovaný běh nesmí poslat druhý. */
    val alreadySent: Boolean = false,
    val error: String? = null,
)

data class ScheduledAnalysisReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val skipped: AnalysisSkipReason? = null,
    val aggregates: AnalysisAggregates? = null,
    val deliveries: List<AnalysisDelivery> = emptyList(),
) {
    fun failureSummary(): String = deliveries.mapNotNull { it.error }.joinToString()
}

/**
 * Pravidelný rozbor recenzí do kanálu (F8, kadence z A11).
 *
 * Období je **celý minulý týden, resp. minulý měsíc v zóně aplikace** — ne posledních sedm
 * dní: klient porovnává období mezi sebou a klouzavé okno by mu to znemožnilo. Předchozí
 * období se počítá taky, protože bez srovnání není z čísla „devět zmínek o pádech" poznat,
 * jestli je to dobře nebo špatně.
 *
 * **Termín pod prahem se přeskočí, ne odbyde.** Dřív šla pod deseti recenzemi do kanálu
 * zpráva „zatím málo dat" — každý týden, u appky s řídkým provozem donekonečna. Nově se
 * období nechá otevřené a příští běh naváže od konce posledního **odeslaného** rozboru,
 * takže se z něj stane delší období s čísly, ze kterých se dá něco vyčíst.
 */
class ScheduledAnalysisUseCase(
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
    /**
     * Úvodní odstavec od modelu (B6). `null` = instalace bez AI; zpráva pak odejde jen
     * ze šablony, což je i tak úplný rozbor.
     */
    private val narrator: AnalysisNarrator? = null,
    /** Prahy rozboru; výchozí hodnoty pro testy a pro běh bez platformní konfigurace. */
    private val policy: AnalysisPolicy = AnalysisPolicy.fixed(),
    private val clock: Clock = Clock.System,
) {
    private val channelByType = notificationChannels.associateBy { it.type }

    /**
     * @param periodStart ruční začátek období; jinak navazuje na poslední odeslaný rozbor
     * @param force pošli i pod prahem — pro onboarding, kdy chce člověk vidět první zprávu
     */
    suspend fun run(
        orgId: OrganizationId,
        appId: AppId,
        periodStart: LocalDate? = null,
        force: Boolean = false,
    ): ScheduledAnalysisReport {
        val app = apps.findById(orgId, appId) ?: return ScheduledAnalysisReport(orgId, appId, AnalysisSkipReason.APP_NOT_FOUND)
        if (!app.enabled) return ScheduledAnalysisReport(orgId, appId, AnalysisSkipReason.APP_DISABLED)

        val zone = zoneOf(app)
        val thresholds = policy.thresholds().forApp(app)
        val (start, end) = period(app, periodStart, digests.lastPeriodEnd(orgId, appId))
        // Srovnávací období je stejně dlouhé a přiléhá zleva — u dobíhajícího období by
        // pevný týden porovnával tři týdny proti jednomu.
        val periodDays = start.daysUntil(end) + 1
        val previousStart = start.minus(periodDays, DateTimeUnit.DAY)

        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        // Horní mez je půlnoc následujícího dne: interval je zleva uzavřený, zprava otevřený,
        // takže recenze z neděle 23:59 do týdne patří a z pondělí 00:00 ne.
        val from = start.atStartOfDayIn(zone)
        val to = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val current = aggregates.aggregate(orgId, appId, from, to)
        if (current.reviews == 0 && insights.coverage(orgId, appId, Topic.TAXONOMY_VERSION).analyzed == 0) {
            return ScheduledAnalysisReport(orgId, appId, AnalysisSkipReason.NO_INSIGHTS)
        }
        val previous = aggregates.aggregate(orgId, appId, previousStart.atStartOfDayIn(zone), from)
        val replies = aggregates.replyStats(orgId, appId, from, to)

        if (!force && current.reviews < thresholds.minReviews) {
            logger.info {
                "Rozbor ${app.id} za $start–$end odložen: ${current.reviews} recenzí s textem " +
                    "z ${thresholds.minReviews}; období poběží dál"
            }
            return ScheduledAnalysisReport(
                orgId,
                appId,
                AnalysisSkipReason.NOT_ENOUGH_REVIEWS,
                aggregates = compose(app, start, end, current, previous, replies, names, app.locale, thresholds),
            )
        }

        val targets = channels.listByApp(orgId, appId).filter { it.enabled && it.deliverAnalyses }
        if (targets.isEmpty()) {
            return ScheduledAnalysisReport(
                orgId,
                appId,
                AnalysisSkipReason.NO_CHANNEL,
                aggregates = compose(app, start, end, current, previous, replies, names, app.locale, thresholds),
            )
        }

        val slug = organizations.findById(orgId)?.slug.orEmpty()
        val note = versionNote(app, from, to, previousStart.atStartOfDayIn(zone), previous, thresholds, names)
        // Shrnutí se počítá **jednou na jazyk a až když je komu poslat**: dvě anglické
        // místnosti téže appky dostanou tentýž odstavec a opakovaný běh, který nic
        // neodešle, za model neplatí.
        val summaries = mutableMapOf<MessageLocale, String?>()
        val deliveries =
            targets.map { channel ->
                val implementation = channelByType[channel.type]
                val credentialId = channel.credentialId
                val summary = compose(app, start, end, current, previous, replies, names, channel.locale, thresholds)
                when {
                    implementation == null ->
                        AnalysisDelivery(channel.id, sent = false, error = "Kanál typu ${channel.type.name} tenhle proces neumí")

                    credentialId == null ->
                        AnalysisDelivery(channel.id, sent = false, error = "Chybí připojená instalace")

                    !digests.claim(orgId, appId, channel.id, start, end, clock.now()) ->
                        AnalysisDelivery(channel.id, sent = false, alreadySent = true)

                    else ->
                        try {
                            val paragraph =
                                if (summaries.containsKey(channel.locale)) {
                                    summaries[channel.locale]
                                } else {
                                    narrative(app, summary, channel.locale, from, to).also { summaries[channel.locale] = it }
                                }
                            implementation.postAnalysisDigest(
                                ChannelTarget(channel.targetRef, secrets.resolve(orgId, credentialId)),
                                digest(app, slug, summary, channel.locale, start, end, note, paragraph),
                            )
                            AnalysisDelivery(channel.id, sent = true)
                        } catch (error: ChannelException) {
                            logger.warn { "Rozbor do kanálu ${channel.id} selhal (${error.kind}): ${error.message}" }
                            AnalysisDelivery(channel.id, sent = false, error = error.message)
                        }
                }
            }

        val report =
            ScheduledAnalysisReport(
                orgId = orgId,
                appId = appId,
                aggregates = compose(app, start, end, current, previous, replies, names, app.locale, thresholds),
                deliveries = deliveries,
            )
        logger.info {
            "Rozbor ${app.id} za $start–$end: recenzí=${report.aggregates?.reviews} " +
                "odesláno=${deliveries.count { it.sent }} z ${targets.size} kanálů"
        }
        return report
    }

    /**
     * Období rozboru jako `[začátek, konec]` včetně obou dnů.
     *
     * Konec je vždycky poslední den **celého** minulého období — dnešek se nepočítá, jinak by
     * se srovnávalo neúplné období s úplným. Začátek navazuje na poslední odeslaný rozbor;
     * když se termín přeskočil, období je delší než jedno.
     */
    fun period(
        app: App,
        periodStart: LocalDate? = null,
        lastSentEnd: LocalDate? = null,
    ): Pair<LocalDate, LocalDate> {
        val end = lastFullPeriodEnd(app)
        // Ruční období má vlastní délku podle kadence, ne konec posledního celého období:
        // člověk, který zadá „od 17. srpna", chce ten týden, ne všechno až do dneška.
        if (periodStart != null) return periodStart to defaultEndFor(app, periodStart)
        val default = defaultStartFor(app, end)
        val continued = lastSentEnd?.plus(1, DateTimeUnit.DAY)?.takeIf { it < default } ?: default
        return continued to end
    }

    /** Poslední den minulého celého období v zóně aplikace. */
    private fun lastFullPeriodEnd(app: App): LocalDate {
        val today = clock.now().toLocalDateTime(zoneOf(app)).date
        return when (app.analysisCadence) {
            AnalysisCadence.WEEKLY -> {
                val thisWeekMonday = today.minus(today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
                thisWeekMonday.minus(1, DateTimeUnit.DAY)
            }

            AnalysisCadence.MONTHLY -> LocalDate(today.year, today.month, 1).minus(1, DateTimeUnit.DAY)
        }
    }

    private fun defaultStartFor(
        app: App,
        end: LocalDate,
    ): LocalDate =
        when (app.analysisCadence) {
            AnalysisCadence.WEEKLY -> end.minus(WEEK_DAYS - 1, DateTimeUnit.DAY)
            AnalysisCadence.MONTHLY -> LocalDate(end.year, end.month, 1)
        }

    /** Konec období, které začíná zadaným dnem — pro ruční běh nad konkrétním obdobím. */
    private fun defaultEndFor(
        app: App,
        start: LocalDate,
    ): LocalDate =
        when (app.analysisCadence) {
            AnalysisCadence.WEEKLY -> start.plus(WEEK_DAYS - 1, DateTimeUnit.DAY)
            AnalysisCadence.MONTHLY -> LocalDate(start.year, start.month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
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
        thresholds: AnalysisThresholds,
    ): AnalysisAggregates =
        AnalysisAggregates.of(
            periodStart = start,
            periodEnd = end,
            current = current,
            previous = previous,
            replies = replies,
            dataSince = aggregates.dataSince(app.orgId, app.id),
            locale = locale,
            thresholds = thresholds,
            names = names,
        )

    /**
     * Úvodní odstavec od modelu (B6). Kandidátské citáty jsou ověřené úryvky k nejpalčivějším
     * tématům — model si nesmí vymyslet ani citát, ani číslo, a co neprojde, se zahodí.
     */
    private suspend fun narrative(
        app: App,
        aggregates: AnalysisAggregates,
        locale: MessageLocale,
        from: Instant,
        to: Instant,
    ): String? {
        val writer = narrator?.takeIf { policy.narrativeEnabled() } ?: return null
        if (aggregates.tooFewReviews) return null
        val quotes =
            aggregates.issues
                .flatMap { topic ->
                    this.aggregates
                        .topQuotes(app.orgId, app.id, topic.key, from, to, QUOTES_PER_TOPIC)
                        .map { NarrativeQuote(it.reviewId.toString(), it.quote) }
                }.distinctBy { it.reviewId }
                .take(MAX_NARRATIVE_QUOTES)
        return writer.write(app.name, locale, aggregates, quotes)
    }

    /**
     * Vydání, které do období přineslo téma, jaké předtím nebylo (B3).
     *
     * Verze se počítá za novou, když v **srovnávacím** období neměla ani jednu recenzi —
     * úplná historie by stála dotaz přes celou appku a pro jednu větu ve zprávě to nestojí
     * za to. Bere se ta s nejvíc recenzemi; jedno vydání za období je pravidlo, ne výjimka.
     */
    private fun versionNote(
        app: App,
        from: Instant,
        to: Instant,
        previousFrom: Instant,
        previous: AnalysisPeriod,
        thresholds: AnalysisThresholds,
        names: Map<String, String>,
    ): AnalysisDigest.VersionNote? {
        val candidate =
            aggregates
                .versionWindows(app.orgId, app.id, from, MIN_VERSION_REVIEWS)
                .maxByOrNull { it.reviews }
                ?: return null
        val filter = AnalysisFilter(platform = candidate.platform, version = candidate.version)
        if (aggregates.aggregate(app.orgId, app.id, previousFrom, from, filter).reviews > 0) return null

        val before =
            previous.topics
                .filter { it.count >= thresholds.minTopicCount }
                .map { it.key }
                .toSet()
        val fresh =
            aggregates
                .aggregate(app.orgId, app.id, from, to, filter)
                .topics
                .filter { it.key !in before && it.count >= thresholds.minTopicCount && it.negative > it.positive }
                .maxByOrNull { it.count }
                ?: return null
        return AnalysisDigest.VersionNote(
            version = candidate.version,
            topicName = AnalysisAggregates.nameOf(fresh.key, app.locale, names),
            count = fresh.count,
        )
    }

    private fun digest(
        app: App,
        slug: String,
        summary: AnalysisAggregates,
        locale: MessageLocale,
        start: LocalDate,
        end: LocalDate,
        versionNote: AnalysisDigest.VersionNote?,
        summaryText: String?,
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
            versionNote = versionNote,
            summary = summaryText,
        )
    }

    /** Neznámá zóna nesmí shodit rozbor — v nejhorším se období počítá v UTC. */
    private fun zoneOf(app: App): TimeZone = runCatching { TimeZone.of(app.timezone) }.getOrDefault(TimeZone.UTC)

    private companion object {
        const val WEEK_DAYS = 7

        /** Z verze s míň recenzemi se nedá nic vyčíst — stejná hranice jako u dopadu verzí. */
        const val MIN_VERSION_REVIEWS = 5

        /** Kandidáti na citaci ve shrnutí: dva na téma, nejvýš osm celkem. */
        const val QUOTES_PER_TOPIC = 2
        const val MAX_NARRATIVE_QUOTES = 8
    }
}
