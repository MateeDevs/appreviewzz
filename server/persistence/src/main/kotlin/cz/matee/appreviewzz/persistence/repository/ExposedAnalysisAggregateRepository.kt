package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.AnalysisReviewScope
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.LanguageAggregate
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TerritoryAggregate
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.port.VersionAggregate
import cz.matee.appreviewzz.core.port.VersionWindow
import cz.matee.appreviewzz.persistence.schema.Replies
import cz.matee.appreviewzz.persistence.schema.ReviewInsightTopics
import cz.matee.appreviewzz.persistence.schema.ReviewInsights
import cz.matee.appreviewzz.persistence.schema.ReviewRevisions
import cz.matee.appreviewzz.persistence.schema.Reviews
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.datetime.KotlinLocalDateColumnType
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Agregace rozborů (F8). **Počítá databáze, ne aplikace**: rozbor jde přes celou historii
 * appky a načítat kvůli podílu témat deset tisíc recenzí do paměti by byl nesmysl.
 *
 * Období je zleva uzavřené, zprava otevřené (`from <= submitted_at < to`) — jinak by recenze
 * z hranice půlnoci padla do obou týdnů.
 */
@Suppress("TooManyFunctions")
class ExposedAnalysisAggregateRepository(
    private val database: ExposedDatabase,
) : AnalysisAggregateRepository {
    override fun aggregate(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): AnalysisPeriod =
        transaction(database) {
            val reviewCount = Reviews.id.count()
            val starSum = Reviews.starRating.sum()

            // Základ: kolik recenzí a s jakou náladou. Bez výkladu se recenze do rozboru
            // nepočítá — jinak by podíly klesaly s tím, kolik toho AI nestihla.
            val base =
                Reviews
                    .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                    .select(Reviews.platform, ReviewInsights.sentiment, reviewCount, starSum)
                    .where { scope(orgId, appId, from, to, filter) }
                    .groupBy(Reviews.platform, ReviewInsights.sentiment)
                    .map {
                        Triple(
                            it[Reviews.platform],
                            it[ReviewInsights.sentiment],
                            it[reviewCount].toInt() to (it[starSum]?.toInt() ?: 0),
                        )
                    }

            val reviews = base.sumOf { it.third.first }
            val byPlatform =
                base
                    .groupBy { it.first }
                    .mapValues { (_, rows) -> rows.sumOf { it.third.first } }
            val sentiments =
                base
                    .groupBy { it.second }
                    .mapValues { (_, rows) -> rows.sumOf { it.third.first } }

            AnalysisPeriod(
                reviews = reviews,
                byPlatform = byPlatform,
                starSum = base.sumOf { it.third.second },
                sentiments = sentiments,
                topics = topics(orgId, appId, from, to, filter),
                versions = versions(orgId, appId, from, to, filter),
            )
        }

    override fun replyStats(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): ReplyStats =
        transaction(database) {
            val total =
                Reviews
                    .selectAll()
                    .where { scope(orgId, appId, from, to, filter) }
                    .count()
                    .toInt()

            // Odpovědi publikované přes nás. Medián se počítá v paměti, protože jde nejvýš
            // o stovky čísel a `percentile_cont` by znamenalo syrové SQL.
            val ours =
                Reviews
                    .join(Replies, JoinType.INNER, Reviews.id, Replies.reviewId)
                    .select(Reviews.id, Reviews.submittedAt, Replies.publishedAt)
                    .where {
                        scope(orgId, appId, from, to, filter) and
                            (Replies.status eq ReplyStatus.PUBLISHED) and
                            Replies.publishedAt.isNotNull()
                    }.mapNotNull { row ->
                        row[Replies.publishedAt]?.let { published -> row[Reviews.id] to (row[Reviews.submittedAt] to published) }
                    }

            // Odpovědi napsané ve storu (Play Console, App Store Connect) se počítají taky:
            // klient se ptá, jestli recenze dostala odpověď, ne kterým oknem prošla. U historie
            // dotažené z archivu je to navíc jediné, co o odpovídání víme.
            val inStore =
                Reviews
                    .select(Reviews.id, Reviews.submittedAt, Reviews.developerResponseAt)
                    .where { scope(orgId, appId, from, to, filter) and Reviews.developerResponseAt.isNotNull() }
                    .mapNotNull { row ->
                        row[Reviews.developerResponseAt]?.let { answered -> row[Reviews.id] to (row[Reviews.submittedAt] to answered) }
                    }

            // Táž recenze může být v obou seznamech (odpověděli jsme my a store to vrátil zpátky);
            // platí ta dřívější, protože ta je skutečným okamžikem odpovědi.
            val answered = mutableMapOf<ReviewId, Pair<Instant, Instant>>()
            (ours + inStore).forEach { (reviewId, times) ->
                val existing = answered[reviewId]
                if (existing == null || times.second < existing.second) answered[reviewId] = times
            }
            val hours =
                answered.values
                    .map { (submitted, replied) -> (replied - submitted).inWholeMinutes / MINUTES_PER_HOUR }
                    .sorted()

            val effect = replyEffect(answered.mapValues { (_, times) -> times.second })
            ReplyStats(
                total = total,
                replied = hours.size,
                medianHours = median(hours),
                uplifted = effect.first,
                dropped = effect.second,
            )
        }

    override fun dataSince(
        orgId: OrganizationId,
        appId: AppId,
    ): Instant? =
        transaction(database) {
            val oldest = Reviews.firstSeenAt.min()
            Reviews
                .select(oldest)
                .where { (Reviews.orgId eq orgId) and (Reviews.appId eq appId) }
                .firstOrNull()
                ?.get(oldest)
        }

    override fun topQuotes(
        orgId: OrganizationId,
        appId: AppId,
        topicKey: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<TopicQuote> =
        transaction(database) {
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .join(ReviewInsightTopics, JoinType.INNER, Reviews.id, ReviewInsightTopics.reviewId)
                .selectAll()
                .where {
                    scope(orgId, appId, from, to, AnalysisFilter.ALL) and
                        (ReviewInsightTopics.topicKey eq topicKey) and
                        ReviewInsightTopics.quote.isNotNull()
                }
                // Nejnaléhavější, pak nejnovější: citát má být ten, kvůli kterému se to řeší.
                .orderBy(ReviewInsights.urgency to SortOrder.DESC, Reviews.submittedAt to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    TopicQuote(
                        reviewId = row[Reviews.id],
                        quote = row[ReviewInsightTopics.quote].orEmpty(),
                        starRating = row[Reviews.starRating].toInt(),
                        platform = row[Reviews.platform],
                        appVersion = row[Reviews.appVersion],
                    )
                }
        }

    override fun daily(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter,
    ): List<DayCounts> =
        transaction(database) {
            val day = LocalDayOf(Reviews.submittedAt, timezone)
            val reviewCount = Reviews.id.count()
            val starSum = Reviews.starRating.sum()
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .select(day, ReviewInsights.sentiment, reviewCount, starSum)
                .where { scope(orgId, appId, from, to, filter) }
                .groupBy(day, ReviewInsights.sentiment)
                .map {
                    DayRow(
                        date = it[day],
                        sentiment = it[ReviewInsights.sentiment],
                        count = it[reviewCount].toInt(),
                        starSum = it[starSum]?.toInt() ?: 0,
                    )
                }.groupBy { it.date }
                .map { (date, rows) ->
                    DayCounts(
                        date = date,
                        reviews = rows.sumOf { it.count },
                        starSum = rows.sumOf { it.starSum },
                        sentiments = rows.groupBy { it.sentiment }.mapValues { (_, same) -> same.sumOf { it.count } },
                    )
                }.sortedBy { it.date }
        }

    override fun dailyTopics(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter,
    ): List<DayTopicCount> =
        transaction(database) {
            val day = LocalDayOf(Reviews.submittedAt, timezone)
            val mentions = ReviewInsightTopics.topicKey.count()
            Reviews
                .join(ReviewInsightTopics, JoinType.INNER, Reviews.id, ReviewInsightTopics.reviewId)
                .select(day, ReviewInsightTopics.topicKey, mentions)
                .where { scope(orgId, appId, from, to, filter) }
                .groupBy(day, ReviewInsightTopics.topicKey)
                .map { DayTopicCount(it[day], it[ReviewInsightTopics.topicKey], it[mentions].toInt()) }
                .sortedBy { it.date }
        }

    override fun territories(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<TerritoryAggregate> =
        transaction(database) {
            val reviewCount = Reviews.id.count()
            val starSum = Reviews.starRating.sum()
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .select(Reviews.territory, ReviewInsights.sentiment, reviewCount, starSum)
                .where { scope(orgId, appId, from, to, filter) and Reviews.territory.isNotNull() }
                .groupBy(Reviews.territory, ReviewInsights.sentiment)
                .map {
                    LabelRow(
                        label = it[Reviews.territory].orEmpty(),
                        sentiment = it[ReviewInsights.sentiment],
                        count = it[reviewCount].toInt(),
                        starSum = it[starSum]?.toInt() ?: 0,
                    )
                }.groupBy { it.label }
                .map { (territory, rows) ->
                    TerritoryAggregate(
                        territory = territory,
                        reviews = rows.sumOf { it.count },
                        negative = rows.filter { it.sentiment.isNegative() }.sumOf { it.count },
                        starSum = rows.sumOf { it.starSum },
                    )
                }.sortedByDescending { it.reviews }
        }

    override fun languages(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<LanguageAggregate> =
        transaction(database) {
            val reviewCount = Reviews.id.count()
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .select(ReviewInsights.language, ReviewInsights.sentiment, reviewCount)
                .where { scope(orgId, appId, from, to, filter) and ReviewInsights.language.isNotNull() }
                .groupBy(ReviewInsights.language, ReviewInsights.sentiment)
                .map {
                    LabelRow(
                        // Jazyk od modelu bývá `cs-CZ` i `cs`; do přehledu patří jedna položka.
                        label = it[ReviewInsights.language].orEmpty().substringBefore('-').lowercase(),
                        sentiment = it[ReviewInsights.sentiment],
                        count = it[reviewCount].toInt(),
                        starSum = 0,
                    )
                }.groupBy { it.label }
                .map { (language, rows) ->
                    LanguageAggregate(
                        language = language,
                        reviews = rows.sumOf { it.count },
                        negative = rows.filter { it.sentiment.isNegative() }.sumOf { it.count },
                    )
                }.sortedByDescending { it.reviews }
        }

    override fun versionWindows(
        orgId: OrganizationId,
        appId: AppId,
        since: Instant,
        minReviews: Int,
    ): List<VersionWindow> =
        transaction(database) {
            val reviewCount = Reviews.id.count()
            val firstSeen = Reviews.submittedAt.min()
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .select(Reviews.appVersion, Reviews.platform, reviewCount, firstSeen)
                .where {
                    (Reviews.orgId eq orgId) and
                        (Reviews.appId eq appId) and
                        (Reviews.submittedAt greaterEq since) and
                        Reviews.body.isNotNull() and
                        (Reviews.body neq "") and
                        Reviews.appVersion.isNotNull()
                }.groupBy(Reviews.appVersion, Reviews.platform)
                .mapNotNull { row ->
                    val count = row[reviewCount].toInt()
                    val start = row[firstSeen] ?: return@mapNotNull null
                    if (count < minReviews) return@mapNotNull null
                    VersionWindow(
                        version = row[Reviews.appVersion].orEmpty(),
                        platform = row[Reviews.platform],
                        firstSeen = start,
                        reviews = count,
                    )
                }.sortedByDescending { it.firstSeen }
        }

    /**
     * Zvedla naše odpověď hodnocení? Recenze, kterou člověk po odpovědi přepsal, má v
     * `review_revision` novější řádek s jiným počtem hvězd. Srovnává se poslední revize
     * **před** odpovědí s poslední revizí **po** ní; recenze bez druhé revize se nepočítá
     * ani do jedné strany — mlčení není souhlas.
     */
    private fun replyEffect(answeredAt: Map<ReviewId, Instant>): Pair<Int, Int> {
        if (answeredAt.isEmpty()) return 0 to 0
        val revisions =
            ReviewRevisions
                .select(ReviewRevisions.reviewId, ReviewRevisions.starRating, ReviewRevisions.observedAt)
                .where { ReviewRevisions.reviewId inList answeredAt.keys.toList() }
                .map { Triple(it[ReviewRevisions.reviewId], it[ReviewRevisions.starRating].toInt(), it[ReviewRevisions.observedAt]) }
                .groupBy { it.first }

        var uplifted = 0
        var dropped = 0
        answeredAt.forEach { (reviewId, repliedAt) ->
            val history = revisions[reviewId]?.sortedBy { it.third } ?: return@forEach
            val before = history.lastOrNull { it.third <= repliedAt }?.second ?: return@forEach
            val after = history.lastOrNull { it.third > repliedAt }?.second ?: return@forEach
            when {
                after > before -> uplifted++
                after < before -> dropped++
            }
        }
        return uplifted to dropped
    }

    private fun topics(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<TopicAggregate> {
        val mentions = ReviewInsightTopics.topicKey.count()
        val starSum = Reviews.starRating.sum()
        return Reviews
            .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
            .join(ReviewInsightTopics, JoinType.INNER, Reviews.id, ReviewInsightTopics.reviewId)
            .select(ReviewInsightTopics.topicKey, ReviewInsightTopics.sentiment, mentions, starSum)
            .where { scope(orgId, appId, from, to, filter) }
            .groupBy(ReviewInsightTopics.topicKey, ReviewInsightTopics.sentiment)
            .map {
                Quadruple(
                    it[ReviewInsightTopics.topicKey],
                    it[ReviewInsightTopics.sentiment],
                    it[mentions].toInt(),
                    it[starSum]?.toInt() ?: 0,
                )
            }.groupBy { it.first }
            .map { (key, rows) ->
                TopicAggregate(
                    key = key,
                    count = rows.sumOf { it.third },
                    negative = rows.filter { it.second == TopicSentiment.NEGATIVE }.sumOf { it.third },
                    positive = rows.filter { it.second == TopicSentiment.POSITIVE }.sumOf { it.third },
                    starSum = rows.sumOf { it.fourth },
                )
            }.sortedByDescending { it.count }
    }

    private fun versions(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<VersionAggregate> {
        val count = Reviews.id.count()
        val starSum = Reviews.starRating.sum()
        return Reviews
            .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
            .select(Reviews.appVersion, Reviews.platform, ReviewInsights.sentiment, count, starSum)
            .where { scope(orgId, appId, from, to, filter) and Reviews.appVersion.isNotNull() }
            .groupBy(Reviews.appVersion, Reviews.platform, ReviewInsights.sentiment)
            .map {
                VersionRow(
                    version = it[Reviews.appVersion].orEmpty(),
                    platform = it[Reviews.platform],
                    sentiment = it[ReviewInsights.sentiment],
                    count = it[count].toInt(),
                    starSum = it[starSum]?.toInt() ?: 0,
                )
            }.groupBy { it.version to it.platform }
            .map { (key, rows) ->
                VersionAggregate(
                    version = key.first,
                    platform = key.second,
                    count = rows.sumOf { it.count },
                    starSum = rows.sumOf { it.starSum },
                    // MIXED se počítá k záporným: recenze, která chválí i kritizuje, je práce.
                    negative = rows.filter { it.sentiment.isNegative() }.sumOf { it.count },
                )
            }.sortedByDescending { it.count }
    }

    /**
     * Rozbor standardně pracuje jen s recenzemi s textem. Volitelný pohled nálady zahrne i
     * samotné hvězdičky; sentiment těchto hodnocení už předtím bezpečně odvodila pravidla.
     */
    private fun scope(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): Op<Boolean> {
        var where =
            (Reviews.orgId eq orgId) and
                (Reviews.appId eq appId) and
                (Reviews.submittedAt greaterEq from) and
                (Reviews.submittedAt less to)
        if (filter.reviewScope == AnalysisReviewScope.WITH_TEXT) {
            where = where and Reviews.body.isNotNull() and (Reviews.body neq "")
        }
        filter.platform?.let { where = where and (Reviews.platform eq it) }
        filter.territory?.let { where = where and (Reviews.territory eq it) }
        filter.version?.let { where = where and (Reviews.appVersion eq it) }
        return where
    }

    private fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private fun OverallSentiment.isNegative(): Boolean = this == OverallSentiment.NEGATIVE || this == OverallSentiment.MIXED

    private data class Quadruple(
        val first: String,
        val second: TopicSentiment,
        val third: Int,
        val fourth: Int,
    )

    private data class VersionRow(
        val version: String,
        val platform: Platform,
        val sentiment: OverallSentiment,
        val count: Int,
        val starSum: Int,
    )

    private data class DayRow(
        val date: LocalDate,
        val sentiment: OverallSentiment,
        val count: Int,
        val starSum: Int,
    )

    private data class LabelRow(
        val label: String,
        val sentiment: OverallSentiment,
        val count: Int,
        val starSum: Int,
    )

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
    }
}

/**
 * Kalendářní den okamžiku v zóně aplikace (`(submitted_at AT TIME ZONE 'Europe/Prague')::date`).
 *
 * Buckety počítá Postgres schválně: rok recenzí by se kvůli rozdělení po dnech tahal do
 * paměti celý. Zóna jde do SQL jako literál — Exposed ho escapuje a `TimeZone.of` ji
 * ověřuje dřív, než se sem dostane.
 */
private class LocalDayOf(
    private val moment: Expression<Instant>,
    private val timezone: String,
) : Function<LocalDate>(KotlinLocalDateColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("((")
        queryBuilder.append(moment)
        queryBuilder.append(") AT TIME ZONE ")
        queryBuilder.append(stringLiteral(timezone))
        queryBuilder.append(")::date")
    }
}
