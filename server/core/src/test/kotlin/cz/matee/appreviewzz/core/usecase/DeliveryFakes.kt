package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.ReviewMessageId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object Delivery {
    val now: Instant = Instant.parse("2026-08-19T12:00:00Z")

    fun review(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform = Platform.ANDROID,
        state: ReviewState = ReviewState.NEW,
        developerResponse: String? = null,
    ): Review =
        Review(
            id = ReviewId(Uuid.random()),
            orgId = orgId,
            appId = appId,
            platform = platform,
            storeReviewId = "gp:AOqpTO",
            authorName = "Jana N.",
            starRating = 2,
            title = null,
            body = "Po updatu se nedostanu dál.",
            locale = "cs",
            territory = "CZ",
            appVersion = "3.2.1",
            device = null,
            submittedAt = now,
            storeUpdatedAt = null,
            contentHash = "hash-1",
            developerResponseBody = developerResponse,
            developerResponseAt = null,
            state = state,
            firstSeenAt = now,
            lastSeenAt = now,
        )

    fun channel(
        orgId: OrganizationId,
        appId: AppId,
        type: ChannelType = ChannelType.SLACK,
        credentialId: CredentialId? = CredentialId(Uuid.random()),
        enabled: Boolean = true,
        deliverReviews: Boolean = true,
        locale: MessageLocale = MessageLocale.CS,
    ): Channel =
        Channel(
            id = ChannelId(Uuid.random()),
            orgId = orgId,
            appId = appId,
            type = type,
            credentialId = credentialId,
            targetRef = "C0123",
            targetLabel = "#recenze",
            locale = locale,
            deliverReviews = deliverReviews,
            deliverRatings = true,
            enabled = enabled,
        )
}

internal class FakeReviewRepository(
    private val reviews: MutableList<Review> = mutableListOf(),
) : ReviewRepository {
    val stateUpdates = mutableListOf<Pair<ReviewId, ReviewState>>()

    fun put(review: Review): Review = review.also { reviews += it }

    override fun findById(
        orgId: OrganizationId,
        id: ReviewId,
    ): Review? = reviews.firstOrNull { it.orgId == orgId && it.id == id }

    override fun updateState(
        orgId: OrganizationId,
        id: ReviewId,
        state: ReviewState,
    ): Boolean {
        stateUpdates += id to state
        return true
    }

    override fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        seenAt: Instant,
        initialState: ReviewState,
    ): ReviewUpsertResult = unused()

    override fun findByStoreId(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        storeReviewId: String,
    ): Review? = unused()

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState>,
        limit: Int,
    ): List<Review> = unused()
}

internal class FakeChannelRepository(
    private val channels: MutableList<Channel> = mutableListOf(),
) : ChannelRepository {
    fun put(channel: Channel): Channel = channel.also { channels += it }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<Channel> = channels.filter { it.orgId == orgId && it.appId == appId }

    override fun findById(
        orgId: OrganizationId,
        id: ChannelId,
    ): Channel? = channels.firstOrNull { it.orgId == orgId && it.id == id }

    override fun create(
        orgId: OrganizationId,
        channel: NewChannel,
    ): Channel = unused()

    override fun setEnabled(
        orgId: OrganizationId,
        id: ChannelId,
        enabled: Boolean,
    ): Boolean = unused()

    override fun delete(
        orgId: OrganizationId,
        id: ChannelId,
    ): Boolean = unused()
}

/** Drží rezervace v paměti se stejnou unikátností jako databáze: (recenze, kanál, otisk). */
internal class FakeReviewMessageRepository : ReviewMessageRepository {
    private val messages = mutableMapOf<Triple<ReviewId, ChannelId, String>, ReviewMessage>()

    val sent get() = messages.values.filter { it.status == MessageStatus.SENT }
    val failed get() = messages.values.filter { it.status == MessageStatus.FAILED }

    override fun claim(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
        contentHash: String,
    ): ReviewMessage =
        messages.getOrPut(Triple(reviewId, channelId, contentHash)) {
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
                createdAt = Delivery.now,
            )
        }

    override fun markSent(
        orgId: OrganizationId,
        id: ReviewMessageId,
        conversationId: String?,
        messageId: String?,
        sentAt: Instant,
    ): Boolean =
        update(id) {
            it.copy(status = MessageStatus.SENT, providerConversationId = conversationId, providerMessageId = messageId, sentAt = sentAt)
        }

    override fun markFailed(
        orgId: OrganizationId,
        id: ReviewMessageId,
        error: String,
    ): Boolean = update(id) { it.copy(status = MessageStatus.FAILED, error = error) }

    override fun findLatestSent(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ): ReviewMessage? =
        messages.values.lastOrNull { it.reviewId == reviewId && it.channelId == channelId && it.status == MessageStatus.SENT }

    override fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<ReviewMessage> = messages.values.filter { it.reviewId == reviewId }

    override fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage? = messages.values.firstOrNull { it.providerConversationId == conversationId && it.providerMessageId == messageId }

    private fun update(
        id: ReviewMessageId,
        change: (ReviewMessage) -> ReviewMessage,
    ): Boolean {
        val key = messages.entries.firstOrNull { it.value.id == id }?.key ?: return false
        messages[key] = change(messages.getValue(key))
        return true
    }
}

/** Kanál, který místo Slacku zapisuje, co by odeslal — nebo předvede připravené selhání. */
internal class FakeNotificationChannel(
    override val type: ChannelType = ChannelType.SLACK,
    private val failWith: ChannelException? = null,
) : NotificationChannel {
    val posted = mutableListOf<Pair<ChannelTarget, ReviewNotification>>()
    val replied = mutableListOf<ReplyRendering>()
    val failures = mutableListOf<String>()

    override suspend fun postReview(
        target: ChannelTarget,
        notification: ReviewNotification,
    ): PostedMessage {
        failWith?.let { throw it }
        posted += target to notification
        return PostedMessage(target.conversationId, "1755600000.${posted.size}")
    }

    override suspend fun markReplied(
        target: ChannelTarget,
        message: PostedMessage,
        rendering: ReplyRendering,
    ) {
        replied += rendering
    }

    override suspend fun reportFailure(
        target: ChannelTarget,
        message: PostedMessage,
        notification: ReviewNotification,
        error: String,
    ) {
        failures += error
    }
}

internal class FakeSuggestProvider(
    private val response: ReplySuggestion,
) : SuggestReplyProvider {
    var calls = 0
        private set

    override suspend fun suggest(request: ReplySuggestionRequest): ReplySuggestion {
        calls++
        return response
    }
}

private fun unused(): Nothing = error("Metoda se v testu doručení nepoužívá")
