package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisAlertRepository
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Jeden týden v grafu nálady. Týden, ne den: denní body u appky s deseti recenzemi šumí. */
data class SentimentWeek(
    val weekStart: LocalDate,
    val positive: Int,
    val neutral: Int,
    val negative: Int,
    val reviews: Int,
    val avgStars: Double?,
)

/** Téma v tabulce na stránce Rozbory — jako v rozboru do kanálu, navíc s trendem pro sparkline. */
data class TopicBreakdown(
    val key: String,
    val name: String,
    val count: Int,
    val previousCount: Int,
    val share: Double,
    val negativeShare: Double,
    val avgStars: Double?,
    val status: TopicStatus,
    /** Osm stejně dlouhých úseků období; poslední je nejnovější. */
    val trend: List<Int>,
)

data class TerritoryOverview(
    val territory: String,
    val reviews: Int,
    val negativeShare: Double,
    val avgStars: Double?,
)

data class LanguageOverview(
    val language: String,
    val reviews: Int,
    val negativeShare: Double,
)

/**
 * Rozbor za období tak, jak ho vidí konzole. Skládá se z týchž agregátů jako zpráva do
 * kanálu — jedno číslo nesmí být na stránce jiné než ve Slacku.
 */
data class AnalysisOverview(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dataSince: Instant?,
    val reviews: Int,
    val byPlatform: Map<Platform, Int>,
    val avgStars: Double?,
    val sentiment: SentimentShare,
    val previousSentiment: SentimentShare?,
    val weekly: List<SentimentWeek>,
    val topics: List<TopicBreakdown>,
    val improved: List<ImprovedTopic>,
    val territories: List<TerritoryOverview>,
    val languages: List<LanguageOverview>,
    val replies: ReplyStats,
    /** Kolik recenzí už má výklad a kolik jich čeká — bez toho jsou podíly nečitelné. */
    val analyzed: Int,
    val missing: Int,
    val thresholds: AnalysisThresholds,
) {
    val tooFewReviews: Boolean get() = reviews < thresholds.minReviews
}

/** Kalendářní období dostupná ve filtru konzole. Hranice se počítají v zóně aplikace. */
enum class AnalysisCalendarPeriod {
    THIS_WEEK,
    PREVIOUS_WEEK,
    THIS_MONTH,
    PREVIOUS_MONTH,
    THIS_YEAR,
    PREVIOUS_YEAR,
    ;

