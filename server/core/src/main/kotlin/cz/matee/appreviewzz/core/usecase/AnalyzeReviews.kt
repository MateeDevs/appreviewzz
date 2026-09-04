package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.ReviewInsightSummary
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewInsight
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisItem
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.CustomTopic
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import cz.matee.appreviewzz.core.port.ReviewInsightRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.text.Normalizer
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Jak dopadl pokus o výklad jedné recenze. Žádná z hodnot není chyba doručení. */
sealed interface AnalysisOutcome {
    data class Analyzed(
        val insight: ReviewInsight,
    ) : AnalysisOutcome

    /** Recenze už platný výklad má — nic se neposílalo ani nepřepisovalo. */
    data class AlreadyAnalyzed(
        val insight: ReviewInsight,
    ) : AnalysisOutcome

    /** Provider není nastavený. Běžný stav instalace bez AI. */
    data object Unavailable : AnalysisOutcome

    data class Failed(
        val message: String,
    ) : AnalysisOutcome

    data object ReviewNotFound : AnalysisOutcome

    val insightOrNull: ReviewInsight?
        get() =
            when (this) {
                is Analyzed -> insight
                is AlreadyAnalyzed -> insight
                else -> null
            }
}

/**
 * Výsledek dávkového běhu. `hasMore` říká, jestli se má job naplánovat znovu — backfill
 * historie se tak rozloží do krátkých běhů místo jedné hodinové transakce.
 */
data class AnalysisReport(
    val orgId: OrganizationId,
    val appId: AppId,
    val analyzed: Int = 0,
    val failed: Int = 0,
    /** Recenze, které model v odpovědi vynechal i po doposlání. */
    val skipped: Int = 0,
    val hasMore: Boolean = false,
    val unavailable: Boolean = false,
    val error: String? = null,
)

/**
 * Tagování recenzí (F8).
 *
 * Dvě cesty dovnitř, obě sem: [ensureAnalyzed] před doručením (jedna recenze, aby štítky byly
 * hned v první zprávě) a [analyzeMissing] dávkově pro historii a pro recenze, které se
 * nedoručují. Selhání AI **nikdy neblokuje doručení** — recenze odejde bez štítků a backfill
 * ji zkusí příště.
 *
 * Ověřování výstupu modelu sedí tady, ne v provideru: jen tady je vidět původní text recenze,
 * proti kterému se ověřuje citát, a jen tady je známý seznam vlastních témat aplikace.
 */
