package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackEnvironment
import cz.matee.appreviewzz.channels.slack.SlackMessageRouting
import cz.matee.appreviewzz.channels.slack.SlackRelayAuthentication
import cz.matee.appreviewzz.channels.slack.SlackReplySubmission
import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewMessage
import cz.matee.appreviewzz.core.model.ReviewMessageId
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ReviewMessageRepository
import cz.matee.appreviewzz.jobs.ReplyJobData
import cz.matee.appreviewzz.jobs.SlackRelayJobData
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val routingNow = Instant.parse("2026-11-02T12:00:00Z")
private val routingClock =
    object : Clock {
        override fun now(): Instant = routingNow
    }
private val sharedSecret = SecretPayload("one-app-staging-and-production")
private val messageRouting = SlackMessageRouting(sharedSecret)
private val relayAuthentication = SlackRelayAuthentication(sharedSecret, routingClock)
private val routedMessage =
    ReviewMessage(
        ReviewMessageId(Uuid.random()),
        OrganizationId(Uuid.random()),
        ReviewId(Uuid.random()),
        ChannelId(Uuid.random()),
        "C_SHARED",
        "123.456",
        MessageStatus.SENT,
        null,
        routingNow,
        "hash",
        routingNow,
    )

private class RoutingMessageRepository : ReviewMessageRepository {
    override fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage? = routedMessage.takeIf { conversationId == it.providerConversationId && messageId == it.providerMessageId }

    override fun claim(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
        contentHash: String,
    ): ReviewMessage = error("unused")

    override fun markSent(
        orgId: OrganizationId,
        id: ReviewMessageId,
        conversationId: String?,
        messageId: String?,
        sentAt: Instant,
    ): Boolean = error("unused")

    override fun markFailed(
        orgId: OrganizationId,
        id: ReviewMessageId,
        error: String,
    ): Boolean = error("unused")

    override fun findLatestSent(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ): ReviewMessage? = error("unused")

    override fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<ReviewMessage> = error("unused")
}

private fun routeValue(
    environment: SlackEnvironment,
    review: String = routedMessage.reviewId.toString(),
): String = messageRouting.issue(environment, routedMessage.orgId.toString(), review)

private fun interaction(value: String): String =
    """{
  "type":"block_actions","team":{"id":"T_SHARED"},"user":{"id":"U1","name":"Tester"},
  "container":{"channel_id":"C_SHARED","message_ts":"123.456"},
  "actions":[{"action_id":"submit_reply","value":"$value"}],
  "state":{"values":{"appreviewzz_reply":{"reply_text":{"value":"Thanks"}}}},
  "response_url":"https://hooks.slack.test/secret-response-token"
}"""

private suspend fun HttpClient.slackPost(value: String) =
    post(SLACK_INTERACTIVITY_PATH) {
        val raw = "payload=" + URLEncoder.encode(interaction(value), Charsets.UTF_8)
        val timestamp = routingNow.epochSeconds.toString()
        val mac =
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(sharedSecret.value.toByteArray(), "HmacSHA256"))
            }
        val signature = "v0=" + mac.doFinal("v0:$timestamp:$raw".toByteArray()).joinToString("") { "%02x".format(it) }
        header(SlackSignatureVerifier.TIMESTAMP_HEADER, timestamp)
        header(SlackSignatureVerifier.SIGNATURE_HEADER, signature)
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(raw)
    }

private suspend fun HttpClient.relayPost(
    data: SlackRelayJobData,
    signatureBody: String = data.payload,
) = post(SlackRelayAuthentication.PATH) {
    val environment = checkNotNull(SlackEnvironment.parse(data.environment))
    val timestamp = routingNow.epochSeconds.toString()
    header(SlackRelayAuthentication.ENVIRONMENT, data.environment)
    header(SlackRelayAuthentication.DELIVERY_ID, data.deliveryId)
    header(SlackRelayAuthentication.TIMESTAMP, timestamp)
    header(SlackRelayAuthentication.SIGNATURE, relayAuthentication.sign(environment, data.deliveryId, timestamp, signatureBody))
    setBody(data.payload)
}

