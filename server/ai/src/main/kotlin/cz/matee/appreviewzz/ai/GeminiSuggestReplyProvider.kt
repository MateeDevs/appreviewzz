package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}
private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Gemini jako výchozí provider návrhů (rozhodnutí §13.2 plánu) — klienti migrovaní z n8n
 * tak dostávají návrhy od stejné rodiny modelů a nepoznají rozdíl.
 *
 * Provider **nikdy nevyhodí výjimku**: selhání AI se vrací jako [ReplySuggestion.Failed]
 * a recenze do kanálu odejde i tak, jen s prázdným vstupem. Návrh je pohodlí, ne podmínka.
 */
class GeminiSuggestReplyProvider(
    private val httpClient: HttpClient,
    private val apiKey: SecretPayload,
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = GEMINI_BASE_URL,
) : SuggestReplyProvider {
    override suspend fun suggest(request: ReplySuggestionRequest): ReplySuggestion {
        val body =
            GenerateContentRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(ReplyPrompt.system(request)))),
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(ReplyPrompt.user(request))))),
                generationConfig =
                    GenerationConfig(
                        temperature = TEMPERATURE,
                        maxOutputTokens = outputTokenBudget(request.maxLength),
                        thinkingConfig = ThinkingConfig(thinkingBudget = 0),
                    ),
            )

        val response =
            try {
                httpClient.post("$baseUrl/models/$model:generateContent") {
                    header(API_KEY_HEADER, apiKey.value)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (error: Exception) {
                // Sem spadne i timeout: AI si nesmí vzít celý běh doručení jako rukojmí.
                logger.warn(error) { "Gemini nedostupné, recenze půjde do kanálu bez návrhu" }
                return ReplySuggestion.Failed("Gemini je nedostupné: ${error.message}")
            }

        if (!response.status.isSuccess()) {
            val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
            val parsed = runCatching { errorJson.decodeFromString<GeminiErrorResponse>(detail).error }.getOrNull()
            logger.warn { "Gemini vrátilo ${response.status.value}: ${parsed?.status ?: "bez detailu"}" }
            return ReplySuggestion.Failed("Gemini vrátilo ${response.status.value}: ${parsed?.message ?: detail}")
        }

        val parsed = response.body<GenerateContentResponse>()
        parsed.promptFeedback?.blockReason?.let {
            // Recenze plná nadávek se dá zablokovat na vstupu — to není chyba, ale návrh nebude.
            return ReplySuggestion.Failed("Gemini odmítlo zadání ($it)")
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
            return ReplySuggestion.Failed("Gemini nevrátilo text (${candidate?.finishReason ?: "bez kandidáta"})")
        }

        return ReplySuggestion.Suggested(clampToLimit(text, request.maxLength), model)
    }

    /**
     * Strop výstupu odvozený z délky odpovědi. Čeština s diakritikou se tokenizuje hůř než
     * angličtina, proto se počítá zhruba token na znak a přidává rezerva — návrh useknutý
     * uprostřed by [clampToLimit] sice zkrátil na větu, ale zbytečně by zahodil půlku textu.
     */
    private fun outputTokenBudget(maxLength: Int): Int = (maxLength + TOKEN_HEADROOM).coerceAtMost(MAX_OUTPUT_TOKENS)

    companion object {
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        /** Parita s dneškem: n8n jede na výchozím flash modelu, jen ho nemá nikde napsaný. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val TEMPERATURE = 0.7
        private const val TOKEN_HEADROOM = 256
        private const val MAX_OUTPUT_TOKENS = 8_192
        private const val ERROR_DETAIL_LIMIT = 500
    }
}
