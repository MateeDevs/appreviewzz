package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.ReplyId
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.ReviewMessageId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.sha256Hex
import cz.matee.appreviewzz.core.port.NewReply
import cz.matee.appreviewzz.core.port.ReplyRepository
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.persistence.schema.Channels
import cz.matee.appreviewzz.persistence.schema.Replies
import cz.matee.appreviewzz.persistence.schema.ReviewMessages
import cz.matee.appreviewzz.persistence.schema.ReviewRevisions
import cz.matee.appreviewzz.persistence.schema.Reviews
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class ExposedReviewRepository(
    private val database: ExposedDatabase,
) : ReviewRepository {
    override fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        seenAt: Instant,
        initialState: ReviewState,
    ): ReviewUpsertResult =
        transaction(database) {
            val hash = observed.contentHash()
            val existing =
                Reviews
                    .selectAll()
                    .where {
                        (Reviews.orgId eq orgId) and
                            (Reviews.appId eq appId) and
                            (Reviews.platform eq observed.platform) and
                            (Reviews.storeReviewId eq observed.storeReviewId)
                    }.firstOrNull()
                    ?.toReview()

            when {
                existing == null -> {
                    val review = insertReview(orgId, appId, observed, hash, seenAt, initialState)
                    insertRevision(review.id, observed, hash, seenAt)
                    ReviewUpsertResult(review, ReviewUpsertOutcome.CREATED)
                }

                existing.contentHash == hash -> {
                    Reviews.update({ Reviews.id eq existing.id }) { it[lastSeenAt] = seenAt }
                    ReviewUpsertResult(existing.copy(lastSeenAt = seenAt), ReviewUpsertOutcome.UNCHANGED)
                }

                else -> {
                    // Autor recenzi přepsal. Stav jde do UPDATED, ne zpátky do NEW: tým chce
                    // vidět, že se z trojky stala pětka, a může odpovědět znovu — ale zároveň
                    // musí být poznat, že to není první doručení.
                    val changes = detectChanges(existing, observed)
                    val nextState =
                        if (existing.state == ReviewState.SUPPRESSED) {
                            // Pod watermarkem zůstává potlačená i po editaci, jinak by
                            // připojení staré appky zaplavilo kanál při první úpravě.
                            ReviewState.SUPPRESSED
                        } else {
                            ReviewState.UPDATED
                        }
                    Reviews.update({ Reviews.id eq existing.id }) {
                        it[state] = nextState
                        it[starRating] = observed.starRating.toShort()
                        it[title] = observed.title
                        it[body] = observed.body
                        it[appVersion] = observed.appVersion
                        it[device] = observed.device
                        it[territory] = observed.territory
                        it[locale] = observed.locale
                        it[storeUpdatedAt] = observed.storeUpdatedAt
                        it[contentHash] = hash
                        it[developerResponseBody] = observed.developerResponseBody
                        it[developerResponseAt] = observed.developerResponseAt
                        it[lastSeenAt] = seenAt
                    }
                    insertRevision(existing.id, observed, hash, seenAt)
                    val updated =
                        existing.copy(
                            state = nextState,
                            starRating = observed.starRating,
                            title = observed.title,
                            body = observed.body,
                            appVersion = observed.appVersion,
                            device = observed.device,
                            territory = observed.territory,
                            locale = observed.locale,
                            storeUpdatedAt = observed.storeUpdatedAt,
                            contentHash = hash,
                            developerResponseBody = observed.developerResponseBody,
                            developerResponseAt = observed.developerResponseAt,
                            lastSeenAt = seenAt,
                        )
                    ReviewUpsertResult(updated, ReviewUpsertOutcome.UPDATED, changes)
                }
            }
        }

    override fun findById(
        orgId: OrganizationId,
        id: ReviewId,
    ): Review? =
        transaction(database) {
            Reviews
                .selectAll()
                .where { (Reviews.orgId eq orgId) and (Reviews.id eq id) }
                .firstOrNull()
                ?.toReview()
        }

    override fun findByStoreId(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        storeReviewId: String,
    ): Review? =
        transaction(database) {
            Reviews
                .selectAll()
                .where {
                    (Reviews.orgId eq orgId) and
                        (Reviews.appId eq appId) and
                        (Reviews.platform eq platform) and
                        (Reviews.storeReviewId eq storeReviewId)
                }.firstOrNull()
                ?.toReview()
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState>,
        limit: Int,
    ): List<Review> =
        transaction(database) {
            Reviews
                .selectAll()
                .where {
                    (Reviews.orgId eq orgId) and
                        (Reviews.appId eq appId) and
                        (Reviews.state inList states.toList())
                }.orderBy(Reviews.submittedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toReview() }
        }

    override fun updateState(
        orgId: OrganizationId,
        id: ReviewId,
        state: ReviewState,
    ): Boolean =
        transaction(database) {
            Reviews.update({ (Reviews.orgId eq orgId) and (Reviews.id eq id) }) {
                it[Reviews.state] = state
            } > 0
        }

    private fun insertReview(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        hash: String,
        seenAt: Instant,
        initialState: ReviewState,
    ): Review {
        val review =
            Review(
                id = ReviewId(Uuid.random()),
                orgId = orgId,
                appId = appId,
                platform = observed.platform,
                storeReviewId = observed.storeReviewId,
                authorName = observed.authorName,
                starRating = observed.starRating,
                title = observed.title,
                body = observed.body,
                locale = observed.locale,
                territory = observed.territory,
                appVersion = observed.appVersion,
                device = observed.device,
                submittedAt = observed.submittedAt,
                storeUpdatedAt = observed.storeUpdatedAt,
                contentHash = hash,
                developerResponseBody = observed.developerResponseBody,
                developerResponseAt = observed.developerResponseAt,
                state = initialState,
                firstSeenAt = seenAt,
                lastSeenAt = seenAt,
            )
        Reviews.insert {
            it[id] = review.id
            it[Reviews.orgId] = review.orgId
            it[Reviews.appId] = review.appId
            it[platform] = review.platform
            it[storeReviewId] = review.storeReviewId
            it[authorName] = review.authorName
            it[starRating] = review.starRating.toShort()
            it[title] = review.title
            it[body] = review.body
            it[locale] = review.locale
            it[territory] = review.territory
            it[appVersion] = review.appVersion
            it[device] = review.device
            it[submittedAt] = review.submittedAt
            it[storeUpdatedAt] = review.storeUpdatedAt
            it[contentHash] = review.contentHash
            it[developerResponseBody] = review.developerResponseBody
            it[developerResponseAt] = review.developerResponseAt
            it[state] = review.state
            it[firstSeenAt] = review.firstSeenAt
            it[lastSeenAt] = review.lastSeenAt
            it[updatedAt] = review.lastSeenAt
        }
        return review
    }

    private fun detectChanges(
        existing: Review,
        observed: ObservedReview,
    ): Set<ReviewChange> =
        buildSet {
            if (existing.starRating != observed.starRating) add(ReviewChange.RATING)
            if (existing.title != observed.title || existing.body != observed.body) add(ReviewChange.TEXT)
            if (existing.appVersion != observed.appVersion) add(ReviewChange.APP_VERSION)
            if (existing.developerResponseBody != observed.developerResponseBody) {
                add(ReviewChange.DEVELOPER_RESPONSE)
            }
        }

    /** `insertIgnore` kvůli unikátu (review_id, content_hash): tutéž verzi textu zapíšeme jednou. */
    private fun insertRevision(
        reviewId: ReviewId,
        observed: ObservedReview,
        hash: String,
        seenAt: Instant,
    ) {
        ReviewRevisions.insertIgnore {
            it[id] = Uuid.random()
            it[ReviewRevisions.reviewId] = reviewId
            it[contentHash] = hash
            it[starRating] = observed.starRating.toShort()
            it[title] = observed.title
            it[body] = observed.body
            it[appVersion] = observed.appVersion
            it[developerResponseBody] = observed.developerResponseBody
            it[observedAt] = seenAt
        }
    }
}

class ExposedReviewMessageRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : ReviewMessageRepository {
    override fun claim(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
        contentHash: String,
    ): ReviewMessage =
        transaction(database) {
            val existing =
                ReviewMessages
                    .selectAll()
                    .where { scope(orgId, reviewId, channelId) and (ReviewMessages.contentHash eq contentHash) }
                    .firstOrNull()
                    ?.toReviewMessage()

            existing ?: run {
                val now = clock.now()
                val message =
                    ReviewMessage(
                        id = ReviewMessageId(Uuid.random()),
                        orgId = orgId,
                        reviewId = reviewId,
                        channelId = channelId,
                        providerConversationId = null,
                        providerMessageId = null,
                        status = MessageStatus.PENDING,
                        error = null,
                        sentAt = null,
                        contentHash = contentHash,
                        createdAt = now,
                    )
                ReviewMessages.insert {
                    it[id] = message.id
                    it[ReviewMessages.orgId] = orgId
                    it[ReviewMessages.reviewId] = reviewId
                    it[ReviewMessages.channelId] = channelId
                    it[status] = MessageStatus.PENDING
                    it[ReviewMessages.contentHash] = contentHash
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                message
            }
        }

    override fun markSent(
        orgId: OrganizationId,
        id: ReviewMessageId,
        conversationId: String?,
        messageId: String?,
        sentAt: Instant,
    ): Boolean =
        transaction(database) {
            ReviewMessages.update({ (ReviewMessages.orgId eq orgId) and (ReviewMessages.id eq id) }) {
                it[status] = MessageStatus.SENT
                it[providerConversationId] = conversationId
                it[providerMessageId] = messageId
                it[error] = null
                it[ReviewMessages.sentAt] = sentAt
            } > 0
        }

    override fun markFailed(
        orgId: OrganizationId,
        id: ReviewMessageId,
        error: String,
    ): Boolean =
        transaction(database) {
            ReviewMessages.update({ (ReviewMessages.orgId eq orgId) and (ReviewMessages.id eq id) }) {
                it[status] = MessageStatus.FAILED
                it[ReviewMessages.error] = error
            } > 0
        }

    override fun findLatestSent(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ): ReviewMessage? =
        transaction(database) {
            ReviewMessages
                .selectAll()
                .where { scope(orgId, reviewId, channelId) and (ReviewMessages.status eq MessageStatus.SENT) }
                .orderBy(ReviewMessages.createdAt to SortOrder.DESC)
                .firstOrNull()
                ?.toReviewMessage()
        }

    override fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<ReviewMessage> =
        transaction(database) {
            ReviewMessages
                .selectAll()
                .where { (ReviewMessages.orgId eq orgId) and (ReviewMessages.reviewId eq reviewId) }
                .orderBy(ReviewMessages.createdAt to SortOrder.ASC)
                .map { it.toReviewMessage() }
        }

    /**
     * Routing příchozí interakce ze Slacku/Teams. Org-scope tady chybí schválně: v tom
     * okamžiku známe jen identifikátory zprávy od providera a organizaci z nich teprve zjišťujeme.
     */
    override fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage? =
        transaction(database) {
            ReviewMessages
                .join(Channels, JoinType.INNER, ReviewMessages.channelId, Channels.id)
                .selectAll()
                .where {
                    (Channels.type eq channelType) and
                        (ReviewMessages.providerConversationId eq conversationId) and
                        (ReviewMessages.providerMessageId eq messageId)
                }.firstOrNull()
                ?.toReviewMessage()
        }

    private fun scope(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ) = (ReviewMessages.orgId eq orgId) and
        (ReviewMessages.reviewId eq reviewId) and
        (ReviewMessages.channelId eq channelId)
}

class ExposedReplyRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : ReplyRepository {
    override fun create(
        orgId: OrganizationId,
        reply: NewReply,
    ): Reply =
        transaction(database) {
            val bodyHash = sha256Hex(reply.body)
            val existing =
                Replies
                    .selectAll()
                    .where {
                        (Replies.orgId eq orgId) and
                            (Replies.reviewId eq reply.reviewId) and
                            (Replies.bodyHash eq bodyHash)
                    }.firstOrNull()
                    ?.toReply()
            existing ?: insertReply(orgId, reply, bodyHash)
        }