class SlackRoutingRoutesTest :
    StringSpec({
        for (source in listOf(SlackEnvironment.PROD, SlackEnvironment.STAGING)) {
            val destination = if (source == SlackEnvironment.PROD) SlackEnvironment.STAGING else SlackEnvironment.PROD
            "jedna app směruje z ${source.wireName} do ${destination.wireName}, stejný workspace nezamění prostředí" {
                val local = mutableListOf<ReplyJobData>()
                val relayed = mutableListOf<SlackRelayJobData>()
                testApplication {
                    application {
                        val intake =
                            SlackReplyIntake(RoutingMessageRepository()) {
                                local += it
                                true
                            }
                        val dispatcher =
                            SlackInteractionDispatcher(
                                source,
                                messageRouting,
                                relayAuthentication,
                                intake,
                                setOf(destination),
                            ) {
                                relayed += it
                                true
                            }
                        slackWebhookRoutes(SlackSignatureVerifier(sharedSecret, routingClock), intake, dispatcher = dispatcher)
                    }
                    client.slackPost(routeValue(destination)).status shouldBe HttpStatusCode.OK
                    local.size shouldBe 0
                    relayed.size shouldBe 1
                    relayed.single().environment shouldBe destination.wireName
                    relayed.single().payload shouldNotContain "secret-response-token"
                    relayed.single().toString() shouldNotContain "Thanks"
                    // Podvržení prostředí zneplatní podpis hodnoty tlačítka.
                    client.slackPost(routeValue(destination).replace(destination.wireName, source.wireName)).status shouldBe
                        HttpStatusCode.BadRequest
                    local.size shouldBe 0
                }
                val published = mutableListOf<ReplyJobData>()
                testApplication {
                    application {
                        val intake =
                            SlackReplyIntake(RoutingMessageRepository()) {
                                published += it
                                true
                            }
                        slackRelayRoutes(
                            SlackInteractionDispatcher(
                                destination,
                                messageRouting,
                                relayAuthentication,
                                intake,
                                setOf(source),
                            ) { error("Relay nesmí vytvořit smyčku") },
                        )
                    }
                    client.relayPost(relayed.single(), "tampered").status shouldBe HttpStatusCode.Unauthorized
                    client.relayPost(relayed.single()).status shouldBe HttpStatusCode.OK
                    client.relayPost(relayed.single()).status shouldBe HttpStatusCode.OK
                    published.size shouldBe 1
                    published.single().reviewId shouldBe routedMessage.reviewId.toString()
                }
            }
        }

        "lokální interakce ověřuje i identitu recenze, staré zprávy zpracuje pouze místní DB" {
            val queue = mutableListOf<ReplyJobData>()
            testApplication {
                application {
                    val intake =
                        SlackReplyIntake(RoutingMessageRepository()) {
                            queue += it
                            true
                        }
                    val dispatcher =
                        SlackInteractionDispatcher(
                            SlackEnvironment.PROD,
                            messageRouting,
                            relayAuthentication,
                            intake,
                            emptySet(),
                        ) { error("Nemá se přeposílat") }
                    slackWebhookRoutes(SlackSignatureVerifier(sharedSecret, routingClock), intake, dispatcher = dispatcher)
                }
                client.slackPost(routeValue(SlackEnvironment.PROD, Uuid.random().toString())).status shouldBe HttpStatusCode.OK
                queue.size shouldBe 0
                client.slackPost(routeValue(SlackEnvironment.PROD)).status shouldBe HttpStatusCode.OK
                queue.size shouldBe 1
                client.slackPost(routedMessage.reviewId.toString()).status shouldBe HttpStatusCode.OK
                queue.size shouldBe 2
            }
        }

        "selhání zápisu relay do fronty neblokuje opakované doručení od Slacku" {
            var attempts = 0
            testApplication {
                application {
                    installSerialization()
                    installErrorHandling()
                    val intake = SlackReplyIntake(RoutingMessageRepository()) { error("Nemá se zpracovat lokálně") }
                    val dispatcher =
                        SlackInteractionDispatcher(
                            SlackEnvironment.PROD,
                            messageRouting,
                            relayAuthentication,
                            intake,
                            setOf(SlackEnvironment.STAGING),
                        ) {
                            if (++attempts == 1) throw IllegalStateException("Database unavailable")
                            true
                        }
                    slackWebhookRoutes(SlackSignatureVerifier(sharedSecret, routingClock), intake, dispatcher = dispatcher)
                }
                client.slackPost(routeValue(SlackEnvironment.STAGING)).status shouldBe HttpStatusCode.InternalServerError
                client.slackPost(routeValue(SlackEnvironment.STAGING)).status shouldBe HttpStatusCode.OK
                attempts shouldBe 2
            }
        }

        "relay odmítne nesprávné prostředí, chybějící autentizaci i cizí recenzi" {
            val queue = mutableListOf<ReplyJobData>()
            testApplication {
                application {
                    val intake =
                        SlackReplyIntake(RoutingMessageRepository()) {
                            queue += it
                            true
                        }
                    slackRelayRoutes(
                        SlackInteractionDispatcher(
                            SlackEnvironment.STAGING,
                            messageRouting,
                            relayAuthentication,
                            intake,
                            emptySet(),
                        ) { error("No forwarding") },
                    )
                }
                client.post(SlackRelayAuthentication.PATH) { setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized

                fun data(
                    environment: SlackEnvironment,
                    review: String,
                ) = SlackRelayJobData(
                    environment.wireName,
                    "b".repeat(64),
                    Json.encodeToString(
                        SlackReplySubmission("C_SHARED", "123.456", "Thanks", "U1", "Tester", "T_SHARED", routeValue(environment, review)),
                    ),
                )
                client.relayPost(data(SlackEnvironment.PROD, routedMessage.reviewId.toString())).status shouldBe HttpStatusCode.Unauthorized
                client.relayPost(data(SlackEnvironment.STAGING, Uuid.random().toString())).status shouldBe
                    HttpStatusCode.UnprocessableEntity
                queue.size shouldBe 0
            }
        }
    })
