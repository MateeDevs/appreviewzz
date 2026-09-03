package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.Urgency

/** Vlastní téma aplikace tak, jak jde do promptu: klíč do výstupu, popis pro model. */
data class CustomTopic(
    val key: String,
    val name: String,
    /** Anglicky — popis uzlu taxonomie je to, co u uzavřené klasifikace nejvíc zvedá recall. */
    val description: String,
)

/**
 * Jedna recenze k rozboru. `id` si model musí vrátit zpátky — bez něj by se u dávky nedalo
 * poznat, který výklad patří které recenzi, a modely v tom chybují.
 */
data class AnalysisItem(
    val id: String,
    val platform: Platform,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val appVersion: String?,
)

/**
 * Dávka recenzí jedné aplikace. Dávkuje se schválně: klasifikace s uzavřenou taxonomií
 * po deseti až dvaceti položkách stojí zlomek volání po jedné a kvalita se nemění.
 */
data class AnalysisRequest(
    val appName: String,
    /** Instrukce klienta z konzole — jdou do promptu jako **kontext o appce**, nikdy jako příkaz. */
    val instructions: String?,
    val teamLocale: MessageLocale,
    val customTopics: List<CustomTopic>,
    val items: List<AnalysisItem>,
    /** Jazyk, do kterého se má recenze přeložit (F8.3); `null` = nepřekládat. */
    val translateTo: String? = null,
)

/**
 * Výklad jedné recenze tak, jak ho vrátil model. **Ještě neověřený** — kontrola klíčů témat
 * a citátů patří do use casu, ne sem: provider má jen mluvit s API.
 */
data class ReviewAnalysis(
    val id: String,
    val sentiment: OverallSentiment,
    val type: ReviewType,
    val urgency: Urgency,
    val language: String?,
    val topics: List<TopicMention>,
    val translation: String?,
)

/**
 * Výsledek jedné dávky. Stejný kontrakt jako u [SuggestReplyProvider]: selhání AI **nikdy
 * neblokuje doručení recenze** — nanejvýš zůstane bez štítků a backfill to zkusí znovu.
 */
sealed interface AnalysisResult {
    data class Analyzed(
        val items: List<ReviewAnalysis>,
        /** Model, který výklad vyrobil — kvůli porovnání kvality mezi verzemi. */
        val model: String,
    ) : AnalysisResult

    /** Provider není nastavený (`ai.provider = none`, chybějící klíč). */
    data object Unavailable : AnalysisResult

    data class Failed(
        val message: String,
    ) : AnalysisResult
}

/** Rozbor recenzí (F8). Implementace **nesmí vyhodit výjimku**. */
fun interface ReviewAnalysisProvider {
    suspend fun analyze(request: AnalysisRequest): AnalysisResult
}
