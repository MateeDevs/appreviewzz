package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.FailedJob
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.port.ReplyRepository
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.auditEntry

/** Recenze se vším, co se k ní váže — doručené zprávy a odpovědi. */
data class ReviewDetail(
    val review: Review,
    val messages: List<ReviewMessage>,
    val replies: List<Reply>,
)

/**
 * Stav jedné aplikace: co je potřeba udělat, aby recenze chodily. Console z toho staví
 * „delivery health" — obrazovku, která má odpovědět na otázku *proč nic nechodí* dřív,
 * než ji klient stihne položit.
 */
data class AppHealth(
    val app: App,
    val channels: List<Channel>,
    val credentials: List<CredentialMeta>,
    val lastReviewAt: kotlin.time.Instant?,
    val pendingReviews: Int,
)

data class OrgHealth(
    val apps: List<AppHealth>,
    /** Úlohy, které se nepovedly ani po opakování — dokud je někdo nevyřeší, něco chybí. */
    val failedJobs: List<FailedJob>,
)

/**
 * Recenze v consoli (F3.5).
 *
 * Číst je smí každý člen: odpovídání na recenze je práce podpory, ne správce. Měnit
 * nastavení organizace přitom nesmí — proto je čtení a odpovídání záměrně na roli MEMBER.
 */
class ReviewInbox(
    private val reviews: ReviewRepository,
    private val messages: ReviewMessageRepository,
    private val replies: ReplyRepository,
    private val apps: AppRepository,
    private val channels: ChannelRepository,
    private val credentials: CredentialRepository,
    private val failedJobs: FailedJobRepository,
    private val audit: AuditLogRepository,
) {
    fun list(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState>,
        limit: Int,
    ): List<Review> {
        apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
        return reviews.listByApp(orgId, appId, states.ifEmpty { ReviewState.entries.toSet() }, limit.coerceIn(1, MAX_LIMIT))
    }

    fun detail(
        orgId: OrganizationId,
        id: ReviewId,
    ): ReviewDetail {
        val review = reviews.findById(orgId, id) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková recenze tu není")
        return ReviewDetail(
            review = review,
            messages = messages.listByReview(orgId, id),
            replies = replies.listByReview(orgId, id),
        )
    }

    /**
     * Odložení recenze (a vrácení zpět). Do stavů, které si drží pipeline — NOTIFIED,
     * REPLIED, SUPPRESSED — se z console sahat nedá: byla by to lež o tom, co se stalo.
     */
    fun setState(
        organization: Organization,
        actor: OrgActor,
        id: ReviewId,
        state: ReviewState,
    ): Review {
        requireRole(actor, OrgRole.MEMBER)
        if (state !in MANUAL_STATES) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Ručně se dá recenze jen odložit (${MANUAL_STATES.joinToString { it.name }})",
            )
        }
        val review = detail(organization.id, id).review
        reviews.updateState(organization.id, id, state)
        audit.append(
            auditEntry(
                orgId = organization.id,
                action = "review.state_changed",
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "review",
                targetId = id.toString(),
                metadata = mapOf("from" to review.state.name, "to" to state.name),
            ),
        )
        return review.copy(state = state)
    }

    /**
     * Podklad pro „proč nic nechodí". Schválně bez chytré diagnostiky: console ukáže
     * fakta (klíč neověřený, kanál vypnutý, úloha v DLQ) a člověk si spojí, co s tím.
     */
    fun health(orgId: OrganizationId): OrgHealth {
        val credentialsOfOrg = credentials.listByOrg(orgId)
        return OrgHealth(
            apps =
                apps.listByOrg(orgId).map { app ->
                    val recent = reviews.listByApp(orgId, app.id, ReviewState.entries.toSet(), HEALTH_SAMPLE)
                    AppHealth(
                        app = app,
                        channels = channels.listByApp(orgId, app.id),
                        credentials = credentialsOfOrg,
                        lastReviewAt = recent.firstOrNull()?.submittedAt,
                        pendingReviews = recent.count { it.state == ReviewState.NEW || it.state == ReviewState.UPDATED },
                    )
                },
            failedJobs = failedJobs.listOpenByOrg(orgId, DLQ_LIMIT),
        )
    }

    private companion object {
        const val MAX_LIMIT = 200

        /** Vzorek pro health — na „kdy naposledy něco přišlo" nemá smysl číst celou tabulku. */
        const val HEALTH_SAMPLE = 50
        const val DLQ_LIMIT = 50
        val MANUAL_STATES = setOf(ReviewState.IGNORED, ReviewState.NEW)
    }
}
