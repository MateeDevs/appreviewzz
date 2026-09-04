package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.port.VersionAggregate
import cz.matee.appreviewzz.persistence.schema.Replies
import cz.matee.appreviewzz.persistence.schema.ReviewInsightTopics
import cz.matee.appreviewzz.persistence.schema.ReviewInsights
import cz.matee.appreviewzz.persistence.schema.Reviews
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.sum
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
class ExposedAnalysisAggregateRepository(
    private val database: ExposedDatabase,
) : AnalysisAggregateRepository {
    override fun aggregate(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
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
                    .where { scope(orgId, appId, from, to) }
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
                topics = topics(orgId, appId, from, to),
                versions = versions(orgId, appId, from, to),
            )
        }

    override fun replyStats(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
    ): ReplyStats =
        transaction(database) {
            val total =
                Reviews
                    .selectAll()
                    .where { scope(orgId, appId, from, to) }
                    .count()
                    .toInt()

            // Publikované odpovědi na recenze z období; medián se počítá v paměti, protože
            // jde nejvýš o stovky čísel a `percentile_cont` by znamenalo syrové SQL.
            val hours =
                Reviews
                    .join(Replies, JoinType.INNER, Reviews.id, Replies.reviewId)
                    .select(Reviews.submittedAt, Replies.publishedAt)
                    .where {
                        scope(orgId, appId, from, to) and
                            (Replies.status eq ReplyStatus.PUBLISHED) and
                            Replies.publishedAt.isNotNull()
                    }.mapNotNull { row ->
                        row[Replies.publishedAt]?.let { published ->
                            (published - row[Reviews.submittedAt]).inWholeMinutes / MINUTES_PER_HOUR
                        }
                    }.sorted()

            ReplyStats(total = total, replied = hours.size, medianHours = median(hours))
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

    override fun topQuote(
        orgId: OrganizationId,
        appId: AppId,
        topicKey: String,
        from: Instant,
        to: Instant,
    ): TopicQuote? =
        transaction(database) {
            Reviews
                .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                .join(ReviewInsightTopics, JoinType.INNER, Reviews.id, ReviewInsightTopics.reviewId)
                .selectAll()
                .where {
                    scope(orgId, appId, from, to) and
                        (ReviewInsightTopics.topicKey eq topicKey) and
                        ReviewInsightTopics.quote.isNotNull()
                }
                // Nejnaléhavější, pak nejnovější: citát má být ten, kvůli kterému se to řeší.
                .orderBy(ReviewInsights.urgency to SortOrder.DESC, Reviews.submittedAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    TopicQuote(
                        reviewId = row[Reviews.id],
                        quote = row[ReviewInsightTopics.quote].orEmpty(),
                        starRating = row[Reviews.starRating].toInt(),
                        platform = row[Reviews.platform],
                        appVersion = row[Reviews.appVersion],
                    )
                }
        }

    private fun topics(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
    ): List<TopicAggregate> {
        val mentions = ReviewInsightTopics.topicKey.count()
        val starSum = Reviews.starRating.sum()
        return Reviews
            .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
            .join(ReviewInsightTopics, JoinType.INNER, Reviews.id, ReviewInsightTopics.reviewId)
            .select(ReviewInsightTopics.topicKey, ReviewInsightTopics.sentiment, mentions, starSum)
            .where { scope(orgId, appId, from, to) }
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
    ): List<VersionAggregate> {
        val count = Reviews.id.count()
        val starSum = Reviews.starRating.sum()
        return Reviews
            .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
            .select(Reviews.appVersion, Reviews.platform, ReviewInsights.sentiment, count, starSum)
            .where { scope(orgId, appId, from, to) and Reviews.appVersion.isNotNull() }
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
                    negative =
                        rows
                            .filter { it.sentiment == OverallSentiment.NEGATIVE || it.sentiment == OverallSentiment.MIXED }
                            .sumOf { it.count },
                )
            }.sortedByDescending { it.count }
    }

    private fun scope(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
    ) = (Reviews.orgId eq orgId) and
        (Reviews.appId eq appId) and
        (Reviews.submittedAt greaterEq from) and
        (Reviews.submittedAt less to)

    private fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

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

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
    }
}