    override fun markPublished(
        orgId: OrganizationId,
        id: ReplyId,
        publishedAt: Instant,
    ): Boolean =
        transaction(database) {
            Replies.update({ (Replies.orgId eq orgId) and (Replies.id eq id) }) {
                it[status] = ReplyStatus.PUBLISHED
                it[error] = null
                it[Replies.publishedAt] = publishedAt
            } > 0
        }

    override fun markFailed(
        orgId: OrganizationId,
        id: ReplyId,
        error: String,
    ): Boolean =
        transaction(database) {
            Replies.update({ (Replies.orgId eq orgId) and (Replies.id eq id) }) {
                it[status] = ReplyStatus.FAILED
                it[Replies.error] = error
            } > 0
        }

    override fun findById(
        orgId: OrganizationId,
        id: ReplyId,
    ): Reply? =
        transaction(database) {
            Replies
                .selectAll()
                .where { (Replies.orgId eq orgId) and (Replies.id eq id) }
                .firstOrNull()
                ?.toReply()
        }

    override fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<Reply> =
        transaction(database) {
            Replies
                .selectAll()
                .where { (Replies.orgId eq orgId) and (Replies.reviewId eq reviewId) }
                .orderBy(Replies.createdAt to SortOrder.ASC)
                .map { it.toReply() }
        }

    override fun listByStatus(
        orgId: OrganizationId,
        status: ReplyStatus,
        limit: Int,
    ): List<Reply> =
        transaction(database) {
            Replies
                .selectAll()
                .where { (Replies.orgId eq orgId) and (Replies.status eq status) }
                .orderBy(Replies.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toReply() }
        }

    private fun insertReply(
        orgId: OrganizationId,
        reply: NewReply,
        bodyHash: String,
    ): Reply {
        val created =
            Reply(
                id = ReplyId(Uuid.random()),
                orgId = orgId,
                reviewId = reply.reviewId,
                body = reply.body,
                bodyHash = bodyHash,
                authorUserId = reply.authorUserId,
                authorExternalId = reply.authorExternalId,
                authorDisplayName = reply.authorDisplayName,
                source = reply.source,
                status = ReplyStatus.PENDING,
                error = null,
                publishedAt = null,
                createdAt = clock.now(),
            )
        Replies.insert {
            it[id] = created.id
            it[Replies.orgId] = created.orgId
            it[reviewId] = created.reviewId
            it[body] = created.body
            it[Replies.bodyHash] = created.bodyHash
            it[authorUserId] = created.authorUserId
            it[authorExternalId] = created.authorExternalId
            it[authorDisplayName] = created.authorDisplayName
            it[replySource] = created.source
            it[status] = created.status
            it[createdAt] = created.createdAt
            it[updatedAt] = created.createdAt
        }
        return created
    }
}
