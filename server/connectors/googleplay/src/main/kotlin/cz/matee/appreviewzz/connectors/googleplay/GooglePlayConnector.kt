package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.storeReplyMaxLength
import cz.matee.appreviewzz.core.port.PublishedReply
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewRefreshSource
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
 * Google Play konektor (plán §5.4).
 *
 * Známá omezení API, se kterými se počítá: `reviews.list` vrací **jen recenze s textem**
 * a jen zhruba **týden zpět**. Ingest každých 30 minut je hluboko uvnitř okna; agregáty
 * všech hodnocení (i těch bez textu) řeší ratings pipeline, ne tenhle endpoint.
 */
class GooglePlayConnector(
    private val httpClient: HttpClient,
    private val oauth: GoogleOAuth = GoogleOAuth(httpClient),
    private val clock: Clock = Clock.System,
    private val baseUrl: String = ANDROID_PUBLISHER_BASE_URL,
) : ReviewSource,
    ReviewRefreshSource,
    ReplyTarget {
    override val platform: Platform = Platform.ANDROID

    /** Google odpověď nad limit odmítne; ořezáváme dřív, než se zeptáme. */
    override val replyMaxLength: Int = platform.storeReplyMaxLength

    override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> {
        val account = GoogleServiceAccount.parse(context.credential)
        val token = oauth.accessToken(account)
        val collected = mutableListOf<ObservedReview>()
        var pageToken: String? = null
        var page = 0

        do {
            val response =
                request(account) {
                    httpClient.get("$baseUrl/applications/${context.appIdentifier}/reviews") {
                        bearerAuth(token)
                        parameter("maxResults", PAGE_SIZE)
                        pageToken?.let { parameter("token", it) }
                    }
                }
            val body = response.body<ReviewsListResponse>()
            collected += body.reviews.mapNotNull { it.toObservedReview() }
            pageToken = body.tokenPagination?.nextPageToken
            page++
            // Dnešní n8n bere jen první stránku, takže při návalu recenze mizí. Stránkujeme,
            // ale se stropem — nekonečná smyčka kvůli rozbitému tokenu by zablokovala worker.
        } while (pageToken != null && page < MAX_PAGES)

        if (pageToken != null) {
            logger.warn {
                "Google Play vrátil víc než ${MAX_PAGES * PAGE_SIZE} recenzí pro ${context.appIdentifier}; " +
                    "zbytek dorazí při dalším běhu"
            }
        }
        return collected
    }

    /**
     * `reviews.get`. Na rozdíl od výpisu **týdenní okno nemá** — ověřeno proti ostrému API
     * na dva roky staré recenzi. Dokumentace to neslibuje (omezení uvádí u obojího), takže
     * kdyby to Google jednou utáhl, projeví se to jako 404 a recenze prostě zůstane, kde je.
     */
    override suspend fun fetchReview(
        context: StoreContext,
        storeReviewId: String,
    ): ObservedReview? {
        val account = GoogleServiceAccount.parse(context.credential)
        val token = oauth.accessToken(account)
        val response =
            try {
                request(account) {
                    httpClient.get("$baseUrl/applications/${context.appIdentifier}/reviews/$storeReviewId") {
                        bearerAuth(token)
                    }
                }
            } catch (error: StoreConnectorException) {
                // 404 je tady normální odpověď, ne porucha: autor recenzi smazal.
                if (error.kind == StoreErrorKind.NOT_FOUND) return null
                throw error
            }
        return response.body<ReviewDto>().toObservedReview()
    }

    override suspend fun validate(context: StoreContext): ValidationOutcome =
        try {
            val account = GoogleServiceAccount.parse(context.credential)
            val token = oauth.accessToken(account)
            request(account) {
                httpClient.get("$baseUrl/applications/${context.appIdentifier}/reviews") {
                    bearerAuth(token)
                    parameter("maxResults", 1)
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
        require(body.isNotBlank()) { "Prázdnou odpověď Google Play nepřijme" }
        val text = body.take(replyMaxLength)
        val account = GoogleServiceAccount.parse(context.credential)
        val token = oauth.accessToken(account)

        val response =
            request(account) {
                httpClient.post("$baseUrl/applications/${context.appIdentifier}/reviews/$storeReviewId:reply") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(ReplyRequest(replyText = text))
                }
            }
        val published = response.body<ReplyResponse>().result
        return PublishedReply(
            body = published?.replyText ?: text,
            publishedAt = published?.lastEdited?.toInstant() ?: clock.now(),
        )
    }

    /** Společné mapování chyb: sítě, HTTP kódů a Google `error.status` na [StoreErrorKind]. */
    private suspend fun request(
        account: GoogleServiceAccount,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        val response =
            try {
                block()
            } catch (error: java.io.IOException) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Google Play API je nedostupné", error)
            }
        if (response.status.isSuccess()) return response

        val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
        val parsed = runCatching { errorJson.decodeFromString<GoogleErrorResponse>(detail).error }.getOrNull()
        val kind =
            when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> StoreErrorKind.AUTH
                HttpStatusCode.NotFound -> StoreErrorKind.NOT_FOUND
                HttpStatusCode.TooManyRequests -> StoreErrorKind.RATE_LIMITED
                else ->
                    if (response.status.value >= HttpStatusCode.InternalServerError.value) {
                        StoreErrorKind.TRANSIENT
                    } else {
                        StoreErrorKind.INVALID_REQUEST
                    }
            }
        if (kind == StoreErrorKind.AUTH) {
            // Token z cache už neplatí — jinak by se stejná chyba opakovala do vypršení TTL.
            oauth.invalidate(account)
        }
        throw StoreConnectorException(
            kind,
            "Google Play API vrátilo ${response.status.value}: ${parsed?.message ?: detail}",
        )
    }

    private fun validationMessage(
        error: StoreConnectorException,
        packageName: String,
    ): String =
        when (error.kind) {
            StoreErrorKind.AUTH ->
                "Service account nemá přístup k $packageName. Zkontroluj, že je pozvaný v Play Console " +
                    "s oprávněním „Odpovídat na recenze\" a že je zapnuté Google Play Android Developer API."
            StoreErrorKind.NOT_FOUND ->
                "Aplikace $packageName v tomhle vývojářském účtu není. Sedí název balíčku?"
            StoreErrorKind.RATE_LIMITED, StoreErrorKind.TRANSIENT ->
                "Google Play teď neodpovídá, zkus to za chvíli znovu."
            StoreErrorKind.INVALID_REQUEST -> error.message ?: "Google Play požadavek odmítlo."
        }

    companion object {
        const val ANDROID_PUBLISHER_BASE_URL = "https://androidpublisher.googleapis.com/androidpublisher/v3"

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
        private const val ERROR_DETAIL_LIMIT = 500
    }
}

