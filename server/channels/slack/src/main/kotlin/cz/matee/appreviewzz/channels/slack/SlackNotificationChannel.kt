package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.MessageCatalog
import cz.matee.appreviewzz.core.message.MessageKey
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Slack jako doručovací kanál (plán §5.5). Jedna Slack App pro všechny klienty, token
 * z OAuth installu leží zašifrovaný ve vaultu a sem přichází rozbalený těsně před odesláním.
 */
class SlackNotificationChannel(
    private val api: SlackApi,
) : NotificationChannel {
    override val type: ChannelType = ChannelType.SLACK

    override suspend fun postReview(
        target: ChannelTarget,
        notification: ReviewNotification,
    ): PostedMessage =
        api.postMessage(
            token = botToken(target),
            channel = target.conversationId,
            blocks = SlackBlocks.review(notification),
            fallbackText = fallbackText(notification),
            metadata = metadata(notification),
        )

    override suspend fun markReplied(
        target: ChannelTarget,
        message: PostedMessage,
        rendering: ReplyRendering,
    ) {
        api.updateMessage(
            token = botToken(target),
            channel = message.conversationId,
            ts = message.messageId,
            blocks = SlackBlocks.replied(rendering),
            fallbackText = fallbackText(rendering.notification),
        )
    }

    override suspend fun postConnectivityCheck(
        target: ChannelTarget,
        notice: ConnectivityNotice,
    ): PostedMessage {
        val catalog = MessageCatalog.of(notice.locale)
        return api.postMessage(
            token = botToken(target),
            channel = target.conversationId,
            blocks = SlackBlocks.connectivityCheck(catalog, notice.appName),
            fallbackText = catalog[MessageKey.CONNECTION_OK_TITLE],
        )
    }

    override suspend fun postRatingsDigest(
        target: ChannelTarget,
        digest: RatingsDigest,
    ): PostedMessage =
        api.postMessage(
            token = botToken(target),
            channel = target.conversationId,
            blocks = SlackBlocks.ratingsDigest(digest),
            fallbackText = digest.fallbackText(),
        )

    override suspend fun reportFailure(
        target: ChannelTarget,
        message: PostedMessage,
        notification: ReviewNotification,
        error: String,
    ) {
        api.postMessage(
            token = botToken(target),
            channel = message.conversationId,
            blocks = SlackBlocks.failure(notification, error),
            fallbackText = ":warning: ${notification.catalog[MessageKey.REPLY_FAILED_TITLE]}: $error",
            // Do vlákna pod původní zprávou: ta zůstane i s formulářem, takže jde zkusit znovu.
            threadTs = message.messageId,
        )
    }

    /**
     * Token workspace z uložené instalace. Credential není holý token, ale payload instalace
     * (workspace, scopes) — díky tomu je v consoli vidět, kam je appka nainstalovaná, aniž by
     * se sahalo na tajemství.
     */
    private fun botToken(target: ChannelTarget): SecretPayload = SecretPayload(SlackInstall.parse(target.credential).botToken)

    /** Text do notifikace na mobilu a do náhledu kanálu — bloky se tam nevykreslí. */
    private fun fallbackText(notification: ReviewNotification): String =
        notification.catalog.format(MessageKey.APP_HAS_NEW_REVIEW, "app" to notification.appName) +
            " ${notification.starRating}/5 — ${notification.text}"

    /**
     * Routing příchozí interakce jede primárně přes databázi (`review_message` podle kanálu
     * a `ts`). Metadata jsou druhá cesta: zůstávají viditelná v payloadu i v Slack API, takže
     * u nejasné zprávy je hned poznat, ke které recenzi patřila — a bez nich by se po obnově
     * ze zálohy nedalo dohledat vůbec nic.
     */
    private fun metadata(notification: ReviewNotification): JsonObject =
        buildJsonObject {
            put("event_type", REVIEW_EVENT_TYPE)
            putJsonObject("event_payload") {
                put("org_id", notification.review.orgId.toString())
                put("app_id", notification.review.appId.toString())
                put("review_id", notification.review.id.toString())
                put("platform", notification.platform.name)
            }
        }

    companion object {
        const val REVIEW_EVENT_TYPE = "appreviewzz_review"
    }
}
