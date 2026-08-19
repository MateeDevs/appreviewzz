package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.Channel
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Reply
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.NewReply
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReplyRepository
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Odkud odpověď přišla a kdo ji napsal. Kanál je vyplněný jen u odpovědí z chatu. */
data class ReplyCommand(
    val orgId: OrganizationId,
    val reviewId: ReviewId,
    val body: String,
    val source: ReplySource,
    /** Kanál, ve kterém se na tlačítko kliklo — tam se pak hlásí výsledek. */
    val channelId: ChannelId? = null,
    val authorUserId: UserId? = null,
    /** Slack user ID, resp. Teams AAD ID — člověka známe jen podle chat identity. */
    val authorExternalId: String? = null,
    val authorDisplayName: String? = null,
)

/** Proč se odpověď vůbec nezkusila publikovat. Žádný z důvodů nemá smysl opakovat. */
enum class ReplyRejection {
    EMPTY_BODY,
    REVIEW_NOT_FOUND,
    APP_NOT_FOUND,

    /** Klient nepřipojil klíč s právem odpovídat (nebo ho odpojil). */
    MISSING_CREDENTIAL,

    /** Konektor pro platformu v tomhle procesu není. */
    NO_TARGET,
}

sealed interface ReplyOutcome {
    data class Published(
        val reply: Reply,
    ) : ReplyOutcome

    /** Dvojklik na „Odeslat": tatáž odpověď na tutéž recenzi už ve storu je. */
    data class AlreadyPublished(
        val reply: Reply,
    ) : ReplyOutcome

    data class Rejected(
        val reason: ReplyRejection,
    ) : ReplyOutcome

    data class Failed(
        val kind: StoreErrorKind,
        val message: String,
    ) : ReplyOutcome {
        val isRetryable: Boolean get() = kind == StoreErrorKind.RATE_LIMITED || kind == StoreErrorKind.TRANSIENT
    }
}

/**
 * Publikace odpovědi do storu a úklid v kanálu (plán §5.5, inventura 02-reply).
 *
 * Tři věci, které dnešní n8n řešení nedělá:
 *
 * - **Odpověď je záznam, ne jen HTTP volání.** Vzniká ve stavu PENDING a teprve pak se
 *   publikuje, takže se ví, co se odeslalo, kdo to napsal a jak to dopadlo. Dvojklik na
 *   „Odeslat" narazí na unikátní otisk textu a druhou odpověď neposílá.
 * - **Výsledek se hlásí každému klientovi**, ne jen tomu, který je natvrdo ve switchi
 *   („HARDCODE ISLE GROW KEY"): dnes se u ostatních klientů odpověď sice odešle, ale zpráva
 *   se nikdy neupraví ani nenahlásí chyba.
 * - **Neúspěch kanálu nezruší úspěch storu.** Odpověď je publikovaná; když se nepovede
 *   přepsat zprávu ve Slacku, je to řádek v logu, ne důvod k opakování celé publikace.
 */
