package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.AppTopic
import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewInsight
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.ReviewFilter
import cz.matee.appreviewzz.core.usecase.AnalysisStatus
import cz.matee.appreviewzz.core.usecase.AppTopicDraft
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.InboxItem
import cz.matee.appreviewzz.core.usecase.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
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
    /** Výklad recenze (F8); `null`, dokud ho AI nespočítá nebo když není nastavená. */
    val insight: ReviewInsightResponse? = null,
)

/** Názvy témat jsou české: konzole je česká a klíč slouží k filtrování, ne k zobrazení. */
@Serializable
data class ReviewInsightResponse(
    val sentiment: OverallSentiment,
    val type: ReviewType,
    val urgency: Urgency,
    val language: String?,
    val translation: String?,
    val topics: List<TopicMentionResponse>,
)

@Serializable
data class TopicMentionResponse(
    val key: String,
    val name: String,
    val sentiment: TopicSentiment,
    val quote: String?,
)

@Serializable
data class TopicResponse(
    val key: String,
    val name: String,
    /** Skupina pro seskupení ve výběru; u vlastních témat `null`. */
    val group: String?,
    val description: String,
    val custom: Boolean,
    val enabled: Boolean,
    /** Kolik zmínek za posledních 30 dní — podle toho klient pozná, o čem se doopravdy píše. */
    val recentCount: Int,
)

@Serializable
data class AppTopicRequest(
    val name: String,
    /** Anglicky: čte ho AI jako popis tématu, ne člověk. */
    val description: String,
    val enabled: Boolean = true,
)

/** Co ruční běh rozboru udělal. `skipped` je stav, ne chyba — proto 200, ne 4xx. */
@Serializable
data class WeeklyAnalysisRunResponse(
    val skipped: String?,
    val reviews: Int,
    val sent: Int,
    val alreadySent: Int,
    val errors: List<String>,
)

