package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.message.MessageCatalog
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering

/**
 * Microsoft Teams jako doručovací kanál (plán §5.5). Rozhraní je stejné jako u Slacku, takže
 * doručovací use-case ani scheduler o Teams nevědí nic konkrétního.
 *
 * Jedna věc se od Slacku liší podstatně: **recenze zakládá vlastní konverzaci.** Ve Slacku je
 * zpráva v kanálu a `ts` je její identita; v Teams se `POST /v3/conversations` založí nové
 * vlákno a teprve v něm je karta. `targetRef` kanálu je proto teamsový kanál, kdežto
 * `conversationId` v [PostedMessage] je vlákno konkrétní recenze.
 */
class TeamsNotificationChannel(
    private val api: TeamsApi,
    private val tokens: TeamsTokens,
    private val bot: TeamsBotIdentity,
) : NotificationChannel {
    override val type: ChannelType = ChannelType.TEAMS

    override suspend fun postReview(
        target: ChannelTarget,
        notification: ReviewNotification,
    ): PostedMessage {
        val install = TeamsInstall.parse(target.credential)
        return api.createChannelConversation(
            token = tokens.accessToken(bot),
            install = install,
            // Cíl kanálu je teamsový kanál (`19:…@thread.tacv2`), ne konverzace: tu zakládáme teď.
            teamsChannelId = target.conversationId,
            card = TeamsCards.review(notification),
        )
    }

    override suspend fun markReplied(
        target: ChannelTarget,
        message: PostedMessage,
        rendering: ReplyRendering,
    ) {
        val install = TeamsInstall.parse(target.credential)
        api.updateActivity(
            token = tokens.accessToken(bot),
            serviceUrl = install.serviceUrl,
            conversationId = message.conversationId,
            activityId = message.messageId,
            card = TeamsCards.replied(rendering),
        )
    }

    override suspend fun postConnectivityCheck(
        target: ChannelTarget,
        notice: ConnectivityNotice,
    ): PostedMessage {
        val install = TeamsInstall.parse(target.credential)
        return api.createChannelConversation(
            token = tokens.accessToken(bot),
            install = install,
            teamsChannelId = target.conversationId,
            card = TeamsCards.connectivityCheck(MessageCatalog.of(notice.locale), notice.appName),
        )
    }

    override suspend fun reportFailure(
        target: ChannelTarget,
        message: PostedMessage,
        notification: ReviewNotification,
        error: String,
    ) {
        val install = TeamsInstall.parse(target.credential)
        api.replyToActivity(
            token = tokens.accessToken(bot),
            serviceUrl = install.serviceUrl,
            conversationId = message.conversationId,
            // Do vlákna pod kartu: ta zůstane i s formulářem, takže jde odpověď poslat znovu.
            replyToId = message.messageId,
            card = TeamsCards.failure(notification, error),
        )
    }
}
