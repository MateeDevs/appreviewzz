package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisRequest
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import cz.matee.appreviewzz.core.port.ReviewAnalysisProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}
private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Rozbor recenzí přes Gemini (F8). Jiný model než návrhy odpovědí — tady je výstup
 * strukturovaný podle schématu, takže levnější model stačí a při desítkách tisíc recenzí
 * je ten rozdíl v ceně to podstatné.
 *
 * Provider **nikdy nevyhodí výjimku** a **nic neověřuje**: kontrola vrácených ID, neznámých
 * témat a citátů patří do use casu, kde je vidět původní text recenze. Tady se jen mluví
 * s API a překládá odpověď do doménových typů.
 */
class GeminiReviewAnalysisProvider(
    private val httpClient: HttpClient,
    private val apiKey: SecretPayload,
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = GeminiSuggestReplyProvider.GEMINI_BASE_URL,
) : ReviewAnalysisProvider {
    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        if (request.items.isEmpty()) return AnalysisResult.Analyzed(emptyList(), model)

        val body =
            GenerateContentRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(AnalysisPrompt.system(request)))),
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(AnalysisPrompt.user(request))))),
                generationConfig =
                    GenerationConfig(
                        temperature = TEMPERATURE,
                        maxOutputTokens = outputTokenBudget(request.items.size),
                        thinkingConfig = ThinkingConfig(thinkingBudget = 0),
                        responseMimeType = ContentType.Application.Json.toString(),
                        responseJsonSchema = AnalysisSchema.of(request.customTopics),
                    ),
            )

        // Jeden pokus navíc při 5xx a timeoutu: dávka je drahá na složení a přechodný výpadek
        // by jinak nechal patnáct recenzí bez výkladu až do dalšího běhu backfillu.
        var lastFailure: AnalysisResult.Failed? = null
        repeat(ATTEMPTS) { attempt ->
            when (val outcome = attempt(body)) {
                is Attempt.Done -> return outcome.result
                is Attempt.Retryable -> {
                    lastFailure = AnalysisResult.Failed(outcome.message)
                    if (attempt < ATTEMPTS - 1) logger.info { "Gemini rozbor zkusíme znovu: ${outcome.message}" }
                }
            }
        }
        return lastFailure ?: AnalysisResult.Failed("Gemini neodpovědělo")
    }

    private suspend fun attempt(body: GenerateContentRequest): Attempt {
        val response: HttpResponse =
            try {
                httpClient.post("$baseUrl/models/$model:generateContent") {
                    header(API_KEY_HEADER, apiKey.value)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (error: Exception) {
                // Sem spadne i timeout — a ten má smysl zopakovat.
                logger.warn(error) { "Gemini nedostupné, recenze zůstanou bez výkladu" }
                return Attempt.Retryable("Gemini je nedostupné: ${error.message}")
            }

        if (!response.status.isSuccess()) {
            val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
            val parsed = runCatching { payloadJson.decodeFromString<GeminiErrorResponse>(detail).error }.getOrNull()
            val message = "Gemini vrátilo ${response.status.value}: ${parsed?.message ?: detail}"
            logger.warn { "Gemini vrátilo ${response.status.value}: ${parsed?.status ?: "bez detailu"}" }
            // 4xx je špatné zadání (chybný klíč, moc velké schema) — opakování ho nespraví.
            return if (response.status.value >= SERVER_ERROR) Attempt.Retryable(message) else Attempt.Done(AnalysisResult.Failed(message))
        }

        val parsed = response.body<GenerateContentResponse>()
        parsed.promptFeedback?.blockReason?.let {
            return Attempt.Done(AnalysisResult.Failed("Gemini odmítlo zadání ($it)"))
        }
        val candidate = parsed.candidates.firstOrNull()
        val text =
            candidate
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(separator = "")
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) {
            return Attempt.Done(AnalysisResult.Failed("Gemini nevrátilo text (${candidate?.finishReason ?: "bez kandidáta"})"))
        }

        val payload =
            try {
                payloadJson.decodeFromString<AnalysisPayload>(text)
            } catch (error: Exception) {
                logger.warn(error) { "Gemini vrátilo odpověď, která neodpovídá schématu" }
                return Attempt.Done(AnalysisResult.Failed("Gemini vrátilo neplatný JSON: ${error.message}"))
            }
        return Attempt.Done(AnalysisResult.Analyzed(payload.items.mapNotNull { it.toAnalysis() }, model))
    }

    /**
     * Neznámá hodnota enumu je jediné, co se zahazuje už tady: bez sentimentu ani typu není
     * co uložit a schema to stejně nemá jak vrátit — tohle je pojistka proti změně API.
     */
    private fun AnalysisItemPayload.toAnalysis(): ReviewAnalysis? {
        val sentiment = OverallSentiment.entries.firstOrNull { it.name == sentiment.uppercase() } ?: return null
        val type = ReviewType.entries.firstOrNull { it.name == type.uppercase() } ?: return null
        val urgency = Urgency.entries.firstOrNull { it.name == urgency.uppercase() } ?: return null
        return ReviewAnalysis(
            id = id,
            sentiment = sentiment,
            type = type,
            urgency = urgency,
            language = language?.takeIf { it.isNotBlank() },
            topics =
                topics.mapNotNull { topic ->
                    val topicSentiment = TopicSentiment.entries.firstOrNull { it.name == topic.sentiment.uppercase() }
                    topicSentiment?.let { TopicMention(topic.key, it, topic.quote?.takeIf { quote -> quote.isNotBlank() }) }
                },
            translation = translation?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Strop výstupu podle velikosti dávky. Výklad jedné recenze je pár desítek tokenů,
     * dvě stě je pohodlná rezerva i pro čtyři témata s citáty.
     */
    private fun outputTokenBudget(items: Int): Int = (items * TOKENS_PER_ITEM + TOKEN_HEADROOM).coerceAtMost(MAX_OUTPUT_TOKENS)

    private sealed interface Attempt {
        data class Done(
            val result: AnalysisResult,
        ) : Attempt

        data class Retryable(
            val message: String,
        ) : Attempt
    }

    companion object {
        /** Levnější model než u návrhů: výstup je strukturovaný, takže na něj stačí. */
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"

        private const val API_KEY_HEADER = "x-goog-api-key"

        /** Klasifikace do uzavřené taxonomie nemá být kreativní — nízká teplota drží výsledky stabilní. */
        private const val TEMPERATURE = 0.2
        private const val TOKENS_PER_ITEM = 200
        private const val TOKEN_HEADROOM = 256
        private const val MAX_OUTPUT_TOKENS = 8_192
        private const val ERROR_DETAIL_LIMIT = 500
        private const val SERVER_ERROR = 500
        private const val ATTEMPTS = 2
    }
}
