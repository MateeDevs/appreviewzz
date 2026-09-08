package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AnalysisNarrativeProvider
import cz.matee.appreviewzz.core.port.Narrative
import cz.matee.appreviewzz.core.port.NarrativeRequest
import cz.matee.appreviewzz.core.port.NarrativeResult
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}
private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Slovní shrnutí rozboru přes Gemini (B6).
 *
 * Jede na modelu návrhů odpovědí (Flash), ne na levnějším modelu tagování: tohle je jediná
 * věta ve zprávě, kterou píše model, chodí jednou týdně na aplikaci a stojí zlomek toho co
 * tagování — šetřit zrovna tady by bylo za cenu jediné věci, kterou klient opravdu čte.
 *
 * Ověřování patří do use casu, kde jsou agregáty i seznam kandidátů. Provider jen mluví
 * s API a nikdy nevyhodí výjimku.
 */
class GeminiNarrativeProvider(
    private val httpClient: HttpClient,
    private val apiKey: SecretPayload,
    val model: String = GeminiSuggestReplyProvider.DEFAULT_MODEL,
    private val baseUrl: String = GeminiSuggestReplyProvider.GEMINI_BASE_URL,
) : AnalysisNarrativeProvider {
    override suspend fun narrate(request: NarrativeRequest): NarrativeResult {
        val body =
            GenerateContentRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(NarrativePrompt.system(request)))),
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(NarrativePrompt.user(request))))),
                generationConfig =
                    GenerationConfig(
                        temperature = TEMPERATURE,
                        maxOutputTokens = MAX_OUTPUT_TOKENS,
                        thinkingConfig = ThinkingConfig(thinkingBudget = 0),
                        responseMimeType = ContentType.Application.Json.toString(),
                        responseJsonSchema = SCHEMA,
                    ),
            )

        val response: HttpResponse =
            try {
                httpClient.post("$baseUrl/models/$model:generateContent") {
                    header(API_KEY_HEADER, apiKey.value)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (error: Exception) {
                // Rozbor odejde i tak, jen bez úvodního odstavce — proto se ani neopakuje.
                logger.warn(error) { "Gemini nedostupné, rozbor půjde bez shrnutí" }
                return NarrativeResult.Failed("Gemini je nedostupné: ${error.message}")
            }

        if (!response.status.isSuccess()) {
            val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
            val parsed = runCatching { payloadJson.decodeFromString<GeminiErrorResponse>(detail).error }.getOrNull()
            return NarrativeResult.Failed("Gemini vrátilo ${response.status.value}: ${parsed?.message ?: detail}")
        }

        val parsed = response.body<GenerateContentResponse>()
        parsed.promptFeedback?.blockReason?.let { return NarrativeResult.Failed("Gemini odmítlo zadání ($it)") }
        val text =
            parsed.candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(separator = "")
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) return NarrativeResult.Failed("Gemini nevrátilo text")

        val payload =
            try {
                payloadJson.decodeFromString<NarrativePayload>(text)
            } catch (error: Exception) {
                return NarrativeResult.Failed("Gemini vrátilo neplatný JSON: ${error.message}")
            }
        val summary = payload.summary.trim()
        if (summary.isEmpty()) return NarrativeResult.Failed("Gemini vrátilo prázdné shrnutí")
        return NarrativeResult.Written(Narrative(summary, payload.citedReviewIds))
    }

    companion object {
        private const val API_KEY_HEADER = "x-goog-api-key"

        /** Trochu volnější než u tagování: tohle je věta pro člověka, ne klasifikace. */
        private const val TEMPERATURE = 0.4
        private const val MAX_OUTPUT_TOKENS = 512
        private const val ERROR_DETAIL_LIMIT = 500

        /** Mělké schema: dva řetězcové výstupy, nic zanořeného. */
        private val SCHEMA =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to
                        JsonObject(
                            mapOf(
                                "summary" to
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "maxLength" to JsonPrimitive(NarrativePrompt.MAX_SUMMARY_CHARS),
                                        ),
                                    ),
                                "citedReviewIds" to
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("array"),
                                            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                        ),
                                    ),
                            ),
                        ),
                    "required" to
                        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("summary"))),
                ),
            )
    }
}
