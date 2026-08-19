package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.PublishedReply
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}
private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * App Store Connect konektor (plán §5.4).
 *
 * Klíčový rozdíl proti dnešnímu n8n: recenze se stahují **s `include=response`**, takže
 * víme, na které už někdo odpověděl. Bez toho iOS „už odpovězeno" nikdy nefungovalo.
 *
 * Verzi aplikace ASC u recenze nevrací a my si ji nedomýšlíme — přiřadit recenzi aktuální
 * verzi appky by bylo prostě nepravdivé. `appVersion` proto zůstává u iOS prázdné.
 */
class AppStoreConnector(
    private val httpClient: HttpClient,
    private val tokens: AscTokens = AscTokens(),
    private val clock: Clock = Clock.System,
    private val baseUrl: String = APP_STORE_CONNECT_BASE_URL,
) : ReviewSource,
    ReplyTarget {
    override val platform: Platform = Platform.IOS

    /** Apple přijme odpověď do 5 970 znaků; delší vrací jako chybu požadavku. */
    override val replyMaxLength: Int = 5_970

    override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> {
        val key = AscApiKey.parse(context.credential)
        val collected = mutableListOf<ObservedReview>()
        var url: String? = "$baseUrl/v1/apps/${context.appIdentifier}/customerReviews"
        var page = 0

        while (url != null && page < MAX_PAGES) {
            val requestUrl = url
            val isFirstPage = page == 0
            val response =
                request(key) {
                    httpClient.get(requestUrl) {
                        bearerAuth(tokens.bearerToken(key))
                        if (isFirstPage) {
                            // include=response je celý fix iOS odpovědí; sort a limit drží
                            // stránkování stabilní i při souběžném přírůstku recenzí.
                            parameter("include", "response")
                            parameter("limit", PAGE_SIZE)
                            parameter("sort", "-createdDate")
                        }
                    }
                }
            val body = response.body<CustomerReviewsResponse>()
            val responsesById =
                body.included
                    .filter { it.type == "customerReviewResponses" }
                    .associateBy { it.id }

            collected += body.data.mapNotNull { it.toObservedReview(responsesById) }
            url = body.links?.next
            page++
        }

        if (url != null) {
            logger.warn {
                "App Store vrátil víc než ${MAX_PAGES * PAGE_SIZE} recenzí pro ${context.appIdentifier}; " +
                    "zbytek dorazí při dalším běhu"
            }
        }
        return collected
    }

    override suspend fun validate(context: StoreContext): ValidationOutcome =
        try {
            val key = AscApiKey.parse(context.credential)
            request(key) {
                httpClient.get("$baseUrl/v1/apps/${context.appIdentifier}/customerReviews") {
                    bearerAuth(tokens.bearerToken(key))
                    parameter("limit", 1)
                }
            }
            ValidationOutcome(valid = true)
        } catch (error: StoreConnectorException) {
            ValidationOutcome(valid = false, message = validationMessage(error, context.appIdentifier))
        }

    override suspend fun publishReply(
        context: StoreContext,
        storeReviewId: String,
        body: String,
    ): PublishedReply {
        require(body.isNotBlank()) { "Prázdnou odpověď App Store nepřijme" }
        val text = body.take(replyMaxLength)
        val key = AscApiKey.parse(context.credential)

        val response =
            request(key) {
                httpClient.post("$baseUrl/v1/customerReviewResponses") {
                    bearerAuth(tokens.bearerToken(key))
                    contentType(ContentType.Application.Json)
                    setBody(CreateResponseRequest.of(storeReviewId, text))
                }
            }
        val created = response.body<CreateResponseResult>().data
        return PublishedReply(
            body = created?.attributes?.responseBody ?: text,
            publishedAt = created?.attributes?.lastModifiedDate?.toInstantOrNull() ?: clock.now(),
        )
    }

    private suspend fun request(
        key: AscApiKey,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        val response =
            try {
                block()
            } catch (error: java.io.IOException) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "App Store Connect API je nedostupné", error)
            }
        if (response.status.isSuccess()) return response

        val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
        val parsed = runCatching { errorJson.decodeFromString<AscErrorResponse>(detail).errors.firstOrNull() }.getOrNull()
        val kind =
            when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> StoreErrorKind.AUTH
                HttpStatusCode.NotFound -> StoreErrorKind.NOT_FOUND
                HttpStatusCode.TooManyRequests -> StoreErrorKind.RATE_LIMITED
                HttpStatusCode.Conflict -> StoreErrorKind.INVALID_REQUEST
                else ->
                    if (response.status.value >= HttpStatusCode.InternalServerError.value) {
                        StoreErrorKind.TRANSIENT
                    } else {
                        StoreErrorKind.INVALID_REQUEST
                    }
            }
        if (kind == StoreErrorKind.AUTH) {
            tokens.invalidate(key)
        }
        val message = listOfNotNull(parsed?.title, parsed?.detail).joinToString(" — ").ifBlank { detail }
        throw StoreConnectorException(kind, "App Store Connect vrátil ${response.status.value}: $message")
    }

    private fun validationMessage(
        error: StoreConnectorException,
        appId: String,
    ): String =
        when (error.kind) {
            StoreErrorKind.AUTH ->
                "Klíč nemá přístup k aplikaci $appId. Musí mít roli Customer Support (víc nechceme) " +
                    "a u týmového klíče musí sedět Issuer ID."
            StoreErrorKind.NOT_FOUND ->
                "Aplikace s ID $appId v tomhle účtu není. Zkontroluj Apple ID aplikace v App Store Connect."
            StoreErrorKind.RATE_LIMITED, StoreErrorKind.TRANSIENT ->
                "App Store Connect teď neodpovídá, zkus to za chvíli znovu."
            StoreErrorKind.INVALID_REQUEST -> error.message ?: "App Store Connect požadavek odmítl."
        }

    companion object {
        const val APP_STORE_CONNECT_BASE_URL = "https://api.appstoreconnect.apple.com"

        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 10
        private const val ERROR_DETAIL_LIMIT = 500
    }
}

internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

/**
 * Normalizace do kanonické recenze. Odpověď vývojáře se dohledá v `included` podle
 * relationships — díky tomu poznáme i odpověď publikovanou ručně z App Store Connect.
 */
internal fun CustomerReviewDto.toObservedReview(responses: Map<String, IncludedResourceDto>): ObservedReview? {
    val attributes = attributes ?: return null
    if (attributes.rating !in 1..5) return null
    val createdAt = attributes.createdDate?.toInstantOrNull() ?: return null

    val response =
        relationships
            ?.response
            ?.data
            ?.id
            ?.let { responses[it] }
            ?.attributes
    return ObservedReview(
        platform = Platform.IOS,
        storeReviewId = id,
        authorName = attributes.reviewerNickname,
        starRating = attributes.rating,
        title = attributes.title,
        body = attributes.body,
        locale = null,
        territory = attributes.territory,
        // ASC u recenze verzi aplikace nevrací a domýšlet ji z aktuální verze by lhalo.
        appVersion = null,
        device = null,
        submittedAt = createdAt,
        storeUpdatedAt = null,
        developerResponseBody = response?.responseBody,
        developerResponseAt = response?.lastModifiedDate?.toInstantOrNull(),
    )
}
