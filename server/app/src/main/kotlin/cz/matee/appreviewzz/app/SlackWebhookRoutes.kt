package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackInteraction
import cz.matee.appreviewzz.channels.slack.SlackReplySubmission
import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.jobs.ReplyJobData
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

private val logger = KotlinLogging.logger {}

/** Co se s příchozí interakcí stalo. Slack dostane 200 skoro vždycky — tohle jde do logu. */
enum class IntakeResult {
    /** Odpověď je ve frontě, worker ji publikuje. */
    QUEUED,

    /** Tatáž odpověď už ve frontě je (dvojklik na „Odeslat"). */
    DUPLICATE,

    /** Zprávu neznáme: cizí workspace, smazaná recenze, nebo obnovená databáze bez historie. */
    UNKNOWN_MESSAGE,

    /** Člověk odeslal prázdný vstup. */
    EMPTY,
}

/**
 * Převzetí odpovědi ze Slacku. Dělá jen dvě věci — najde recenzi podle `channel` + `ts` a
 * zařadí úlohu — protože Slack čeká na potvrzení do tří sekund a publikace do storu trvá dýl.
 *
 * Recenze se dohledává **v databázi**, ne z metadat zprávy: metadata jdou přes Slack a zpátky,
 * kdežto `review_message` je náš vlastní záznam, který navíc nese org a kanál. Cizí zpráva
 * tak nemá jak podstrčit odpověď do jiné organizace.
 */
class SlackReplyIntake(
    private val messages: ReviewMessageRepository,
    private val enqueue: (ReplyJobData) -> Boolean,
) {
    fun accept(submission: SlackReplySubmission): IntakeResult {
        val text = submission.text.trim()
        if (text.isEmpty()) return IntakeResult.EMPTY

        val message =
            messages.findByProviderMessage(ChannelType.SLACK, submission.conversationId, submission.messageTs)
                ?: return IntakeResult.UNKNOWN_MESSAGE

        val queued =
            enqueue(
                ReplyJobData(
                    orgId = message.orgId.toString(),
                    reviewId = message.reviewId.toString(),
                    channelId = message.channelId.toString(),
                    body = text,
                    source = ReplySource.SLACK.name,
                    authorExternalId = submission.userId,
                    authorDisplayName = submission.userName,
                ),
            )
        return if (queued) IntakeResult.QUEUED else IntakeResult.DUPLICATE
    }
}

/**
 * Interactivity endpoint Slack Appky. Ověření podpisu je jediná autentizace, kterou tenhle
 * endpoint má — proto se dělá **nad syrovým tělem** požadavku, před jakýmkoli parsováním.
 */
fun Application.slackWebhookRoutes(
    verifier: SlackSignatureVerifier,
    intake: SlackReplyIntake,
) {
    routing {
        post(SLACK_INTERACTIVITY_PATH) {
            val rawBody = call.receiveText()
            val failure =
                verifier.verify(
                    timestamp = call.request.header(SlackSignatureVerifier.TIMESTAMP_HEADER),
                    signature = call.request.header(SlackSignatureVerifier.SIGNATURE_HEADER),
                    rawBody = rawBody,
                )
            if (failure != null) {
                logger.warn { "Slack interactivity: požadavek odmítnut ($failure)" }
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val payload = rawBody.formField("payload")
            val submission = payload?.let { SlackInteraction.parse(it) }
            if (submission == null) {
                // Kliknutí na jiný prvek nebo typ interakce, který neobsluhujeme: potvrdit a zahodit.
                call.respond(HttpStatusCode.OK)
                return@post
            }

            val result = intake.accept(submission)
            if (result == IntakeResult.UNKNOWN_MESSAGE) {
                logger.warn {
                    "Slack interactivity: zpráva ${submission.conversationId}/${submission.messageTs} není v databázi"
                }
            } else {
                logger.info { "Slack interactivity: odpověď na zprávu ${submission.messageTs} — $result" }
            }
            // I neznámá zpráva dostane 200: opakované doručení Slacku by dopadlo stejně.
            call.respond(HttpStatusCode.OK)
        }
    }
}

const val SLACK_INTERACTIVITY_PATH = "/webhooks/slack/interactivity"

/**
 * Jedno pole z formulářového těla. Dekóduje se **s `plusIsSpace`**: Slack posílá payload jako
 * `application/x-www-form-urlencoded`, kde jsou mezery plusy — bez toho by odpověď o víc než
 * jednom slově dorazila s plusy místo mezer a JSON by se ani nepodařilo přečíst.
 */
private fun String.formField(name: String): String? =
    split('&')
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.decodeURLQueryComponent(plusIsSpace = true)
