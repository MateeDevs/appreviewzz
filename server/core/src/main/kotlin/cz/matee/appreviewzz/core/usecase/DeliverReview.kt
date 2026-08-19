package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Proč se recenze nedoručila nikam. Žádný z důvodů není chyba — jen stav, který má být vidět. */
enum class DeliverySkipReason {
    REVIEW_NOT_FOUND,
    APP_NOT_FOUND,
    APP_DISABLED,

    /** Recenze je pod watermarkem appky: uložená kvůli historii, ale bez notifikace. */
    SUPPRESSED,

    /** Appka nemá zapnutý žádný kanál pro recenze. */
    NO_CHANNEL,
}

/** Proč se přeskočil jeden kanál, zatímco ostatní doručit mohly. */
enum class ChannelSkipReason {
    /** Kanál tohohle typu v běžícím procesu není (self-host bez Teams). */
    NO_IMPLEMENTATION,

    /** Kanál nemá připojený credential — typicky nedokončený onboarding. */
    MISSING_CREDENTIAL,
}

sealed interface ChannelDelivery {
    val channelId: ChannelId

    data class Sent(
        override val channelId: ChannelId,
        val message: PostedMessage,
    ) : ChannelDelivery

    /** Tohle znění recenze už v kanálu je (opakovaný běh, retry po částečném selhání). */
    data class AlreadySent(
        override val channelId: ChannelId,
    ) : ChannelDelivery

    data class Skipped(
        override val channelId: ChannelId,
        val reason: ChannelSkipReason,
    ) : ChannelDelivery

    data class Failed(
        override val channelId: ChannelId,
        val kind: ChannelErrorKind,
        val message: String,
    ) : ChannelDelivery {
        val isRetryable: Boolean get() = kind == ChannelErrorKind.RATE_LIMITED || kind == ChannelErrorKind.TRANSIENT
    }
}

data class DeliveryReport(
    val orgId: OrganizationId,
    val reviewId: ReviewId,
    val deliveries: List<ChannelDelivery> = emptyList(),
    val skipped: DeliverySkipReason? = null,
    /** AI selhala, ale zpráva odešla bez návrhu — patří do delivery health, ne do DLQ. */
    val suggestionError: String? = null,
) {
    val sent: List<ChannelDelivery.Sent> get() = deliveries.filterIsInstance<ChannelDelivery.Sent>()

    val failures: List<ChannelDelivery.Failed> get() = deliveries.filterIsInstance<ChannelDelivery.Failed>()

    /** Retry má smysl jen u limitů a výpadků; chybějící scope opakováním nepřibude. */
    val isRetryable: Boolean get() = failures.any { it.isRetryable }
}

/**
 * Doručení jedné recenze do všech kanálů appky (plán §5.5). Běží jako samostatná úloha za
 * ingestem, takže pomalá AI ani nedostupný Slack nebrzdí stahování recenzí a retry se dá
 * odstupňovat per recenzi.
 *
 * Idempotence stojí na `review_message`: záznam se **nejdřív rezervuje** pro trojici
 * (recenze, kanál, otisk znění) a teprve pak se posílá. Opakovaný běh tak zprávu nepošle
 * dvakrát, ale editovaná recenze (jiný otisk) vlastní zprávu dostane — přesně to, co dnešní
 * seznam zpracovaných ID v n8n neumí.
 *
 * Návrh od AI se generuje **jednou na recenzi**, ne jednou na kanál: dva kanály téže appky
 * jsou dva různé týmy, ale recenze je pořád jedna a účet za tokeny taky.
 */
