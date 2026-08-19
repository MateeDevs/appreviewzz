package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal fun request(
    platform: Platform = Platform.ANDROID,
    stars: Int = 2,
    title: String? = null,
    body: String? = "Po updatu se nedostanu dál.",
    reviewLocale: String? = "cs",
    instructions: String? = null,
    maxLength: Int = 350,
): ReplySuggestionRequest =
    ReplySuggestionRequest(
        platform = platform,
        starRating = stars,
        title = title,
        body = body,
        appName = "IsleGrow",
        reviewLocale = reviewLocale,
        teamLocale = MessageLocale.CS,
        instructions = instructions,
        maxLength = maxLength,
    )

/** Mock engine, který si pamatuje poslední požadavek — testy tak vidí, co jsme Gemini poslali. */
internal class RecordingEngine(
    private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient =
        aiHttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )
}

internal fun geminiResponse(text: String): String =
    """
    {
      "candidates": [
        { "content": { "role": "model", "parts": [ { "text": ${quoted(text)} } ] }, "finishReason": "STOP" }
      ]
    }
    """.trimIndent()

private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
