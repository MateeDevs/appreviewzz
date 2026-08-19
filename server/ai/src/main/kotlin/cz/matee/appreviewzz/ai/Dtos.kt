package cz.matee.appreviewzz.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
