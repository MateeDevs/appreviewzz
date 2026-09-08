package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AppTopic
import cz.matee.appreviewzz.core.model.AppTopicId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewInsight
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisDigestRepository
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.DayCounts
import cz.matee.appreviewzz.core.port.DayTopicCount
import cz.matee.appreviewzz.core.port.InsightCoverage
import cz.matee.appreviewzz.core.port.LanguageAggregate
import cz.matee.appreviewzz.core.port.NewAppTopic
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import cz.matee.appreviewzz.core.port.TerritoryAggregate
import cz.matee.appreviewzz.core.port.TopicQuote
import cz.matee.appreviewzz.core.port.VersionWindow
import kotlinx.datetime.LocalDate
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

    override fun topicCounts(
        orgId: OrganizationId,
        appId: AppId,
        since: Instant,
    ): Map<String, Int> =
        stored.values
            .filter { it.orgId == orgId && it.appId == appId && it.analyzedAt >= since }
            .flatMap { it.topics }
            .groupingBy { it.key }
            .eachCount()
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

/** Agregace v paměti: test jí nadiktuje čísla, která by jinak spočítalo SQL. */
internal class FakeAnalysisAggregateRepository(
    private val current: AnalysisPeriod = AnalysisPeriod.EMPTY,
    private val previous: AnalysisPeriod = AnalysisPeriod.EMPTY,
    private val replies: ReplyStats = ReplyStats(total = 0, replied = 0, medianHours = null),
    private val quote: TopicQuote? = null,
    private val since: Instant? = null,
    private val days: List<DayCounts> = emptyList(),
    private val dayTopics: List<DayTopicCount> = emptyList(),
    private val markets: List<TerritoryAggregate> = emptyList(),
    private val tongues: List<LanguageAggregate> = emptyList(),
    private val versions: List<VersionWindow> = emptyList(),
) : AnalysisAggregateRepository {
    val requestedPeriods = mutableListOf<Pair<Instant, Instant>>()

    override fun aggregate(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): AnalysisPeriod {
        requestedPeriods += from to to
        // Rozlišuje se podle hranice období, ne podle pořadí volání: use case se ptá dvakrát
        // na běh a druhý běh by jinak dostal čísla předchozího týdne jako aktuální.
        val latest = requestedPeriods.maxOf { it.first }
        return if (from == latest) current else previous
    }

    override fun replyStats(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): ReplyStats = replies

    override fun dataSince(
        orgId: OrganizationId,
        appId: AppId,
    ): Instant? = since

    override fun topQuotes(
        orgId: OrganizationId,
        appId: AppId,
        topicKey: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<TopicQuote> = listOfNotNull(quote).take(limit)

    override fun daily(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter,
    ): List<DayCounts> = days

    override fun dailyTopics(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter,
    ): List<DayTopicCount> = dayTopics

    override fun territories(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<TerritoryAggregate> = markets

    override fun languages(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter,
    ): List<LanguageAggregate> = tongues

    override fun versionWindows(
        orgId: OrganizationId,
        appId: AppId,
        since: Instant,
        minReviews: Int,
    ): List<VersionWindow> = versions
}

/** Rezervace období se stejnou unikátností jako databáze: (kanál, začátek období). */
internal class FakeAnalysisDigestRepository(
    /** Konec posledního odeslaného období — odsud navazuje další rozbor. */
    private var lastEnd: LocalDate? = null,
) : AnalysisDigestRepository {
    private val claimed = mutableSetOf<Pair<ChannelId, LocalDate>>()

    override fun claim(
        orgId: OrganizationId,
        appId: AppId,
        channelId: ChannelId,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        sentAt: Instant,
    ): Boolean {
        if (!claimed.add(channelId to periodStart)) return false
        lastEnd = maxOf(periodEnd, lastEnd ?: periodEnd)
        return true
    }

    override fun lastSent(
        orgId: OrganizationId,
        channelId: ChannelId,
    ): LocalDate? = claimed.filter { it.first == channelId }.maxOfOrNull { it.second }

    override fun lastPeriodEnd(
        orgId: OrganizationId,
        appId: AppId,
    ): LocalDate? = lastEnd
}

internal class FakeOrganizationRepository(
    private val organizations: MutableList<Organization> = mutableListOf(),
) : OrganizationRepository {
    fun put(organization: Organization): Organization = organization.also { organizations += it }

    override fun findById(id: OrganizationId): Organization? = organizations.firstOrNull { it.id == id }

    override fun findBySlug(slug: String): Organization? = organizations.firstOrNull { it.slug == slug }

    override fun list(): List<Organization> = organizations

    override fun create(
        name: String,
        slug: String,
    ): Organization = error("Zakládání organizace se v testu rozborů nepoužívá")

    override fun updatePlan(
        id: OrganizationId,
        plan: cz.matee.appreviewzz.core.model.OrgPlan,
    ): Organization? = error("Změna plánu se v testu rozborů nepoužívá")
}
