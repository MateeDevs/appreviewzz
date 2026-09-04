package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AppTopic
import cz.matee.appreviewzz.core.model.AppTopicId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewInsight
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.InsightCoverage
import cz.matee.appreviewzz.core.port.NewAppTopic
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Výklady v paměti se stejnou unikátností jako databáze: řádek na recenzi, zápis nahrazuje.
 * `listMissing` odpovídá na tutéž otázku jako SQL — co nemá výklad, co má neplatný.
 */
internal class FakeReviewInsightRepository(
    private val pending: MutableList<Review> = mutableListOf(),
) : ReviewInsightRepository {
    private val stored = mutableMapOf<ReviewId, ReviewInsight>()

    val saved: List<ReviewInsight> get() = stored.values.toList()

    fun awaiting(vararg reviews: Review) {
        pending += reviews
    }

    override fun upsert(
        orgId: OrganizationId,
        insight: NewReviewInsight,
        analyzedAt: Instant,
    ): ReviewInsight =
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
        ).also { stored[insight.reviewId] = it }

    override fun findByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): ReviewInsight? = stored[reviewId]?.takeIf { it.orgId == orgId }

    override fun findByReviews(
        orgId: OrganizationId,
        reviewIds: Collection<ReviewId>,
    ): Map<ReviewId, ReviewInsight> = reviewIds.mapNotNull { id -> findByReview(orgId, id)?.let { id to it } }.toMap()

    override fun listMissing(
        orgId: OrganizationId,
        appId: AppId,
        taxonomyVersion: String,
        limit: Int,
    ): List<Review> =
        pending
            .filter { review ->
                val insight = stored[review.id]
                insight == null || insight.contentHash != review.contentHash || insight.taxonomyVersion != taxonomyVersion
            }.take(limit)

    override fun coverage(
        orgId: OrganizationId,
        appId: AppId,
        taxonomyVersion: String,
    ): InsightCoverage = InsightCoverage(analyzed = stored.size, missing = listMissing(orgId, appId, taxonomyVersion, Int.MAX_VALUE).size)
}

internal class FakeAppTopicRepository(
    private val topics: MutableList<AppTopic> = mutableListOf(),
) : AppTopicRepository {
    fun put(
        orgId: OrganizationId,
        appId: AppId,
        name: String,
        description: String,
    ): AppTopic =
        AppTopic(
            id = AppTopicId(Uuid.random()),
            orgId = orgId,
            appId = appId,
            name = name,
            description = description,
            enabled = true,
            createdAt = Delivery.now,
        ).also { topics += it }

    override fun listEnabled(
        orgId: OrganizationId,
        appId: AppId,
    ): List<AppTopic> = topics.filter { it.orgId == orgId && it.appId == appId && it.enabled }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<AppTopic> = topics.filter { it.orgId == orgId && it.appId == appId }

    override fun create(
        orgId: OrganizationId,
        topic: NewAppTopic,
    ): AppTopic = unusedAnalysis()

    override fun findById(
        orgId: OrganizationId,
        id: AppTopicId,
    ): AppTopic? = topics.firstOrNull { it.orgId == orgId && it.id == id }

    override fun update(
        orgId: OrganizationId,
        id: AppTopicId,
        name: String,
        description: String,
        enabled: Boolean,
    ): AppTopic? = unusedAnalysis()

    override fun delete(
        orgId: OrganizationId,
        id: AppTopicId,
    ): Boolean = unusedAnalysis()
}

/** Provider, kterému test nadiktuje odpověď a který si pamatuje, co dostal. */
internal class FakeAnalysisProvider(
    private val results: MutableList<AnalysisResult> = mutableListOf(),
) : ReviewAnalysisProvider {
    val requests = mutableListOf<AnalysisRequest>()
    private var echo: ((String) -> ReviewAnalysis)? = null

    fun answer(result: AnalysisResult) {
        results += result
    }

    /**
     * Odpověď složená z ID, která přišla — jako to dělá skutečný model. Testy tak nemusí
     * znát ID recenze dřív, než ji založí.
     */
    fun echo(
        model: String = "test-model",
        analysis: (String) -> ReviewAnalysis,
    ) {
        echo = analysis
        results += AnalysisResult.Analyzed(emptyList(), model)
    }

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        requests += request
        echo?.let { build -> return AnalysisResult.Analyzed(request.items.map { build(it.id) }, "test-model") }
        return if (results.isEmpty()) AnalysisResult.Unavailable else results.removeAt(0)
    }
}

private fun unusedAnalysis(): Nothing = error("Metoda se v testu rozborů nepoužívá")
