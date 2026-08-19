package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
internal val SUBMITTED: Instant = Instant.parse("2026-08-19T12:30:00Z")

internal fun review(
    platform: Platform = Platform.ANDROID,
    author: String? = "Jana N.",
    stars: Int = 2,
    title: String? = null,
    body: String? = "Po updatu se nedostanu dál.",
    developerResponse: String? = null,
): Review =
    Review(
        id = ReviewId(Uuid.parse("11111111-1111-1111-1111-111111111111")),
        orgId = OrganizationId(Uuid.parse("22222222-2222-2222-2222-222222222222")),
        appId = AppId(Uuid.parse("33333333-3333-3333-3333-333333333333")),
        platform = platform,
        storeReviewId = "gp:AOqpTOFake",
        authorName = author,
        starRating = stars,
        title = title,
        body = body,
        locale = "cs",
        territory = "CZE",
        appVersion = "3.2.1",
        device = null,
        submittedAt = SUBMITTED,
        storeUpdatedAt = null,
        contentHash = "hash",
        developerResponseBody = developerResponse,
        developerResponseAt = null,
        state = ReviewState.NEW,
        firstSeenAt = SUBMITTED,
        lastSeenAt = SUBMITTED,
    )

internal fun notification(
    review: Review = review(),
    suggestion: String? = "Mrzí nás to, chybu už opravujeme.",
    locale: MessageLocale = MessageLocale.CS,
    isUpdate: Boolean = false,
): ReviewNotification =
    ReviewNotification(
        review = review,
        appName = "IsleGrow",
        timezone = "Europe/Prague",
        locale = locale,
        suggestedReply = suggestion,
        isUpdate = isUpdate,
    )

internal class RecordingEngine(
    private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient =
        slackHttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )
}

internal fun JsonArray.ofType(type: String): List<JsonObject> = map { it.jsonObject }.filter { it.text("type") == type }

internal fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.at(vararg path: String): JsonElement? =
    path.fold(this as JsonElement?) { element, key -> element?.jsonObject?.get(key) }

internal fun JsonArray.render(): String = Json.encodeToString(JsonArray.serializer(), this)

internal fun JsonObject.blocks(): JsonArray = getValue("blocks").jsonArray
