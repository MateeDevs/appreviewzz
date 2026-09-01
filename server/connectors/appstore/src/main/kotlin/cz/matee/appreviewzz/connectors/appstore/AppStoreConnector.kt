package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.storeReplyMaxLength
import cz.matee.appreviewzz.core.port.PublishedReply
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.StoreApp
import cz.matee.appreviewzz.core.port.StoreAppCatalog
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
 * Verzi aplikace atributy recenze neobsahují, ale zjistit ji jde: recenze visí na konkrétní
 * verzi (`/v1/appStoreVersions/{id}/customerReviews`), takže se verze doplní odtud — stejně
 * jako to dělá dnešní n8n, jen s `include=response` a stránkováním navíc.
 *
 * Doplnění verze je **best-effort**: klíč s rolí Customer Support na seznam verzí nemusí mít
 * právo. Když listing selže, recenze dorazí bez verze místo toho, aby spadl celý ingest.
 */
class AppStoreConnector(
    private val httpClient: HttpClient,
    private val tokens: AscTokens = AscTokens(),
    private val clock: Clock = Clock.System,
    private val baseUrl: String = APP_STORE_CONNECT_BASE_URL,
    /** Kolik posledních verzí se prochází kvůli doplnění verze k recenzi. */
    private val versionWindow: Int = DEFAULT_VERSION_WINDOW,
) : ReviewSource,
    ReplyTarget,
    StoreAppCatalog {
    override val platform: Platform = Platform.IOS

    /** Apple přijme odpověď do 5 970 znaků; delší vrací jako chybu požadavku. */
    override val replyMaxLength: Int = platform.storeReplyMaxLength

    override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> {
        val key = AscApiKey.parse(context.credential)
        val versionByReviewId = versionsByReviewId(key, context.appIdentifier)
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

            collected +=
                body.data.mapNotNull { review ->
                    review.toObservedReview(responsesById)?.copy(appVersion = versionByReviewId[review.id])
                }
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

    /**
     * Aplikace, které klíč vidí. Týmový klíč jich vrací celý tým — proto stránkování;
     * `limit` je maximum, které ASC dovolí.
     *
     * Chyby se překládají tady, ne u volajícího: rozdíl mezi „špatné Issuer ID" a „klíč nemá
     * roli" pozná jen ten, kdo ví, co které HTTP číslo v App Store Connect znamená.
     */
    override suspend fun listApps(credential: SecretPayload): List<StoreApp> {
        val key = AscApiKey.parse(credential)
        val collected = mutableListOf<StoreApp>()
        var url: String? = "$baseUrl/v1/apps"
        var page = 0

        while (url != null && page < MAX_PAGES) {
            val requestUrl = url
            val isFirstPage = page == 0
            val response =
                try {
                    request(key) {
                        httpClient.get(requestUrl) {
                            bearerAuth(tokens.bearerToken(key))
                            if (isFirstPage) {
                                parameter("fields[apps]", "name,bundleId,primaryLocale")
                                parameter("limit", PAGE_SIZE)
                            }
                        }
                    }
                } catch (error: StoreConnectorException) {
                    throw StoreConnectorException(error.kind, catalogMessage(error), error)
                }
            val body = response.body<AppsResponse>()
            collected +=
                body.data.map { app ->
                    StoreApp(
                        identifier = app.id,
                        // Bez názvu by v seznamu zbyla holá čísla; bundle ID appku pozná stejně dobře.
                        name = app.attributes?.name ?: app.attributes?.bundleId ?: app.id,
                        bundleId = app.attributes?.bundleId,
                    )
                }
            url = body.links?.next
            page++
        }
        return collected
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

    /**
     * Mapa recenze → verze. Projde nejnovější iOS verze a u každé si vytáhne ID recenzí,
     * které pod ni spadají. Recenze na starších verzích, než sahá okno, přijdou bez verze —
     * pořád je to lepší než je kvůli tomu vůbec neposlat.
     */
    private suspend fun versionsByReviewId(
        key: AscApiKey,
        appId: String,
    ): Map<String, String> =
        try {
            val versions = listIosVersions(key, appId)
            buildMap {
                versions.forEach { version ->
                    reviewIdsOfVersion(key, version.id).forEach { reviewId -> put(reviewId, version.versionString) }
                }
            }
        } catch (error: StoreConnectorException) {
            logger.info {
                "Verze iOS recenzí pro $appId se nepodařilo zjistit (${error.kind}); " +
                    "recenze dorazí bez verze. Klíč nejspíš nemá přístup k App Store Versions."
            }
            emptyMap()
        }

    private suspend fun listIosVersions(
        key: AscApiKey,
        appId: String,
    ): List<IosVersion> {
        val response =
            request(key) {
                httpClient.get("$baseUrl/v1/apps/$appId/appStoreVersions") {
                    bearerAuth(tokens.bearerToken(key))
                    parameter("fields[appStoreVersions]", "versionString,appStoreState,platform,createdDate")
                    parameter("limit", VERSIONS_PAGE_SIZE)
                }
            }
        return response
            .body<AppStoreVersionsResponse>()
            .data
            .mapNotNull { version ->
                val attributes = version.attributes ?: return@mapNotNull null
                // macOS a tvOS verze téže appky do iOS recenzí nepatří.
                if (attributes.platform != "IOS") return@mapNotNull null
                val versionString = attributes.versionString ?: return@mapNotNull null
                IosVersion(version.id, versionString, attributes.createdDate?.toInstantOrNull())
            }.sortedByDescending { it.createdAt }
            .take(versionWindow)
    }

    private suspend fun reviewIdsOfVersion(
        key: AscApiKey,
        versionId: String,
    ): List<String> {
        val response =
            request(key) {
                httpClient.get("$baseUrl/v1/appStoreVersions/$versionId/customerReviews") {
                    bearerAuth(tokens.bearerToken(key))
                    // Zajímají nás jen ID; menší pole = menší odpověď.
                    parameter("fields[customerReviews]", "createdDate")
                    parameter("limit", PAGE_SIZE)
                    parameter("sort", "-createdDate")
                }
            }
        return response.body<CustomerReviewsResponse>().data.map { it.id }
    }

    private data class IosVersion(
        val id: String,
        val versionString: String,
        val createdAt: Instant?,
    )

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

    /**
     * Hlášky pro dialog „Vyberte aplikace". Klient v tu chvíli právě opsal dvě ID a nahrál
     * soubor — potřebuje vědět, které z toho je špatně, ne že „App Store vrátil 401".
     */
    private fun catalogMessage(error: StoreConnectorException): String =
        when (error.kind) {
            StoreErrorKind.AUTH ->
                "App Store Connect klíč nepřijal. Zkontroluj Issuer ID (je nahoře na stránce Integrations) " +
                    "a jestli klíč nebyl mezitím zrušený. Když sedí obojí, chybí klíči role na čtení recenzí."
            StoreErrorKind.NOT_FOUND -> "App Store Connect ten účet nezná — nejspíš je klíč z jiného týmu."
            StoreErrorKind.RATE_LIMITED, StoreErrorKind.TRANSIENT ->
                "App Store Connect teď neodpovídá, zkus to za chvíli znovu."
            StoreErrorKind.INVALID_REQUEST -> error.message ?: "App Store Connect požadavek odmítl."
        }

    companion object {
        const val APP_STORE_CONNECT_BASE_URL = "https://api.appstoreconnect.apple.com"

        private const val PAGE_SIZE = 200
        private const val VERSIONS_PAGE_SIZE = 200
        private const val DEFAULT_VERSION_WINDOW = 15
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
        // Doplní volající z mapy verzí — atributy recenze verzi nenesou.
        appVersion = null,
        device = null,
        submittedAt = createdAt,
        storeUpdatedAt = null,
        developerResponseBody = response?.responseBody,
        developerResponseAt = response?.lastModifiedDate?.toInstantOrNull(),
    )
}