    fun dates(today: LocalDate): Pair<LocalDate, LocalDate> {
        val thisWeek = today.minus(today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
        val thisMonth = LocalDate(today.year, today.month, 1)
        val thisYear = LocalDate(today.year, 1, 1)
        return when (this) {
            THIS_WEEK -> thisWeek to today
            PREVIOUS_WEEK ->
                thisWeek.minus(7, DateTimeUnit.DAY) to thisWeek.minus(1, DateTimeUnit.DAY)

            THIS_MONTH -> thisMonth to today
            PREVIOUS_MONTH -> {
                val end = thisMonth.minus(1, DateTimeUnit.DAY)
                LocalDate(end.year, end.month, 1) to end
            }

            THIS_YEAR -> thisYear to today
            PREVIOUS_YEAR -> LocalDate(today.year - 1, 1, 1) to LocalDate(today.year - 1, 12, 31)
        }
    }
}

/** Výkyv i s názvem tématu v jazyce aplikace — konzole klíč nezobrazuje. */
data class AnalysisAlertView(
    val alert: AnalysisAlert,
    val topicName: String?,
)

/** Téma u jedné verze; podíl je z recenzí té verze, ne z celku. */
data class VersionTopicShare(
    val key: String,
    val name: String,
    val count: Int,
    val share: Double,
)

/** Jedna strana srovnání „před a po vydání". */
data class VersionSlice(
    val reviews: Int,
    val avgStars: Double?,
    val negativeShare: Double,
    val topics: List<VersionTopicShare>,
)

/**
 * Co udělalo vydání verze (B3). Srovnává se **stejná platforma**: iOS a Android mají jiné
 * publikum i jinou kulturu recenzí a míchat je znamená připsat verzi cizí problém.
 */
data class VersionImpact(
    val version: String,
    val platform: Platform,
    val firstSeen: Instant,
    val after: VersionSlice,
    val before: VersionSlice,
    /** Témata, která u verze jsou a před ní nebyla — červená část tabulky. */
    val newTopics: List<VersionTopicShare>,
    /** Témata, která zmizela. Zlepšení je zpráva stejně jako zhoršení. */
    val goneTopics: List<VersionTopicShare>,
) {
    val starsDelta: Double?
        get() {
            val now = after.avgStars ?: return null
            val was = before.avgStars ?: return null
            return now - was
        }

    val negativeDelta: Double get() = after.negativeShare - before.negativeShare
}

/**
 * Rozbory pro konzoli (B1) a dopad verzí (B3).
 *
 * Vlastní use case vedle [ScheduledAnalysisUseCase]: ten posílá zprávu a hlídá idempotenci,
 * tenhle jenom čte. Sdílejí [AnalysisAggregates] — čísla na stránce a čísla ve zprávě
 * pocházejí z jednoho výpočtu, jinak by si klient dřív nebo později všiml rozdílu.
 */
class AnalysisInsights(
    private val apps: AppRepository,
    private val aggregates: AnalysisAggregateRepository,
    private val insights: ReviewInsightRepository,
    private val appTopics: AppTopicRepository,
    /** Zaznamenané výkyvy (B4); `null` u procesů, které tabulku nemají po ruce. */
    private val alerts: AnalysisAlertRepository? = null,
    private val policy: AnalysisPolicy = AnalysisPolicy.fixed(),
    private val clock: Clock = Clock.System,
) {
    /**
     * Výkyvy za období. Do konzole patří i ty, o kterých zpráva nedorazila (kanál byl
     * zrovna rozbitý) — jinak by se o výkyvu nešlo dozvědět dodatečně.
     */
    fun alerts(
        orgId: OrganizationId,
        appId: AppId,
        days: Int = ALERT_DAYS,
    ): List<AnalysisAlertView> {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val repository = alerts ?: return emptyList()
        val zone = zoneOf(app.timezone)
        val since =
            clock
                .now()
                .toLocalDateTime(zone)
                .date
                .minus(days.coerceIn(MIN_DAYS, MAX_DAYS), DateTimeUnit.DAY)
        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        return repository.listByApp(orgId, appId, since, MAX_ALERTS).map { alert ->
            AnalysisAlertView(
                alert = alert,
                topicName = alert.topicKey?.let { AnalysisAggregates.nameOf(it, app.locale, names) },
            )
        }
    }

    fun overview(
        orgId: OrganizationId,
        appId: AppId,
        days: Int = DEFAULT_DAYS,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): AnalysisOverview {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val zone = zoneOf(app.timezone)
        val window = days.coerceIn(MIN_DAYS, MAX_DAYS)
        // Období končí dneškem včetně: na stránce chce člověk vidět i to, co přišlo dnes ráno.
        val end = clock.now().toLocalDateTime(zone).date
        return overview(app, orgId, end.minus(window - 1, DateTimeUnit.DAY), end, filter)
    }

    /** Kalendářní období se řídí místním dnem aplikace, ne zónou prohlížeče. */
    fun overview(
        orgId: OrganizationId,
        appId: AppId,
        period: AnalysisCalendarPeriod,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): AnalysisOverview {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val today = clock.now().toLocalDateTime(zoneOf(app.timezone)).date
        val (start, end) = period.dates(today)
        return overview(app, orgId, start, end, filter)
    }

    /**
     * Rozbor za **konkrétní období**, ne za posledních N dní. Používá ho měsíční report:
     * ten pojmenovává období měsícem a čísla pod tím jménem musí být z téhož měsíce —
     * klouzavé okno by dalo report „za srpen" spočítaný do dneška.
     */
    @Suppress("LongMethod")
    fun overview(
        orgId: OrganizationId,
        appId: AppId,
        start: LocalDate,
        end: LocalDate,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): AnalysisOverview {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        return overview(app, orgId, start, end, filter)
    }

    @Suppress("LongMethod")
    private fun overview(
        app: App,
        orgId: OrganizationId,
        start: LocalDate,
        end: LocalDate,
        filter: AnalysisFilter,
    ): AnalysisOverview {
        val appId = app.id
        val zone = zoneOf(app.timezone)
        // Srovnávací období je stejně dlouhé a přiléhá zleva — stejné pravidlo jako u rozboru do kanálu.
        val window = start.daysUntil(end) + 1
        val from = start.atStartOfDayIn(zone)
        val to = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val previousFrom = start.minus(window, DateTimeUnit.DAY).atStartOfDayIn(zone)

        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        val thresholds = policy.thresholds().forApp(app)
        val current = aggregates.aggregate(orgId, appId, from, to, filter)
        val previous = aggregates.aggregate(orgId, appId, previousFrom, from, filter)
        val replies = aggregates.replyStats(orgId, appId, from, to, filter)
        val coverage = insights.coverage(orgId, appId, Topic.TAXONOMY_VERSION)

        val summary =
            AnalysisAggregates.of(
                periodStart = start,
                periodEnd = end,
                current = current,
                previous = previous,
                replies = replies,
                dataSince = aggregates.dataSince(orgId, appId),
                locale = app.locale,
                thresholds = thresholds,
                names = names,
            )

        val daily = aggregates.daily(orgId, appId, from, to, app.timezone, filter)
        val dailyTopics = aggregates.dailyTopics(orgId, appId, from, to, app.timezone, filter)

        return AnalysisOverview(
            periodStart = start,
            periodEnd = end,
            dataSince = summary.dataSince,
            reviews = summary.reviews,
            byPlatform = summary.byPlatform,
            avgStars = summary.avgStars,
            sentiment = summary.sentiment,
            previousSentiment = summary.previousSentiment,
            weekly = weekly(daily, start, end),
            topics = summary.topics.map { topic -> topic.withTrend(dailyTopics, start, end) },
            improved = summary.improved,
            territories =
                aggregates.territories(orgId, appId, from, to, filter).map {
                    TerritoryOverview(
                        territory = it.territory,
                        reviews = it.reviews,
                        negativeShare = if (it.reviews > 0) it.negative.toDouble() / it.reviews else 0.0,
                        avgStars = if (it.reviews > 0) it.starSum.toDouble() / it.reviews else null,
                    )
                },
            languages =
                aggregates.languages(orgId, appId, from, to, filter).map {
                    LanguageOverview(
                        language = it.language,
                        reviews = it.reviews,
                        negativeShare = if (it.reviews > 0) it.negative.toDouble() / it.reviews else 0.0,
                    )
                },
            replies = replies,
            analyzed = coverage.analyzed,
            missing = coverage.missing,
            thresholds = thresholds,
        )
    }

    /**
     * Dopad verzí za poslední rok. „Před" je [BEFORE_DAYS] dní před prvním výskytem verze
     * na téže platformě — kalendářní měsíc by srovnával podle toho, kdy se klient dívá.
     *
     * První výskyt se bere z `submitted_at`, ne z `first_seen_at`: historie dotažená
     * z archivu má `first_seen_at` všech recenzí stejný (den importu) a verze by pak
     * vypadaly, že vyšly všechny naráz.
     */
    fun versions(
        orgId: OrganizationId,
        appId: AppId,
    ): List<VersionImpact> {
        val app = apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        val names = appTopics.listByApp(orgId, appId).associate { it.key to it.name }
        val now = clock.now()
        val windows = aggregates.versionWindows(orgId, appId, now - VERSION_HISTORY_DAYS.days, MIN_VERSION_REVIEWS)

        return windows.take(MAX_VERSIONS).map { window ->
            val after =
                slice(
                    orgId,
                    appId,
                    window.firstSeen,
                    now,
                    AnalysisFilter(platform = window.platform, version = window.version),
                    app.locale,
                    names,
                )
            val before =
                slice(
                    orgId,
                    appId,
                    window.firstSeen - BEFORE_DAYS.days,
                    window.firstSeen,
                    AnalysisFilter(platform = window.platform),
                    app.locale,
                    names,
                )
            val beforeKeys = before.topics.map { it.key }.toSet()
            val afterKeys = after.topics.map { it.key }.toSet()
            VersionImpact(
                version = window.version,
                platform = window.platform,
                firstSeen = window.firstSeen,
                after = after,
                before = before,
                newTopics = after.topics.filter { it.key !in beforeKeys },
                goneTopics = before.topics.filter { it.key !in afterKeys },
            )
        }
    }

    private fun slice(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
        locale: MessageLocale,
        names: Map<String, String>,
    ): VersionSlice {
        val period = aggregates.aggregate(orgId, appId, from, to, filter)
        return VersionSlice(
            reviews = period.reviews,
            avgStars = period.avgStars,
            negativeShare = if (period.reviews > 0) negativeOf(period.sentiments) else 0.0,
            topics =
                period.topics
                    .filter { it.count >= MIN_VERSION_TOPIC_COUNT }
                    .map {
                        VersionTopicShare(
                            key = it.key,
                            name = AnalysisAggregates.nameOf(it.key, locale, names),
                            count = it.count,
                            share = if (period.reviews > 0) it.count.toDouble() / period.reviews else 0.0,
                        )
                    }.sortedByDescending { it.count },
        )
    }

    private fun negativeOf(counts: Map<OverallSentiment, Int>): Double = SentimentShare.of(counts).negative

    /**
     * Denní body posbírané do týdnů začínajících pondělím. První a poslední týden bývají
     * neúplné — a je to tak správně: graf ukazuje, co se stalo, ne dopočítaný odhad.
     */
    private fun weekly(
        daily: List<DayCounts>,
        start: LocalDate,
        end: LocalDate,
    ): List<SentimentWeek> {
        if (daily.isEmpty()) return emptyList()
        val byWeek = daily.filter { it.date in start..end }.groupBy { mondayOf(it.date) }
        return byWeek.entries
            .sortedBy { it.key }
            .map { (monday, days) ->
                val reviews = days.sumOf { it.reviews }
                val stars = days.sumOf { it.starSum }
                val counts = days.flatMap { it.sentiments.entries }.groupBy({ it.key }, { it.value })
                val share = SentimentShare.of(counts.mapValues { (_, values) -> values.sum() })
                SentimentWeek(
                    weekStart = monday,
                    positive = (share.positive * reviews).toInt(),
                    neutral = (share.neutral * reviews).toInt(),
                    negative = reviews - (share.positive * reviews).toInt() - (share.neutral * reviews).toInt(),
                    reviews = reviews,
                    avgStars = if (reviews > 0) stars.toDouble() / reviews else null,
                )
            }
    }

    private fun TopicInsight.withTrend(
        daily: List<DayTopicCount>,
        start: LocalDate,
        end: LocalDate,
    ): TopicBreakdown {
        val mine = daily.filter { it.topicKey == key }.associate { it.date to it.count }
        val totalDays = (start.daysUntilInclusive(end)).coerceAtLeast(1)
        val buckets = IntArray(TREND_POINTS)
        mine.forEach { (date, count) ->
            val offset = start.daysUntilInclusive(date) - 1
            if (offset < 0) return@forEach
            val bucket = (offset * TREND_POINTS / totalDays).coerceIn(0, TREND_POINTS - 1)
            buckets[bucket] += count
        }
        return TopicBreakdown(
            key = key,
            name = name,
            count = count,
            previousCount = previousCount,
            share = share,
            negativeShare = negativeShare,
            avgStars = avgStars,
            status = status,
            trend = buckets.toList(),
        )
    }

    private fun LocalDate.daysUntilInclusive(other: LocalDate): Int = this.daysUntil(other) + 1

    private fun mondayOf(date: LocalDate): LocalDate =
        date.minus(date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)

    private fun zoneOf(timezone: String): TimeZone = runCatching { TimeZone.of(timezone) }.getOrDefault(TimeZone.UTC)

    companion object {
        const val DEFAULT_DAYS = 30

        /** Za jak dlouho zpátky se v konzoli vypisují výkyvy. */
        const val ALERT_DAYS = 90

        /** Víc výkyvů než tohle znamená, že jsou špatně nastavené prahy, ne že je co číst. */
        const val MAX_ALERTS = 50
        const val MIN_DAYS = 7
        const val MAX_DAYS = 365

        /** Kolik bodů má sparkline u tématu. Osm se vejde do tabulky a trend je z nich vidět. */
        const val TREND_POINTS = 8

        /** Verze s míň recenzemi neříká nic — podíl ze dvou recenzí je náhoda. */
        const val MIN_VERSION_REVIEWS = 5

        /** Téma u verze se počítá od dvou zmínek; verze mají řádově míň recenzí než období. */
        const val MIN_VERSION_TOPIC_COUNT = 2

        const val BEFORE_DAYS = 30
        const val VERSION_HISTORY_DAYS = 365

        /** Do tabulky se vejde pár posledních vydání; starší nikdo neřeší. */
        const val MAX_VERSIONS = 8
    }
}