@Serializable
data class AnalysisStatusResponse(
    val analyzed: Int,
    val missing: Int,
    val taxonomyVersion: String,
    /** `true` = doplnění výkladů se právě zařadilo do fronty. */
    val queued: Boolean,
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
            val filter = call.reviewFilter()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            call.respond(
                io {
                    val names = console.topicNames(context.organization.id, call.appIdParam())
                    inbox.list(context.organization.id, call.appIdParam(), filter, limit).map { it.toResponse(names) }
                },
            )
        }
    }

    /**
     * Témata pro filtr i pro nastavení. Základní taxonomie je pevná a klient ji nemění;
     * měnit jde jen to, co si k ní přidal sám.
     */
    route("/orgs/{org}/apps/{app}/topics") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(
                io {
                    inbox.topics(context.organization.id, call.appIdParam(), console.clock.now()).map {
                        TopicResponse(
                            key = it.key,
                            name = it.name,
                            group = it.group?.labelCs,
                            description = it.descriptionEn,
                            custom = it.custom,
                            enabled = it.enabled,
                            recentCount = it.recentCount,
                        )
                    }
                },
            )
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<AppTopicRequest>()
            val topic =
                io {
                    console.appTopics.create(
                        organization = context.organization,
                        actor = context.actor,
                        appId = call.appIdParam(),
                        draft = AppTopicDraft(request.name, request.description, request.enabled),
                    )
                }
            call.respond(HttpStatusCode.Created, topic.toResponse())
        }

        patch("/{topic}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<AppTopicRequest>()
            val topic =
                io {
                    console.appTopics.update(
                        organization = context.organization,
                        actor = context.actor,
                        id = call.appTopicIdParam(),
                        draft = AppTopicDraft(request.name, request.description, request.enabled),
                    )
                }
            call.respond(topic.toResponse())
        }

        delete("/{topic}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { console.appTopics.delete(context.organization, context.actor, call.appTopicIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/orgs/{org}/apps/{app}/analysis") {
        get("/status") {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { inbox.analysisStatus(context.organization.id, call.appIdParam()).toResponse() })
        }

        /**
         * Ruční odeslání týdenního rozboru. Běží v požadavku, ne ve frontě: klient na to
         * klikl proto, aby hned viděl, co do kanálu dorazilo.
         */
        post("/weekly/run") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val weekly =
                console.weeklyAnalysis
                    ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Rozbory nejsou v tomhle procesu zapnuté")
            val report = weekly.run(context.organization.id, call.appIdParam())
            call.respond(
                WeeklyAnalysisRunResponse(
                    skipped = report.skipped?.name,
                    reviews = report.aggregates?.reviews ?: 0,
                    sent = report.deliveries.count { it.sent },
                    alreadySent = report.deliveries.count { it.alreadySent },
                    errors = report.deliveries.mapNotNull { it.error },
                ),
            )
        }

        /**
         * Doplnění výkladů za historii. Zařadí se do fronty a odpoví hned — backfill jede
         * v dávkách po desítkách vteřin a klient u toho nemá čekat s otevřeným requestem.
         */
        post("/backfill") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val appId = call.appIdParam()
            val status = io { inbox.analysisStatus(context.organization.id, appId) }
            val enqueue =
                console.enqueueAnalysis
                    ?: throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Doplňování rozborů není v tomhle procesu zapnuté",
                    )
            val queued = io { enqueue(context.organization.id.toString(), appId.toString()) }
            call.respond(HttpStatusCode.Accepted, status.copy(queued = queued).toResponse())
        }
    }

    route("/orgs/{org}/reviews/{review}") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val detail = io { inbox.detail(context.organization.id, call.reviewIdParam()) }
            call.respond(
                ReviewDetailResponse(
                    review =
                        detail.review.toResponse(
                            insight = detail.insight,
                            names = io { console.topicNames(context.organization.id, detail.review.appId) },
                        ),
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

/**
 * Filtr inboxu z query parametrů. Neznámá hodnota je chyba požadavku, ne tichý prázdný
 * výsledek — překlep v `?urgency=hight` má být vidět hned.
 */
private fun ApplicationCall.reviewFilter(): ReviewFilter =
    ReviewFilter(
        states = stateFilter(),
        topics = values("topic").toSet(),
        types = values("type").map { enumValue<ReviewType>(it, "typ recenze") }.toSet(),
        urgencies = values("urgency").map { enumValue<Urgency>(it, "naléhavost") }.toSet(),
        sentiments = values("sentiment").map { enumValue<OverallSentiment>(it, "nálada") }.toSet(),
    )

private fun ApplicationCall.values(name: String): List<String> =
    request.queryParameters
        .getAll(name)
        .orEmpty()
        .flatMap { it.split(',') }
        .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() } }

private inline fun <reified T : Enum<T>> enumValue(
    raw: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámá hodnota '$raw' pro $label")

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

private fun InboxItem.toResponse(names: Map<String, String>) = review.toResponse(insight, names)

private fun AppTopic.toResponse() =
    TopicResponse(
        key = key,
        name = name,
        group = null,
        description = description,
        custom = true,
        enabled = enabled,
        recentCount = 0,
    )

private fun AnalysisStatus.toResponse() =
    AnalysisStatusResponse(analyzed = analyzed, missing = missing, taxonomyVersion = taxonomyVersion, queued = queued)

private fun Review.toResponse(
    insight: ReviewInsight? = null,
    names: Map<String, String> = emptyMap(),
) = ReviewResponse(
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
    insight = insight?.toResponse(names),
)

private fun ReviewInsight.toResponse(names: Map<String, String>) =
    ReviewInsightResponse(
        sentiment = sentiment,
        type = type,
        urgency = urgency,
        language = language,
        translation = translation,
        topics =
            topics.map {
                TopicMentionResponse(
                    key = it.key,
                    // Smazané vlastní téma zůstává ve výkladu jako klíč — data se kvůli změně
                    // seznamu témat nepřepisují.
                    name = Topic.ofKey(it.key)?.labelCs ?: names[it.key] ?: it.key,
                    sentiment = it.sentiment,
                    quote = it.quote,
                )
            },
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
