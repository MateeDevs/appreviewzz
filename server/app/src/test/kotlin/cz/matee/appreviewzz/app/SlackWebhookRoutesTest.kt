package cz.matee.appreviewzz.app

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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val SIGNING_SECRET = "8f742231b10e8888abcd99yyyzzz85a5"
private val NOW = Instant.parse("2026-08-19T12:00:00Z")
private val ORG = OrganizationId(Uuid.random())
private val REVIEW = ReviewId(Uuid.random())
private val CHANNEL = ChannelId(Uuid.random())

private fun payload(
    text: String = "Díky za zpětnou vazbu, opravujeme to.",
    ts: String = "1755600000.000100",
): String =
    """
    {
      "type": "block_actions",
      "user": { "id": "U0123", "name": "tadeas" },
      "team": { "id": "T0123" },
      "container": { "message_ts": "$ts", "channel_id": "C0123" },
      "actions": [ { "action_id": "submit_reply" } ],
      "state": { "values": { "appreviewzz_reply": { "reply_text": { "value": "$text" } } } }
    }
    """.trimIndent()

private fun body(payload: String): String = "payload=" + URLEncoder.encode(payload, Charsets.UTF_8)

private fun sign(
    body: String,
    timestamp: Long,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SIGNING_SECRET.toByteArray(), "HmacSHA256"))
    return "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Zná jednu doručenou zprávu — přesně tu, kterou Slack v testu posílá zpátky. */
private class SingleMessageRepository : ReviewMessageRepository {
    override fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage? =
        if (channelType == ChannelType.SLACK && conversationId == "C0123" && messageId == "1755600000.000100") {
            ReviewMessage(
                id = ReviewMessageId(Uuid.random()),
                orgId = ORG,
                reviewId = REVIEW,
                channelId = CHANNEL,
                providerConversationId = conversationId,
                providerMessageId = messageId,
                status = MessageStatus.SENT,
                error = null,
                sentAt = NOW,
                contentHash = "hash",
                createdAt = NOW,
            )
        } else {
            null
        }

    override fun claim(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
        contentHash: String,
    ): ReviewMessage = error("nepoužívá se")

    override fun markSent(
        orgId: OrganizationId,
        id: ReviewMessageId,
        conversationId: String?,
        messageId: String?,
        sentAt: Instant,
    ): Boolean = error("nepoužívá se")

    override fun markFailed(
        orgId: OrganizationId,
        id: ReviewMessageId,
        error: String,
    ): Boolean = error("nepoužívá se")

    override fun findLatestSent(
        orgId: OrganizationId,
        reviewId: ReviewId,
        channelId: ChannelId,
    ): ReviewMessage? = error("nepoužívá se")

    override fun listByReview(
        orgId: OrganizationId,
        reviewId: ReviewId,
    ): List<ReviewMessage> = error("nepoužívá se")
}

class SlackWebhookRoutesTest :
    StringSpec({
        fun testApp(queued: MutableList<ReplyJobData>) =
            fun io.ktor.server.application.Application.() {
                installSerialization()
                installErrorHandling()
                installObservability(PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                slackWebhookRoutes(
                    verifier =
                        SlackSignatureVerifier(
                            signingSecret = SecretPayload(SIGNING_SECRET),
                            clock =
                                object : Clock {
                                    override fun now(): Instant = NOW
                                },
                        ),
                    intake =
                        SlackReplyIntake(SingleMessageRepository()) { data ->
                            queued += data
                            true
                        },
                )
            }

        "podepsané kliknutí na Odeslat zařadí publikaci a hned potvrdí" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload())

                val response =
                    client.post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, sign(raw, NOW.epochSeconds))
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody(raw)
                    }

                response.status shouldBe HttpStatusCode.OK
                queued shouldHaveSize 1
                val job = queued.single()
                job.orgId shouldBe ORG.toString()
                job.reviewId shouldBe REVIEW.toString()
                job.channelId shouldBe CHANNEL.toString()
                job.body shouldBe "Díky za zpětnou vazbu, opravujeme to."
                job.authorExternalId shouldBe "U0123"
                job.source shouldBe "SLACK"
            }
        }

        "víceřádková odpověď dorazí i s mezerami a zalomením" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                // Formulářové kódování dělá z mezer plusy; bez správného dekódování by odpověď
                // do storu odešla jako „Díky+za+zprávu".
                val raw = body(payload(text = "Díky za zprávu.\n\nOpravu vydáme příští týden."))

                client
                    .post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, sign(raw, NOW.epochSeconds))
                        setBody(raw)
                    }.status shouldBe HttpStatusCode.OK

                queued.single().body shouldBe "Díky za zprávu.\n\nOpravu vydáme příští týden."
            }
        }

        "nepodepsaný požadavek se odmítne a nic nezařadí" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload())

                val response =
                    client.post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, "v0=" + "0".repeat(64))
                        setBody(raw)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                queued.shouldHaveSize(0)
            }
        }

        "přehraný požadavek s platným podpisem se odmítne kvůli stáří" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload())
                val old = NOW.epochSeconds - 3_600

                val response =
                    client.post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, old.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, sign(raw, old))
                        setBody(raw)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                queued.shouldHaveSize(0)
            }
        }

        "zachycený požadavek poslaný podruhé se už nezpracuje" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload())
                val signature = sign(raw, NOW.epochSeconds)

                // Bajt po bajtu tentýž požadavek: podpis je pořád platný i v čase, takže sám
                // o sobě přehrávku nezachytí.
                repeat(2) {
                    client
                        .post(SLACK_INTERACTIVITY_PATH) {
                            header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                            header(SlackSignatureVerifier.SIGNATURE_HEADER, signature)
                            setBody(raw)
                        }.status shouldBe HttpStatusCode.OK
                }

                queued shouldHaveSize 1
            }
        }

        "neznámá zpráva dostane 200, ale nic se nepublikuje" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload(ts = "1755600000.999999"))

                val response =
                    client.post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, sign(raw, NOW.epochSeconds))
                        setBody(raw)
                    }

                response.status shouldBe HttpStatusCode.OK
                queued.shouldHaveSize(0)
            }
        }

        "prázdný vstup se nepublikuje" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))
                val raw = body(payload(text = "   "))

                client
                    .post(SLACK_INTERACTIVITY_PATH) {
                        header(SlackSignatureVerifier.TIMESTAMP_HEADER, NOW.epochSeconds.toString())
                        header(SlackSignatureVerifier.SIGNATURE_HEADER, sign(raw, NOW.epochSeconds))
                        setBody(raw)
                    }.status shouldBe HttpStatusCode.OK
                queued.shouldHaveSize(0)
            }
        }
    })