internal fun TimestampDto.toInstant(): Instant? = seconds?.toLongOrNull()?.let { Instant.fromEpochSeconds(it, nanos) }

/**
 * Normalizace do kanonické recenze. Recenzi bez uživatelského komentáře (může se stát
 * u smazaných) přeskakujeme — nemá hvězdy ani text, notifikovat není co.
 */
internal fun ReviewDto.toObservedReview(): ObservedReview? {
    val user = comments.firstNotNullOfOrNull { it.userComment } ?: return null
    val developer = comments.firstNotNullOfOrNull { it.developerComment }
    val submittedAt = user.lastModified?.toInstant() ?: return null
    if (user.starRating !in 1..5) return null

    return ObservedReview(
        platform = Platform.ANDROID,
        storeReviewId = reviewId,
        authorName = authorName,
        starRating = user.starRating,
        // Google Play recenze nemají titulek, na rozdíl od App Store.
        title = null,
        body = user.text,
        locale = user.reviewerLanguage,
        territory = null,
        appVersion = user.appVersionName ?: user.appVersionCode?.toString(),
        device = user.deviceMetadata?.productName ?: user.device,
        submittedAt = submittedAt,
        storeUpdatedAt = submittedAt,
        developerResponseBody = developer?.text,
        developerResponseAt = developer?.lastModified?.toInstant(),
    )
}
