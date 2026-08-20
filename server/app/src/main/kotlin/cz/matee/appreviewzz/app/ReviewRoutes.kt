package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ReviewResponse(
    val id: String,
    val platform: Platform,
    val storeReviewId: String,
    val authorName: String?,
    val starRating: Int,
    val title: String?,
    val body: String?,
    val appVersion: String?,
    val territory: String?,
    val submittedAt: String,
    val state: ReviewState,
    val developerResponseBody: String?,
    val developerResponseAt: String?,
)

@Serializable
data class ReviewMessageResponse(
    val channelId: String,
    val status: MessageStatus,
    val error: String?,
    val sentAt: String?,
)

@Serializable
data class ReplyResponse(
    val id: String,
    val body: String,
    val source: ReplySource,
    val status: ReplyStatus,
    val error: String?,
    val authorDisplayName: String?,
    val publishedAt: String?,
    val createdAt: String,
)

@Serializable
data class ReviewDetailResponse(
    val review: ReviewResponse,
    val messages: List<ReviewMessageResponse>,
    val replies: List<ReplyResponse>,
)

@Serializable
data class ReplyRequest(
    val body: String,
)

@Serializable
data class ChangeReviewStateRequest(
    val state: ReviewState,
)

@Serializable
data class QueuedResponse(
    val queued: Boolean,
    val message: String,
)

@Serializable
data class ChannelHealthResponse(
    val id: String,
    val targetRef: String,
    val enabled: Boolean,
    val hasCredential: Boolean,
)

@Serializable
data class CredentialHealthResponse(
    val id: String,
    val label: String,
    val validationStatus: ValidationStatus,
    val validationError: String?,
)

@Serializable
data class AppHealthResponse(
    val appId: String,
    val name: String,
    val enabled: Boolean,
    val lastReviewAt: String?,
    val pendingReviews: Int,
    val channels: List<ChannelHealthResponse>,
    val credentials: List<CredentialHealthResponse>,
)

@Serializable
data class FailedJobResponse(
    val task: String,
    val attempts: Int,
    val error: String?,
    val firstFailedAt: String,
    val lastFailedAt: String,
)

@Serializable
data class HealthResponse(
    val apps: List<AppHealthResponse>,
    val failedJobs: List<FailedJobResponse>,
)

@Serializable
data class AuditEntryResponse(
    val action: String,
    val actor: String?,
    val targetType: String?,
    val targetId: String?,
    val metadata: Map<String, String>,
    val at: String?,
)

/**
 * Recenze, odpovídání z console, delivery health a audit log (F3.5).
 *
 * Odpověď z console se **zařadí do stejné fronty** jako odpověď ze Slacku, ne publikuje
 * rovnou v requestu. Dvě dobré vlastnosti: publikace do storu může trvat vteřiny (a klient
 * na to nečeká) a nasazení nové verze uprostřed odpovídání ji neztratí — leží v databázi.
 */