class PublishReplyUseCase(
    private val apps: AppRepository,
    private val reviews: ReviewRepository,
    private val replies: ReplyRepository,
    private val channels: ChannelRepository,
    private val messages: ReviewMessageRepository,
    private val credentials: CredentialRepository,
    private val secrets: SecretResolver,
    private val audit: AuditLogRepository,
    replyTargets: List<ReplyTarget>,
    notificationChannels: List<NotificationChannel> = emptyList(),
    private val clock: Clock = Clock.System,
) {
    private val targetByPlatform: Map<Platform, ReplyTarget> = replyTargets.associateBy { it.platform }
    private val channelByType: Map<ChannelType, NotificationChannel> = notificationChannels.associateBy { it.type }

    suspend fun publish(command: ReplyCommand): ReplyOutcome {
        val body = command.body.trim()
        if (body.isEmpty()) return ReplyOutcome.Rejected(ReplyRejection.EMPTY_BODY)

        val review =
            reviews.findById(command.orgId, command.reviewId)
                ?: return ReplyOutcome.Rejected(ReplyRejection.REVIEW_NOT_FOUND)
        val app =
            apps.findById(command.orgId, review.appId)
                ?: return ReplyOutcome.Rejected(ReplyRejection.APP_NOT_FOUND)
        val target =
            targetByPlatform[review.platform]
                ?: return ReplyOutcome.Rejected(ReplyRejection.NO_TARGET)

        // Ořez na limit storu tady i v konektoru: tady kvůli tomu, aby se do databáze uložilo
        // přesně to, co se odeslalo.
        val text = body.take(target.replyMaxLength)
        val reply =
            replies.create(
                command.orgId,
                NewReply(
                    reviewId = review.id,
                    body = text,
                    source = command.source,
                    authorUserId = command.authorUserId,
                    authorExternalId = command.authorExternalId,
                    authorDisplayName = command.authorDisplayName,
                ),
            )
        if (reply.status == ReplyStatus.PUBLISHED) return ReplyOutcome.AlreadyPublished(reply)

        val credential =
            credentials.findForApp(command.orgId, app.id, CredentialPurpose.REPLIES, credentialType(review.platform))
                ?: return ReplyOutcome.Rejected(ReplyRejection.MISSING_CREDENTIAL)
        val identifier =
            app.storeIdentifier(review.platform)
                ?: return ReplyOutcome.Rejected(ReplyRejection.NO_TARGET)

        return try {
            val context = StoreContext(identifier, secrets.resolve(command.orgId, credential.id))
            val published = target.publishReply(context, review.storeReviewId, text)
            replies.markPublished(command.orgId, reply.id, published.publishedAt)
            reviews.updateState(command.orgId, review.id, ReviewState.REPLIED)
            audit.append(
                auditEntry(
                    orgId = command.orgId,
                    action = "reply.published",
                    actorType = if (command.authorUserId != null) ActorType.USER else ActorType.CHAT,
                    actorUserId = command.authorUserId,
                    actorLabel = command.authorDisplayName ?: command.authorExternalId,
                    targetType = "review",
                    targetId = review.id.toString(),
                    metadata = mapOf("platform" to review.platform.name, "source" to command.source.name),
                ),
            )
            markRepliedInChannel(app, review, command, text)
            logger.info { "Odpověď na recenzi ${review.id} publikovaná (${review.platform})" }
            ReplyOutcome.Published(reply.copy(status = ReplyStatus.PUBLISHED, publishedAt = published.publishedAt))
        } catch (error: StoreConnectorException) {
            val detail = error.message.orEmpty()
            replies.markFailed(command.orgId, reply.id, detail)
            logger.warn { "Publikace odpovědi na recenzi ${review.id} selhala (${error.kind}): $detail" }
            reportFailureInChannel(app, review, command, detail)
            ReplyOutcome.Failed(error.kind, detail)
        }
    }

    /** Zpráva v kanálu ztratí formulář a ukáže odeslanou odpověď. */
    private suspend fun markRepliedInChannel(
        app: App,
        review: Review,
        command: ReplyCommand,
        text: String,
    ) {
        withOriginChannel(app, review, command) { channel, implementation, target, message, notification ->
            implementation.markReplied(
                target = target,
                message = message,
                rendering =
                    ReplyRendering(
                        notification = notification,
                        replyText = text,
                        authorDisplayName = command.authorDisplayName,
                        repliedAt = clock.now(),
                    ),
            )
            logger.debug { "Zpráva v kanálu ${channel.id} označená jako odpovězená" }
        }
    }

    /** Chyba publikace jde do vlákna pod původní zprávu; formulář zůstává, dá se zkusit znovu. */
    private suspend fun reportFailureInChannel(
        app: App,
        review: Review,
        command: ReplyCommand,
        error: String,
    ) {
        withOriginChannel(app, review, command) { _, implementation, target, message, notification ->
            implementation.reportFailure(target, message, notification, error)
        }
    }

    private suspend fun withOriginChannel(
        app: App,
        review: Review,
        command: ReplyCommand,
        block: suspend (Channel, NotificationChannel, ChannelTarget, PostedMessage, ReviewNotification) -> Unit,
    ) {
        val channelId = command.channelId ?: return
        val channel = channels.findById(command.orgId, channelId) ?: return
        val implementation = channelByType[channel.type] ?: return
        val credentialId = channel.credentialId ?: return
        val message = messages.findLatestSent(command.orgId, review.id, channel.id) ?: return
        val conversationId = message.providerConversationId ?: channel.targetRef
        val messageId = message.providerMessageId ?: return

        val notification =
            ReviewNotification(
                review = review,
                appName = app.name,
                timezone = app.timezone,
                locale = channel.locale,
                suggestedReply = null,
            )

        try {
            block(
                channel,
                implementation,
                ChannelTarget(conversationId, secrets.resolve(command.orgId, credentialId)),
                PostedMessage(conversationId, messageId),
                notification,
            )
        } catch (error: ChannelException) {
            // Odpověď už ve storu je; nepovedený úklid zprávy nesmí vyvolat další pokus o publikaci.
            logger.warn { "Úprava zprávy v kanálu ${channel.id} selhala (${error.kind}): ${error.message}" }
        }
    }

    private fun credentialType(platform: Platform): CredentialType =
        when (platform) {
            Platform.ANDROID -> CredentialType.GP_SERVICE_ACCOUNT
            Platform.IOS -> CredentialType.ASC_API_KEY
        }
}
