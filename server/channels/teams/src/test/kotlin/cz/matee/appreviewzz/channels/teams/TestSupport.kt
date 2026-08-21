package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
internal val SUBMITTED: Instant = Instant.parse("2026-08-19T12:30:00Z")

internal const val BOT_APP_ID = "7070c53f-17ad-473a-b48f-8a21ed6cd339"
internal const val TENANT_ID = "eeffd5e3-c44e-4862-aba6-a1bcd564c00c"
internal const val SERVICE_URL = "https://smba.trafficmanager.net/emea"
internal const val TEAMS_CHANNEL_ID = "19:abcdef@thread.tacv2"

internal val BOT =
    TeamsBotIdentity(appId = BOT_APP_ID, appPassword = SecretPayload("tajne-heslo"), tenantId = TENANT_ID)

internal val INSTALL: SecretPayload =
    TeamsInstall(tenantId = TENANT_ID, tenantName = "IsleGrow", serviceUrl = SERVICE_URL, teamId = "19:team@thread.tacv2")
        .payload()

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
        teamsHttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )
}

/** Token endpoint odpovídá vždy stejně; testy kanálu se zajímají o volání Bot Frameworku. */
internal fun MockRequestHandleScope.tokenResponse(): HttpResponseData = respondJson("""{"access_token":"bot-token","expires_in":3600}""")

internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData = respond(body, headers = jsonHeaders)

internal fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.at(vararg path: String): JsonElement? =
    path.fold(this as JsonElement?) { element, key -> element?.jsonObject?.get(key) }

internal fun JsonObject.render(): String = Json.encodeToString(JsonObject.serializer(), this)

internal fun JsonArray.render(): String = Json.encodeToString(JsonArray.serializer(), this)