fun Route.reviewRoutes(console: ConsoleWiring) {
    val inbox = console.reviews

    route("/orgs/{org}/apps/{app}/reviews") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val states = call.stateFilter()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            call.respond(
                io { inbox.list(context.organization.id, call.appIdParam(), states, limit).map { it.toResponse() } },
            )
        }
    }

    route("/orgs/{org}/reviews/{review}") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val detail = io { inbox.detail(context.organization.id, call.reviewIdParam()) }
            call.respond(
                ReviewDetailResponse(
                    review = detail.review.toResponse(),
                    messages =
                        detail.messages.map {
                            ReviewMessageResponse(it.channelId.toString(), it.status, it.error, it.sentAt?.toString())
                        },
                    replies = detail.replies.map { it.toResponse() },
                ),
            )
        }

        patch {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ChangeReviewStateRequest>()
            val review =
                io { inbox.setState(context.organization, context.actor, call.reviewIdParam(), request.state) }
            call.respond(review.toResponse())
        }

        post("/reply") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ReplyRequest>()
            val body = request.body.trim()
            if (body.isEmpty()) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Odpověď nesmí být prázdná")

            val enqueue =
                console.enqueueReply
                    ?: throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Publikace odpovědí není v tomhle procesu zapnutá",
                    )
            val reviewId = call.reviewIdParam()
            val user = call.authenticatedUser.account.user
            // Ověření vlastnictví recenze proběhne dřív, než se cokoli zařadí do fronty.
            io { inbox.detail(context.organization.id, reviewId) }

            val queued =
                io {
                    enqueue(
                        ConsoleReply(
                            orgId = context.organization.id.toString(),
                            reviewId = reviewId.toString(),
                            body = body,
                            authorUserId = user.id.toString(),
                            authorDisplayName = user.displayName ?: user.email,
                        ),
                    )
                }
            call.respond(
                HttpStatusCode.Accepted,
                QueuedResponse(
                    queued = queued,
                    // Duplicita není chyba: dvojklik na „Odeslat" má skončit stejně jako jeden.
                    message = if (queued) "Odpověď je ve frontě k publikaci" else "Tatáž odpověď už ve frontě je",
                ),
            )
        }
    }

    get("/orgs/{org}/health") {
        val context = call.orgContext(console.organizations, console.memberships)
        val health = io { inbox.health(context.organization.id) }
        call.respond(
            HealthResponse(
                apps =
                    health.apps.map { app ->
                        AppHealthResponse(
                            appId = app.app.id.toString(),
                            name = app.app.name,
                            enabled = app.app.enabled,
                            lastReviewAt = app.lastReviewAt?.toString(),
                            pendingReviews = app.pendingReviews,
                            channels = app.channels.map { it.toHealth() },
                            credentials = app.credentials.map { it.toHealth() },
                        )
                    },
                failedJobs =
                    health.failedJobs.map {
                        FailedJobResponse(
                            task = it.taskName,
                            attempts = it.attempts,
                            error = it.errorMessage ?: it.errorClass,
                            firstFailedAt = it.firstFailedAt.toString(),
                            lastFailedAt = it.lastFailedAt.toString(),
                        )
                    },
            ),
        )
    }

    get("/orgs/{org}/audit") {
        val context = call.orgContext(console.organizations, console.memberships)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val entries = io { console.audit.list(context.organization.id, limit.coerceIn(1, MAX_AUDIT)) }
        call.respond(entries.map { it.toResponse() })
    }
}

/** Odpověď napsaná v consoli, na cestě do fronty. */
class ConsoleReply(
    val orgId: String,
    val reviewId: String,
    val body: String,
    val authorUserId: String,
    val authorDisplayName: String,
)

private const val DEFAULT_LIMIT = 50
private const val MAX_AUDIT = 200

private fun ApplicationCall.reviewIdParam(): ReviewId =
    runCatching { ReviewId(Uuid.parse(parameters["review"].orEmpty())) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková recenze tu není") }

private fun ApplicationCall.stateFilter(): Set<ReviewState> =
    request.queryParameters
        .getAll("state")
        .orEmpty()
        .flatMap { it.split(',') }
        .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() } }
        .map { raw ->
            ReviewState.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámý stav recenze '$raw'")
        }.toSet()

private fun Review.toResponse() =
    ReviewResponse(
        id = id.toString(),
        platform = platform,
        storeReviewId = storeReviewId,
        authorName = authorName,
        starRating = starRating,
        title = title,
        body = body,
        appVersion = appVersion,
        territory = territory,
        submittedAt = submittedAt.toString(),
        state = state,
        developerResponseBody = developerResponseBody,
        developerResponseAt = developerResponseAt?.toString(),
    )

private fun Reply.toResponse() =
    ReplyResponse(
        id = id.toString(),
        body = body,
        source = source,
        status = status,
        error = error,
        authorDisplayName = authorDisplayName,
        publishedAt = publishedAt?.toString(),
        createdAt = createdAt.toString(),
    )

private fun Channel.toHealth() =
    ChannelHealthResponse(
        id = id.toString(),
        targetRef = targetRef,
        enabled = enabled,
        hasCredential = credentialId != null,
    )

private fun CredentialMeta.toHealth() =
    CredentialHealthResponse(
        id = id.toString(),
        label = label,
        validationStatus = validationStatus,
        validationError = validationError,
    )

private fun AuditEntry.toResponse() =
    AuditEntryResponse(
        action = action,
        actor = actorLabel,
        targetType = targetType,
        targetId = targetId,
        metadata = metadata,
        at = createdAt?.toString(),
    )
