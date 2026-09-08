package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.AnalysisAlertId
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AppTopic
import cz.matee.appreviewzz.core.model.AppTopicId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.InsightReport
import cz.matee.appreviewzz.core.model.InsightReportId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReportSnapshot
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewInsight
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.port.AnalysisAlertRepository
import cz.matee.appreviewzz.core.port.AnalysisDigestRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.InsightCoverage
import cz.matee.appreviewzz.core.port.InsightReportRepository
import cz.matee.appreviewzz.core.port.NewAnalysisAlert
import cz.matee.appreviewzz.core.port.NewAppTopic
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import cz.matee.appreviewzz.persistence.schema.AnalysisAlerts
import cz.matee.appreviewzz.persistence.schema.AnalysisDigests
import cz.matee.appreviewzz.persistence.schema.AppTopics
import cz.matee.appreviewzz.persistence.schema.InsightReports
import cz.matee.appreviewzz.persistence.schema.ReviewInsightTopics
import cz.matee.appreviewzz.persistence.schema.ReviewInsights
import cz.matee.appreviewzz.persistence.schema.Reviews
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Výklady recenzí (F8). Zápis je nahrazení: recenze má nejvýš jeden platný výklad a při
 * přeanalyzování ten starý mizí i s tématy — historie výkladů by se k ničemu nepoužila
 * a jen by rozbila `PRIMARY KEY (review_id, topic_key)`.
 */
class ExposedReviewInsightRepository(
    private val database: ExposedDatabase,
) : ReviewInsightRepository {
    override fun upsert(
        orgId: OrganizationId,
        insight: NewReviewInsight,
        analyzedAt: Instant,
    ): ReviewInsight =
        transaction(database) {
            val exists =
                ReviewInsights
                    .selectAll()
                    .where { (ReviewInsights.orgId eq orgId) and (ReviewInsights.reviewId eq insight.reviewId) }
                    .any()
            if (exists) {
                ReviewInsights.update({ ReviewInsights.reviewId eq insight.reviewId }) { it.write(insight, analyzedAt) }
                ReviewInsightTopics.deleteWhere { ReviewInsightTopics.reviewId eq insight.reviewId }
            } else {
                ReviewInsights.insert {
                    it[reviewId] = insight.reviewId
                    it[ReviewInsights.orgId] = orgId
                    it[appId] = insight.appId
                    it.write(insight, analyzedAt)
                }
            }
            insight.topics.forEach { mention ->
                ReviewInsightTopics.insert {
                    it[reviewId] = insight.reviewId
                    it[topicKey] = mention.key
                    it[sentiment] = mention.sentiment
                    it[quote] = mention.quote
                }
            }
            ReviewInsight(
                reviewId = insight.reviewId,
                orgId = orgId,
                appId = insight.appId,
                contentHash = insight.contentHash,
                taxonomyVersion = insight.taxonomyVersion,
                promptVersion = insight.promptVersion,
                model = insight.model,
                sentiment = insight.sentiment,
                type = insight.type,
                urgency = insight.urgency,
                language = insight.language,
                translation = insight.translation,
                topics = insight.topics,
                analyzedAt = analyzedAt,
            )
        }

    override fun findByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): ReviewInsight? = findByReviews(orgId, listOf(reviewId))[reviewId]

    override fun findByReviews(
        orgId: OrganizationId,
        reviewIds: Collection<ReviewId>,
    ): Map<ReviewId, ReviewInsight> {
        if (reviewIds.isEmpty()) return emptyMap()
        return transaction(database) {
            val ids = reviewIds.toList()
            val topics = topicsOf(ids)
            ReviewInsights
                .selectAll()
                .where { (ReviewInsights.orgId eq orgId) and (ReviewInsights.reviewId inList ids) }
                .associate { row ->
                    val id = row[ReviewInsights.reviewId]
                    id to row.toReviewInsight(topics[id].orEmpty())
                }
        }
    }

    override fun listMissing(
        orgId: OrganizationId,
        appId: AppId,
        taxonomyVersion: String,
        limit: Int,
    ): List<Review> =
        transaction(database) {
            // LEFT JOIN, ne poddotaz: chybějící výklad i neplatný výklad je tatáž otázka
            // („co je potřeba přeanalyzovat") a dvě cesty by se rozešly.
            Reviews
                .join(ReviewInsights, JoinType.LEFT, Reviews.id, ReviewInsights.reviewId)
                .selectAll()
                .where {
                    (Reviews.orgId eq orgId) and
                        (Reviews.appId eq appId) and
                        (
                            ReviewInsights.reviewId.isNull() or
                                (ReviewInsights.contentHash neq Reviews.contentHash) or
                                (ReviewInsights.taxonomyVersion neq taxonomyVersion)
                        )
                }.orderBy(Reviews.submittedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toReview() }
        }

    override fun coverage(
        orgId: OrganizationId,
        appId: AppId,
        taxonomyVersion: String,
    ): InsightCoverage =
        transaction(database) {
            val total =
                Reviews
                    .selectAll()
                    .where { (Reviews.orgId eq orgId) and (Reviews.appId eq appId) }
                    .count()
                    .toInt()
            val analyzed =
                Reviews
                    .join(ReviewInsights, JoinType.INNER, Reviews.id, ReviewInsights.reviewId)
                    .selectAll()
                    .where {
                        (Reviews.orgId eq orgId) and
                            (Reviews.appId eq appId) and
                            (ReviewInsights.contentHash eq Reviews.contentHash) and
                            (ReviewInsights.taxonomyVersion eq taxonomyVersion)
                    }.count()
                    .toInt()
            InsightCoverage(analyzed = analyzed, missing = total - analyzed)
        }

    override fun topicCounts(
        orgId: OrganizationId,
        appId: AppId,
        since: Instant,
    ): Map<String, Int> =
        transaction(database) {
            val count = ReviewInsightTopics.topicKey.count()
            ReviewInsights
                .join(ReviewInsightTopics, JoinType.INNER, ReviewInsights.reviewId, ReviewInsightTopics.reviewId)
                .select(ReviewInsightTopics.topicKey, count)
                .where {
                    (ReviewInsights.orgId eq orgId) and
                        (ReviewInsights.appId eq appId) and
                        (ReviewInsights.analyzedAt greaterEq since)
                }.groupBy(ReviewInsightTopics.topicKey)
                .associate { it[ReviewInsightTopics.topicKey] to it[count].toInt() }
        }

    private fun topicsOf(ids: List<ReviewId>): Map<ReviewId, List<TopicMention>> =
        ReviewInsightTopics
            .selectAll()
            .where { ReviewInsightTopics.reviewId inList ids }
            .groupBy({ it[ReviewInsightTopics.reviewId] }, { it.toTopicMention() })

    private fun UpdateBuilder<*>.write(
        insight: NewReviewInsight,
        analyzedAt: Instant,
    ) {
        this[ReviewInsights.contentHash] = insight.contentHash
        this[ReviewInsights.taxonomyVersion] = insight.taxonomyVersion
        this[ReviewInsights.promptVersion] = insight.promptVersion
        this[ReviewInsights.model] = insight.model
        this[ReviewInsights.sentiment] = insight.sentiment
        this[ReviewInsights.reviewType] = insight.type
        this[ReviewInsights.urgency] = insight.urgency
        this[ReviewInsights.language] = insight.language
        this[ReviewInsights.translation] = insight.translation
        this[ReviewInsights.analyzedAt] = analyzedAt
    }
}

class ExposedAppTopicRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : AppTopicRepository {
    override fun create(
        orgId: OrganizationId,
        topic: NewAppTopic,
    ): AppTopic =
        transaction(database) {
            val created =
                AppTopic(
                    id = AppTopicId(Uuid.random()),
                    orgId = orgId,
                    appId = topic.appId,
                    name = topic.name,
                    description = topic.description,
                    enabled = true,
                    createdAt = clock.now(),
                )
            AppTopics.insert {
                it[id] = created.id
                it[AppTopics.orgId] = created.orgId
                it[appId] = created.appId
                it[name] = created.name
                it[description] = created.description
                it[enabled] = true
                it[createdAt] = created.createdAt
            }
            created
        }

    override fun findById(
        orgId: OrganizationId,
        id: AppTopicId,
    ): AppTopic? =
        transaction(database) {
            AppTopics
                .selectAll()
                .where { (AppTopics.orgId eq orgId) and (AppTopics.id eq id) }
                .firstOrNull()
                ?.toAppTopic()
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<AppTopic> =
        transaction(database) {
            AppTopics
                .selectAll()
                .where { (AppTopics.orgId eq orgId) and (AppTopics.appId eq appId) }
                .orderBy(AppTopics.name to SortOrder.ASC)
                .map { it.toAppTopic() }
        }

    override fun listEnabled(
        orgId: OrganizationId,
        appId: AppId,
    ): List<AppTopic> = listByApp(orgId, appId).filter { it.enabled }

    override fun update(
        orgId: OrganizationId,
        id: AppTopicId,
        name: String,
        description: String,
        enabled: Boolean,
    ): AppTopic? =
        transaction(database) {
            val updated =
                AppTopics.update({ (AppTopics.orgId eq orgId) and (AppTopics.id eq id) }) {
                    it[AppTopics.name] = name
                    it[AppTopics.description] = description
                    it[AppTopics.enabled] = enabled
                }
            if (updated == 0) null else findById(orgId, id)
        }

    override fun delete(
        orgId: OrganizationId,
        id: AppTopicId,
    ): Boolean =
        transaction(database) {
            AppTopics.deleteWhere { (AppTopics.orgId eq orgId) and (AppTopics.id eq id) } > 0
        }
}

class ExposedAnalysisDigestRepository(
    private val database: ExposedDatabase,
) : AnalysisDigestRepository {
    override fun claim(
        orgId: OrganizationId,
        appId: AppId,
        channelId: ChannelId,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        sentAt: Instant,
    ): Boolean =
        transaction(database) {
            val taken =
                AnalysisDigests
                    .selectAll()
                    .where { (AnalysisDigests.channelId eq channelId) and (AnalysisDigests.periodStart eq periodStart) }
                    .any()
            if (taken) {
                false
            } else {
                AnalysisDigests.insert {
                    it[AnalysisDigests.orgId] = orgId
                    it[AnalysisDigests.appId] = appId
                    it[AnalysisDigests.channelId] = channelId
                    it[AnalysisDigests.periodStart] = periodStart
                    it[AnalysisDigests.periodEnd] = periodEnd
                    it[AnalysisDigests.sentAt] = sentAt
                }
                true
            }
        }

    override fun lastSent(
        orgId: OrganizationId,
        channelId: ChannelId,
    ): LocalDate? =
        transaction(database) {
            AnalysisDigests
                .selectAll()
                .where { (AnalysisDigests.orgId eq orgId) and (AnalysisDigests.channelId eq channelId) }
                .orderBy(AnalysisDigests.periodStart to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(AnalysisDigests.periodStart)
        }

    override fun lastPeriodEnd(
        orgId: OrganizationId,
        appId: AppId,
    ): LocalDate? =
        transaction(database) {
            AnalysisDigests
                .selectAll()
                .where { (AnalysisDigests.orgId eq orgId) and (AnalysisDigests.appId eq appId) }
                .orderBy(AnalysisDigests.periodEnd to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(AnalysisDigests.periodEnd)
        }
}

/**
 * Zaznamenané výkyvy (F8/B4). Zápis se řídí týmž pravidlem jako rezervace rozboru:
 * napřed se rezervuje řádek, teprve pak se posílá zpráva. Když padneme mezi tím, přijde
 * o zprávu jeden den — a to je pořád lepší než deset zpráv o jednom výkyvu.
 */
class ExposedAnalysisAlertRepository(
    private val database: ExposedDatabase,
) : AnalysisAlertRepository {
    override fun insertIfAbsent(
        orgId: OrganizationId,
        alert: NewAnalysisAlert,
        createdAt: Instant,
    ): AnalysisAlert? =
        transaction(database) {
            val existing =
                AnalysisAlerts
                    .selectAll()
                    .where {
                        (AnalysisAlerts.appId eq alert.appId) and
                            (AnalysisAlerts.kind eq alert.kind) and
                            (AnalysisAlerts.windowDate eq alert.windowDate) and
                            // Exposed nemá výraz pro „obojí NULL"; u druhu bez tématu se
                            // porovnává explicitně, jinak by `= NULL` nikdy nesedělo.
                            (alert.topicKey?.let { AnalysisAlerts.topicKey eq it } ?: AnalysisAlerts.topicKey.isNull())
                    }.any()
            if (existing) {
                null
            } else {
                val id = AnalysisAlertId(Uuid.random())
                AnalysisAlerts.insert {
                    it[AnalysisAlerts.id] = id
                    it[AnalysisAlerts.orgId] = orgId
                    it[appId] = alert.appId
                    it[kind] = alert.kind
                    it[topicKey] = alert.topicKey
                    it[windowDate] = alert.windowDate
                    it[observed] = alert.observed
                    it[expected] = alert.expected.toBigDecimal()
                    it[zScore] = alert.zScore.toBigDecimal()
                    it[AnalysisAlerts.createdAt] = createdAt
                }
                AnalysisAlert(
                    id = id,
                    orgId = orgId,
                    appId = alert.appId,
                    kind = alert.kind,
                    topicKey = alert.topicKey,
                    windowDate = alert.windowDate,
                    observed = alert.observed,
                    expected = alert.expected,
                    zScore = alert.zScore,
                    createdAt = createdAt,
                )
            }
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        since: LocalDate,
        limit: Int,
    ): List<AnalysisAlert> =
        transaction(database) {
            AnalysisAlerts
                .selectAll()
                .where {
                    (AnalysisAlerts.orgId eq orgId) and
                        (AnalysisAlerts.appId eq appId) and
                        (AnalysisAlerts.windowDate greaterEq since)
                }.orderBy(AnalysisAlerts.windowDate to SortOrder.DESC, AnalysisAlerts.observed to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    AnalysisAlert(
                        id = row[AnalysisAlerts.id],
                        orgId = row[AnalysisAlerts.orgId],
                        appId = row[AnalysisAlerts.appId],
                        kind = row[AnalysisAlerts.kind],
                        topicKey = row[AnalysisAlerts.topicKey],
                        windowDate = row[AnalysisAlerts.windowDate],
                        observed = row[AnalysisAlerts.observed],
                        expected = row[AnalysisAlerts.expected].toDouble(),
                        zScore = row[AnalysisAlerts.zScore].toDouble(),
                        createdAt = row[AnalysisAlerts.createdAt],
                    )
                }
        }
}

/**
 * Měsíční reporty (F8/C1).
 *
 * Přegenerování měsíce **nechává token na pokoji**: odkaz, který agentura poslala klientovi,
 * nesmí přestat platit kvůli tomu, že se doplnily výklady. Zrušit sdílení jde jen výslovně.
 */
class ExposedInsightReportRepository(
    private val database: ExposedDatabase,
) : InsightReportRepository {
    override fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        snapshot: ReportSnapshot,
        createdAt: Instant,
    ): InsightReport =
        transaction(database) {
            val existing =
                InsightReports
                    .selectAll()
                    .where { (InsightReports.appId eq appId) and (InsightReports.periodStart eq periodStart) }
                    .firstOrNull()
            if (existing != null) {
                InsightReports.update({ InsightReports.id eq existing[InsightReports.id] }) {
                    it[InsightReports.periodEnd] = periodEnd
                    it[aggregates] = snapshot
                }
                existing.toReport().copy(periodEnd = periodEnd, snapshot = snapshot)
            } else {
                val id = InsightReportId(Uuid.random())
                InsightReports.insert {
                    it[InsightReports.id] = id
                    it[InsightReports.orgId] = orgId
                    it[InsightReports.appId] = appId
                    it[InsightReports.periodStart] = periodStart
                    it[InsightReports.periodEnd] = periodEnd
                    it[aggregates] = snapshot
                    it[shareToken] = null
                    it[InsightReports.createdAt] = createdAt
                }
                InsightReport(
                    id = id,
                    orgId = orgId,
                    appId = appId,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    snapshot = snapshot,
                    shareToken = null,
                    createdAt = createdAt,
                )
            }
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        limit: Int,
    ): List<InsightReport> =
        transaction(database) {
            InsightReports
                .selectAll()
                .where { (InsightReports.orgId eq orgId) and (InsightReports.appId eq appId) }
                .orderBy(InsightReports.periodStart to SortOrder.DESC)
                .limit(limit)
                .map { it.toReport() }
        }

    override fun findById(
        orgId: OrganizationId,
        id: InsightReportId,
    ): InsightReport? =
        transaction(database) {
            InsightReports
                .selectAll()
                .where { (InsightReports.orgId eq orgId) and (InsightReports.id eq id) }
                .firstOrNull()
                ?.toReport()
        }

    override fun findByShareToken(token: String): InsightReport? =
        transaction(database) {
            InsightReports
                .selectAll()
                .where { InsightReports.shareToken eq token }
                .firstOrNull()
                ?.toReport()
        }

    override fun setShareToken(
        orgId: OrganizationId,
        id: InsightReportId,
        token: String?,
    ): Boolean =
        transaction(database) {
            InsightReports.update({ (InsightReports.orgId eq orgId) and (InsightReports.id eq id) }) {
                it[shareToken] = token
            } > 0
        }

    private fun ResultRow.toReport() =
        InsightReport(
            id = this[InsightReports.id],
            orgId = this[InsightReports.orgId],
            appId = this[InsightReports.appId],
            periodStart = this[InsightReports.periodStart],
            periodEnd = this[InsightReports.periodEnd],
            snapshot = this[InsightReports.aggregates],
            shareToken = this[InsightReports.shareToken],
            createdAt = this[InsightReports.createdAt],
        )
}
