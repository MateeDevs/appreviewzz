package cz.matee.appreviewzz.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Podmnožina Gemini `generateContent` API, kterou používáme (v1beta). */
@Serializable
internal data class GenerateContentRequest(
    val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig,
)

@Serializable
internal data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>,
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
)

@Serializable
internal data class GenerationConfig(
    val temperature: Double,
    val maxOutputTokens: Int,
    val thinkingConfig: ThinkingConfig? = null,
    /** `application/json` u structured outputu; `null` (běžný text) u návrhů odpovědí. */
    val responseMimeType: String? = null,
    /**
     * JSON schema odpovědi. Není zadarmo jen na oko: díky němu nemá model jak vrátit téma,
     * které v taxonomii není, takže parsování nemusí hádat, co s neznámou hodnotou.
     */
    val responseJsonSchema: JsonObject? = null,
)

/**
 * Modely řady 2.5 „přemýšlejí" i nad triviálním zadáním — a účtují si to. Návrh odpovědi
 * na recenzi žádnou úvahu navíc nepotřebuje, takže rozpočet stavíme na nulu.
 */
@Serializable
internal data class ThinkingConfig(
    val thinkingBudget: Int,
)

@Serializable
internal data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
)

@Serializable
internal data class Candidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

@Serializable
internal data class PromptFeedback(
    val blockReason: String? = null,
)

/** Odpověď rozboru: model vrací jeden objekt na recenzi, každý se svým `id`. */
@Serializable
internal data class AnalysisPayload(
    val items: List<AnalysisItemPayload> = emptyList(),
)

@Serializable
internal data class AnalysisItemPayload(
    val id: String,
    val sentiment: String,
    val type: String,
    val urgency: String,
    val language: String? = null,
    val translation: String? = null,
    val topics: List<AnalysisTopicPayload> = emptyList(),
)

@Serializable
internal data class AnalysisTopicPayload(
    val key: String,
    val sentiment: String,
    val quote: String? = null,
)

@Serializable
internal data class GeminiErrorResponse(
    val error: GeminiError? = null,
)

@Serializable
internal data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    @SerialName("status") val status: String? = null,
)

@Serializable
internal data class NarrativePayload(
    val summary: String = "",
    val citedReviewIds: List<String> = emptyList(),
)