class AnalyzeReviewsUseCase(
    private val apps: AppRepository,
    private val reviews: ReviewRepository,
    private val insights: ReviewInsightRepository,
    private val appTopics: AppTopicRepository,
    private val provider: ReviewAnalysisProvider,
    private val clock: Clock = Clock.System,
) {
    /**
     * Výklad jedné recenze pro doručení. Když už platný výklad je, nic se neposílá — cena
     * za tagování se platí jednou za znění recenze, ne při každém pokusu o doručení.
     */
    suspend fun ensureAnalyzed(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): AnalysisOutcome {
        val review = reviews.findById(orgId, reviewId) ?: return AnalysisOutcome.ReviewNotFound
        insights.findByReview(orgId, reviewId)?.takeIf { it.isFresh(review) }?.let {
            return AnalysisOutcome.AlreadyAnalyzed(it)
        }
        val app = apps.findById(orgId, review.appId) ?: return AnalysisOutcome.ReviewNotFound

        if (review.hasNoText()) return AnalysisOutcome.Analyzed(store(orgId, review, fromRules(review)))

        return when (val result = analyze(app, listOf(review))) {
            is AnalysisResult.Unavailable -> AnalysisOutcome.Unavailable
            is AnalysisResult.Failed -> AnalysisOutcome.Failed(result.message)
            is AnalysisResult.Analyzed -> {
                val analysis =
                    result.items.firstOrNull { it.id == review.id.toString() }
                        ?: return AnalysisOutcome.Failed("Model recenzi ${review.id} ve výsledku nevrátil")
                AnalysisOutcome.Analyzed(store(orgId, review, analysis.sanitized(review, result.model)))
            }
        }
    }

    /**
     * Dotagování toho, co výklad nemá nebo ho má neplatný. Bere i `SUPPRESSED` a `IGNORED`
     * recenze: do kanálu nejdou, ale v podílech a trendech chybět nesmí.
     */
    suspend fun analyzeMissing(
        orgId: OrganizationId,
        appId: AppId,
        limit: Int = DEFAULT_LIMIT,
    ): AnalysisReport {
        val app = apps.findById(orgId, appId) ?: return AnalysisReport(orgId, appId, error = "Taková aplikace tu není")
        val pending = insights.listMissing(orgId, appId, Topic.TAXONOMY_VERSION, limit)
        if (pending.isEmpty()) return AnalysisReport(orgId, appId)

        // Recenze bez textu (na Androidu jich je zhruba každá druhá) do AI nechodí vůbec:
        // sentiment se z hvězd odvodí spolehlivě a zadarmo. Bez toho by v podílech chyběly.
        val (textless, withText) = pending.partition { it.hasNoText() }
        textless.forEach { store(orgId, it, fromRules(it)) }

        var analyzed = textless.size
        var failed = 0
        var skipped = 0
        var error: String? = null

        withText.chunked(BATCH_SIZE).forEach { batch ->
            // Dlouhá recenze jde samostatně: v dávce by zabrala rozpočet ostatním a model
            // by pak na konci odpovědi začal položky vynechávat.
            val (long, short) = batch.partition { it.textLength() > LONG_REVIEW_LENGTH }
            val groups = short.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty() + long.map { listOf(it) }
            groups.forEach { group ->
                when (val result = analyze(app, group)) {
                    is AnalysisResult.Unavailable -> return AnalysisReport(orgId, appId, analyzed = analyzed, unavailable = true)
                    is AnalysisResult.Failed -> {
                        failed += group.size
                        error = error ?: result.message
                    }

                    is AnalysisResult.Analyzed -> {
                        val byId = result.items.associateBy { it.id }
                        group.forEach { review ->
                            val analysis = byId[review.id.toString()]
                            if (analysis == null) {
                                skipped++
                            } else {
                                store(orgId, review, analysis.sanitized(review, result.model))
                                analyzed++
                            }
                        }
                    }
                }
            }
        }

        logger.info { "Rozbor aplikace $appId: vyloženo=$analyzed selhalo=$failed vynecháno=$skipped" }
        return AnalysisReport(
            orgId = orgId,
            appId = appId,
            analyzed = analyzed,
            failed = failed,
            skipped = skipped,
            // Další běh má smysl jen tehdy, když se něco povedlo — jinak by se opakovalo selhání dokola.
            hasMore = pending.size >= limit && analyzed > 0,
            error = error,
        )
    }

    /**
     * Výklad přeložený do jazyka kanálu. Překlad klíčů na labely sedí tady, protože tady je
     * po ruce seznam vlastních témat aplikace — kanálový modul taxonomii znát nemá.
     */
    fun summarize(
        insight: ReviewInsight,
        locale: MessageLocale,
    ): ReviewInsightSummary {
        val custom = appTopics.listByApp(insight.orgId, insight.appId).associate { it.key to it.name }
        return ReviewInsightSummary(
            topics = insight.topics.mapNotNull { Topic.ofKey(it.key)?.label(locale) ?: custom[it.key] },
            type = insight.type,
            urgency = insight.urgency,
        )
    }

    private suspend fun analyze(
        app: App,
        batch: List<Review>,
    ): AnalysisResult =
        provider.analyze(
            AnalysisRequest(
                appName = app.name,
                instructions = app.aiInstructions,
                teamLocale = app.locale,
                customTopics =
                    appTopics.listEnabled(app.orgId, app.id).map {
                        CustomTopic(key = it.key, name = it.name, description = it.description)
                    },
                items =
                    batch.map {
                        AnalysisItem(
                            id = it.id.toString(),
                            platform = it.platform,
                            starRating = it.starRating,
                            title = it.title,
                            body = it.body,
                            appVersion = it.appVersion,
                        )
                    },
            ),
        )

    private fun store(
        orgId: OrganizationId,
        review: Review,
        insight: NewReviewInsight,
    ): ReviewInsight = insights.upsert(orgId, insight, clock.now())

    /**
     * Kontrola toho, co model vrátil. Tři věci, na kterých klasifikace nejčastěji ujede:
     * vymyšlené téma, vymyšlený citát a `other` přilepené k něčemu konkrétnímu.
     */
    private fun ReviewAnalysis.sanitized(
        review: Review,
        model: String,
    ): NewReviewInsight {
        val known = appTopics.listEnabled(review.orgId, review.appId).map { it.key }.toSet()
        val haystack = normalize(listOfNotNull(review.title, review.body).joinToString(" "))
        val cleaned =
            topics
                .filter { Topic.ofKey(it.key) != null || it.key in known }
                .map { mention ->
                    // Citát, který v recenzi není, se nevymýšlí — radší štítek bez citátu.
                    val quote = mention.quote?.takeIf { normalize(it).isNotEmpty() && normalize(it) in haystack }
                    mention.copy(quote = quote)
                }.distinctBy { it.key }
        val topics =
            cleaned
                .takeIf { it.isNotEmpty() }
                ?.let { list -> if (list.size > 1) list.filterNot { it.key == Topic.OTHER.key } else list }
                ?: listOf(TopicMention(Topic.OTHER.key, TopicSentiment.NEUTRAL, null))

        return NewReviewInsight(
            reviewId = review.id,
            appId = review.appId,
            contentHash = review.contentHash,
            taxonomyVersion = Topic.TAXONOMY_VERSION,
            promptVersion = PROMPT_VERSION,
            model = model,
            sentiment = sentiment,
            type = type,
            urgency = urgency,
            language = language,
            translation = translation,
            topics = topics,
        )
    }

    /**
     * Výklad recenze bez textu. Nejde o odhad: pětihvězdičkové „nic" je pochvala a jednička
     * bez textu je nespokojenost — a na Androidu je takových recenzí zhruba polovina, takže
     * bez nich by podíly témat lhaly.
     */
    private fun fromRules(review: Review): NewReviewInsight =
        NewReviewInsight(
            reviewId = review.id,
            appId = review.appId,
            contentHash = review.contentHash,
            taxonomyVersion = Topic.TAXONOMY_VERSION,
            promptVersion = PROMPT_VERSION,
            model = RULES_MODEL,
            sentiment =
                when {
                    review.starRating >= POSITIVE_STARS -> OverallSentiment.POSITIVE
                    review.starRating <= NEGATIVE_STARS -> OverallSentiment.NEGATIVE
                    else -> OverallSentiment.NEUTRAL
                },
            type = if (review.starRating >= POSITIVE_STARS) ReviewType.PRAISE else ReviewType.OTHER,
            urgency = Urgency.LOW,
            language = null,
            translation = null,
            topics =
                if (review.starRating >= POSITIVE_STARS) {
                    listOf(TopicMention(Topic.PRAISE.key, TopicSentiment.POSITIVE, null))
                } else {
                    listOf(TopicMention(Topic.OTHER.key, TopicSentiment.NEUTRAL, null))
                },
        )

    private fun ReviewInsight.isFresh(review: Review): Boolean =
        contentHash == review.contentHash && taxonomyVersion == Topic.TAXONOMY_VERSION

    private fun Review.hasNoText(): Boolean = title.isNullOrBlank() && body.isNullOrBlank()

    private fun Review.textLength(): Int = (title?.length ?: 0) + (body?.length ?: 0)

    companion object {
        /** Verze promptu se drží u výkladu, aby šlo poznat, čím se čísla změnila. */
        const val PROMPT_VERSION = "2026-09-v1"

        /** Model, který výklad vyrobil pravidly z hvězd, ne voláním do AI. */
        const val RULES_MODEL = "rules"

        /** Deset až dvacet položek na volání je u uzavřené taxonomie sladké místo mezi cenou a kvalitou. */
        const val BATCH_SIZE = 15

        /** Kolik recenzí zvládne jeden běh jobu, než se přeplánuje. */
        const val DEFAULT_LIMIT = 150

        private const val LONG_REVIEW_LENGTH = 1_500
        private const val POSITIVE_STARS = 5
        private const val NEGATIVE_STARS = 2

        /**
         * Normalizace pro ověření citátu. Model mění bílé znaky a občas i diakritiku, ale
         * *obsah* citátu musí být z recenze — po tomhle srovnání se rozdíl mezi „opravdu to
         * tam je" a „vypadá to podobně" pozná spolehlivě.
         */
        private fun normalize(text: String): String =
            Normalizer
                .normalize(text, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .replace(WHITESPACE, " ")
                .trim()
                .lowercase()

        private val DIACRITICS = Regex("\\p{Mn}+")
        private val WHITESPACE = Regex("\\s+")
    }
}
