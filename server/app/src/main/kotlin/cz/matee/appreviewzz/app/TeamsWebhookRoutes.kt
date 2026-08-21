package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.teams.BotFrameworkAuthenticator
import cz.matee.appreviewzz.channels.teams.TeamsActivity
import cz.matee.appreviewzz.channels.teams.TeamsReplySubmission
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.jobs.ReplyJobData
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

private val logger = KotlinLogging.logger {}

/**
 * Převzetí odpovědi z Teams. Dělá totéž co [SlackReplyIntake] a ze stejného důvodu: karta je
 * `Action.Submit`, takže Bot Connector čeká jen na potvrzení, kdežto publikace do storu trvá dýl.
 *
 * Recenze se dohledává **v databázi** podle konverzace a aktivity, ne z payloadu tlačítka.
 * Dnešní n8n bere `clientId` i celý obsah recenze z toho, co přišlo zpátky přes Teams —
 * kdokoli s přístupem ke kartě by tak uměl podstrčit odpověď do jiné organizace.
 */
class TeamsReplyIntake(
    private val messages: ReviewMessageRepository,
    private val enqueue: (ReplyJobData) -> Boolean,
) {
    fun accept(submission: TeamsReplySubmission): IntakeResult {
        val text = submission.text.trim()
        if (text.isEmpty()) return IntakeResult.EMPTY

        val message =
            messages.findByProviderMessage(ChannelType.TEAMS, submission.conversationId, submission.activityId)
                ?: return IntakeResult.UNKNOWN_MESSAGE

        val queued =
            enqueue(
                ReplyJobData(
                    orgId = message.orgId.toString(),
                    reviewId = message.reviewId.toString(),
                    channelId = message.channelId.toString(),
                    body = text,
                    source = ReplySource.TEAMS.name,
                    authorExternalId = submission.userId,
                    authorDisplayName = submission.userName,
                ),
            )
        return if (queued) IntakeResult.QUEUED else IntakeResult.DUPLICATE
    }
}

/**
 * Messaging endpoint Azure Bota. Ověření tokenu od Bot Connectoru je jediná autentizace, kterou
 * tenhle endpoint má — proto se dělá dřív, než se z těla použije cokoli jiného než pole nutná
 * k samotnému ověření (`serviceUrl`, `channelId`).
 */
fun Application.teamsWebhookRoutes(
    authenticator: BotFrameworkAuthenticator,
    intake: TeamsReplyIntake,
) {
    routing {
        post(TEAMS_MESSAGES_PATH) {
            val activity = TeamsActivity.parse(call.receiveText())
            if (activity == null) {
                // Nečitelné tělo je buď cizí sken, nebo rozbitý klient; odmítnout, ne přijmout.
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            val failure = authenticator.verify(call.request.header("Authorization"), activity)
            if (failure != null) {
                logger.warn { "Teams messaging endpoint: aktivita odmítnuta ($failure)" }
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            val submission = activity.replySubmission()
            if (submission == null) {
                // Přidání bota do týmu, systémové zprávy: potvrdit a zahodit.
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val result = intake.accept(submission)
            if (result == IntakeResult.UNKNOWN_MESSAGE) {
                logger.warn {
                    "Teams: karta ${submission.conversationId}/${submission.activityId} není v databázi"
                }
            } else {
                logger.info { "Teams: odpověď na kartu ${submission.activityId} — $result" }
            }
            // I neznámá karta dostane 200: opakované doručení by dopadlo stejně.
            call.respond(HttpStatusCode.OK)
        }
    }
}

const val TEAMS_MESSAGES_PATH = "/webhooks/teams/messages"