class DeliverReviewUseCase(
    private val apps: AppRepository,
    private val reviews: ReviewRepository,
    private val channels: ChannelRepository,
    private val messages: ReviewMessageRepository,
    private val secrets: SecretResolver,
    private val suggestions: SuggestReplyProvider,
    notificationChannels: List<NotificationChannel>,
    private val clock: Clock = Clock.System,
) {
    private val channelByType: Map<ChannelType, NotificationChannel> = notificationChannels.associateBy { it.type }

    init {
        require(channelByType.size == notificationChannels.size) {
            "Pro jeden typ kanálu je zaregistrovaná víc než jedna implementace"
        }
    }

    suspend fun deliver(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): DeliveryReport {
        val review =
            reviews.findById(orgId, reviewId)
                ?: return DeliveryReport(orgId, reviewId, skipped = DeliverySkipReason.REVIEW_NOT_FOUND)
        if (review.state == ReviewState.SUPPRESSED) {
            return DeliveryReport(orgId, reviewId, skipped = DeliverySkipReason.SUPPRESSED)
        }
        val app =
            apps.findById(orgId, review.appId)
                ?: return DeliveryReport(orgId, reviewId, skipped = DeliverySkipReason.APP_NOT_FOUND)
        if (!app.enabled) return DeliveryReport(orgId, reviewId, skipped = DeliverySkipReason.APP_DISABLED)

        val targets =
            channels
                .listByApp(orgId, app.id)
                .filter { it.enabled && it.deliverReviews }
        if (targets.isEmpty()) return DeliveryReport(orgId, reviewId, skipped = DeliverySkipReason.NO_CHANNEL)

        val suggestion = suggest(app, review)
        val deliveries = targets.map { channel -> deliverTo(app, review, channel, suggestion) }
        if (deliveries.any { it is ChannelDelivery.Sent } && review.state != ReviewState.NOTIFIED) {
            reviews.updateState(orgId, reviewId, ReviewState.NOTIFIED)
        }

        val report =
            DeliveryReport(
                orgId = orgId,
                reviewId = reviewId,
                deliveries = deliveries,
                suggestionError = (suggestion as? ReplySuggestion.Failed)?.message,
            )
        logger.info {
            "Doručení recenze $reviewId: odesláno=${report.sent.size} chyb=${report.failures.size} " +
                "kanálů=${targets.size}"
        }
        return report
    }

    private suspend fun deliverTo(
        app: App,
        review: Review,
        channel: Channel,
        suggestion: ReplySuggestion,
    ): ChannelDelivery {
        val implementation =
            channelByType[channel.type]
                ?: return ChannelDelivery.Skipped(channel.id, ChannelSkipReason.NO_IMPLEMENTATION)
        val credentialId =
            channel.credentialId
                ?: return ChannelDelivery.Skipped(channel.id, ChannelSkipReason.MISSING_CREDENTIAL)

        // Rezervace před odesláním: kdyby proces spadl mezi odesláním a zápisem, zůstane po
        // zprávě stopa ve stavu PENDING místo tichého duplikátu při dalším běhu.
        val message = messages.claim(app.orgId, review.id, channel.id, review.contentHash)
        if (message.sentAt != null) return ChannelDelivery.AlreadySent(channel.id)

        val notification =
            ReviewNotification(
                review = review,
                appName = app.name,
                timezone = app.timezone,
                locale = channel.locale,
                suggestedReply = (suggestion as? ReplySuggestion.Suggested)?.text,
                isUpdate = review.state == ReviewState.UPDATED,
            )

        return try {
            val target = ChannelTarget(channel.targetRef, secrets.resolve(app.orgId, credentialId))
            val posted = implementation.postReview(target, notification)
            messages.markSent(app.orgId, message.id, posted.conversationId, posted.messageId, clock.now())
            ChannelDelivery.Sent(channel.id, posted)
        } catch (error: ChannelException) {
            val detail = error.message.orEmpty()
            messages.markFailed(app.orgId, message.id, detail)
            logger.warn { "Doručení recenze ${review.id} do kanálu ${channel.id} selhalo (${error.kind}): $detail" }
            ChannelDelivery.Failed(channel.id, error.kind, detail)
        }
    }

    /** Návrh odpovědi. Selhání AI se jen zaznamená — recenze musí dorazit i bez něj. */
    private suspend fun suggest(
        app: App,
        review: Review,
    ): ReplySuggestion {
        val suggestion = suggestions.suggest(ReplySuggestionRequest.of(app, review))
        if (suggestion is ReplySuggestion.Failed) {
            logger.warn { "Návrh odpovědi na recenzi ${review.id} se nepovedl: ${suggestion.message}" }
        }
        return suggestion
    }
}
