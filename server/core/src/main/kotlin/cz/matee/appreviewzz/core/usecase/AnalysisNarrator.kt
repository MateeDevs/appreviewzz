package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.port.AnalysisNarrativeProvider
import cz.matee.appreviewzz.core.port.NarrativeQuote
import cz.matee.appreviewzz.core.port.NarrativeRequest
import cz.matee.appreviewzz.core.port.NarrativeResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.number
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Slovní shrnutí rozboru (B6) i s ověřením.
 *
 * Model dostane hotová čísla a napíše nad nimi odstavec. Než se odstavec dostane do zprávy,
 * projde dvěma kontrolami:
 *
 *  1. **Každé číslo v textu musí pocházet z agregátů.** Modely v aritmetice chybují způsobem,
 *     který nejde poznat — „nespokojených bylo 31 %" vypadá stejně důvěryhodně, ať už je to
 *     pravda nebo ne. Číslo, které v datech není, znamená zahodit celé shrnutí, ne ho opravit.
 *  2. **Každý citovaný `reviewId` musí být z kandidátů.** Ve zdrojích k rozborům se uvádí,
 *     že 7,7 % citátů z LLM v datech vůbec nebylo; citát, který nikdo nenapsal, je horší
 *     než žádný.
 *
 * Když kontrola neprojde, zpráva odejde v čistě šablonové podobě. Rozbor bez odstavce je
 * pořád rozbor; rozbor s vymyšleným číslem je ztráta důvěry ve všechna ostatní.
 */
class AnalysisNarrator(
    private val provider: AnalysisNarrativeProvider,
) {
    suspend fun write(
        appName: String,
        locale: MessageLocale,
        aggregates: AnalysisAggregates,
        quotes: List<NarrativeQuote>,
    ): String? {
        val request =
            NarrativeRequest(
                appName = appName,
                locale = locale,
                aggregatesJson = json(aggregates),
                quotes = quotes,
            )
        val result =
            when (val outcome = provider.narrate(request)) {
                is NarrativeResult.Written -> outcome.narrative
                NarrativeResult.Unavailable -> return null
                is NarrativeResult.Failed -> {
                    logger.info { "Shrnutí rozboru se nepovedlo, zpráva půjde šablonová: ${outcome.message}" }
                    return null
                }
            }

        val allowedIds = quotes.map { it.reviewId }.toSet()
        val invented = result.citedReviewIds.filterNot { it in allowedIds }
        if (invented.isNotEmpty()) {
            logger.warn { "Shrnutí cituje recenze mimo zadání ($invented) — zahazuji" }
            return null
        }

        val allowed = allowedNumbers(aggregates)
        val used = NUMBER.findAll(result.summary).map { it.value.replace(',', '.') }.toList()
        val unexplained = used.filterNot { it in allowed }
        if (unexplained.isNotEmpty()) {
            logger.warn { "Shrnutí obsahuje čísla mimo agregáty ($unexplained) — zahazuji" }
            return null
        }

        return result.summary
            .take(MAX_CHARS)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Čísla, která smí ve shrnutí padnout. Podíly se povolují v obou zaokrouhleních (dolů
     * i matematicky) — model, který napíše „61 %" místo „60,7 %", nelže, jen zaokrouhluje,
     * a zahazovat kvůli tomu odstavec by znamenalo, že neprojde skoro nikdy.
     */
    private fun allowedNumbers(aggregates: AnalysisAggregates): Set<String> =
        buildSet {
            fun allow(value: Int) {
                add(value.toString())
            }

            fun share(value: Double) {
                allow((value * PERCENT).roundToInt())
                allow((value * PERCENT).toInt())
            }

            allow(aggregates.reviews)
            aggregates.byPlatform.values.forEach { allow(it) }
            share(aggregates.sentiment.positive)
            share(aggregates.sentiment.neutral)
            share(aggregates.sentiment.negative)
            aggregates.previousSentiment?.let {
                share(it.positive)
                share(it.neutral)
                share(it.negative)
            }
            aggregates.previousNegativeDelta?.let { share(kotlin.math.abs(it)) }
            aggregates.avgStars?.let {
                add(String.format(java.util.Locale.ROOT, "%.1f", it))
                add(String.format(java.util.Locale.ROOT, "%.2f", it))
                allow(it.roundToInt())
            }
            aggregates.topics.forEach { topic ->
                allow(topic.count)
                allow(topic.previousCount)
                share(topic.share)
                share(topic.negativeShare)
                topic.avgStars?.let { add(String.format(java.util.Locale.ROOT, "%.1f", it)) }
            }
            aggregates.improved.forEach {
                allow(it.before)
                allow(it.after)
            }
            allow(aggregates.replies.total)
            allow(aggregates.replies.replied)
            allow(aggregates.replies.uplifted)
            allow(aggregates.replies.dropped)
            share(aggregates.replies.share)
            aggregates.replies.medianHours?.let {
                allow(it.roundToInt())
                add(String.format(java.util.Locale.ROOT, "%.1f", it))
            }
            aggregates.versions.forEach { allow(it.count) }
            // Roky a dny období: datum ve větě není tvrzení o datech.
            allow(aggregates.periodStart.year)
            listOf(aggregates.periodStart, aggregates.periodEnd).forEach {
                allow(it.day)
                allow(it.month.number)
            }
        }

    /** Agregáty jako plochý JSON. Jen to, co smí do věty — verze promptu drží [json] a schema. */
    private fun json(aggregates: AnalysisAggregates): String =
        buildString {
            append("{")
            append("\"period\":\"${aggregates.periodStart}..${aggregates.periodEnd}\",")
            append("\"reviews\":${aggregates.reviews},")
            append("\"avgStars\":${aggregates.avgStars?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "null"},")
            append("\"positivePercent\":${(aggregates.sentiment.positive * PERCENT).roundToInt()},")
            append("\"negativePercent\":${(aggregates.sentiment.negative * PERCENT).roundToInt()},")
            aggregates.previousSentiment?.let {
                append("\"previousNegativePercent\":${(it.negative * PERCENT).roundToInt()},")
            }
            append("\"topics\":[")
            append(
                aggregates.topics.joinToString(",") { topic ->
                    "{\"name\":\"${escape(topic.name)}\",\"count\":${topic.count}," +
                        "\"previousCount\":${topic.previousCount},\"status\":\"${topic.status}\"," +
                        "\"sharePercent\":${(topic.share * PERCENT).roundToInt()}}"
                },
            )
            append("],\"improved\":[")
            append(
                aggregates.improved.joinToString(",") {
                    "{\"name\":\"${escape(it.name)}\",\"before\":${it.before},\"after\":${it.after}}"
                },
            )
            append("],\"replies\":{\"total\":${aggregates.replies.total},\"replied\":${aggregates.replies.replied},")
            append("\"uplifted\":${aggregates.replies.uplifted}}")
            append("}")
        }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val PERCENT = 100
        const val MAX_CHARS = 400

        /** Čísla v textu, včetně desetinné čárky i tečky — čeština píše „4,2", angličtina „4.2". */
        val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    }
}
