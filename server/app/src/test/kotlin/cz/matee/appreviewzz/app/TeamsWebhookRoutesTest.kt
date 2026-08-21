package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.teams.BotFrameworkAuthenticator
import cz.matee.appreviewzz.channels.teams.TeamsBotIdentity
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val BOT_APP_ID = "7070c53f-17ad-473a-b48f-8a21ed6cd339"
private const val SERVICE_URL = "https://smba.trafficmanager.net/emea"
private const val CONVERSATION = "19:abc@thread.tacv2;messageid=1"
private const val ACTIVITY = "1755600000000"
private const val KEY_ID = "test-key"

private val NOW = Instant.parse("2026-08-21T10:00:00Z")
private val ORG = OrganizationId(Uuid.random())
private val REVIEW = ReviewId(Uuid.random())
private val CHANNEL = ChannelId(Uuid.random())

private val fixedClock =
    object : Clock {
        override fun now(): Instant = NOW
    }

private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.base64Url(): String = toByteArray(Charsets.UTF_8).base64Url()

/** BigInteger.toByteArray() přidává vedoucí nulu kvůli znaménku; JWK ji nemá. */
private fun ByteArray.dropLeadingZero(): ByteArray = if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this

private fun token(audience: String = BOT_APP_ID): String {
    val header = """{"alg":"RS256","kid":"$KEY_ID","typ":"JWT"}""".base64Url()
    val claims =
        """
        {"iss":"${BotFrameworkAuthenticator.BOT_CONNECTOR_ISSUER}","aud":"$audience","serviceurl":"$SERVICE_URL",
        "nbf":${NOW.epochSeconds - 60},"exp":${NOW.epochSeconds + 3600}}
        """.trimIndent().replace("\n", "").base64Url()
    val signed = "$header.$claims"
    val signature =
        Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(signed.toByteArray(Charsets.US_ASCII))
            sign()
        }
    return "$signed.${signature.base64Url()}"
}

/** Metadata i JWKS Bot Connectoru; v testu je servíruje mock engine místo Microsoftu. */
private fun botConnectorClient(): HttpClient {
    val public = keyPair.public as RSAPublicKey
    val jwks =
        """
        { "keys": [ { "kty": "RSA", "kid": "$KEY_ID",
          "n": "${public.modulus.toByteArray().dropLeadingZero().base64Url()}",
          "e": "${public.publicExponent.toByteArray().dropLeadingZero().base64Url()}",
          "endorsements": ["msteams"] } ] }
        """.trimIndent()
    return HttpClient(
        MockEngine { request ->
            val body =
                if (request.url.toString().contains("openidconfiguration")) {
                    """{"jwks_uri":"https://login.botframework.com/v1/keys","id_token_signing_alg_values_supported":["RS256"]}"""
                } else {
                    jwks
                }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
}

private fun activity(
    replyToId: String = ACTIVITY,
    text: String = "Díky za zpětnou vazbu, opravujeme to.",
    type: String = "message",
): String =
    """
    {
      "type": "$type",
      "channelId": "msteams",
      "serviceUrl": "$SERVICE_URL",
      "conversation": { "id": "$CONVERSATION" },
      "replyToId": "$replyToId",
      "from": { "id": "29:user", "name": "Tadeáš Sosín" },
      "channelData": { "tenant": { "id": "eeffd5e3-c44e-4862-aba6-a1bcd564c00c" } },
      "value": { "verb": "sendReply", "replyText": "$text" }
    }
    """.trimIndent()

/** Zná jednu doručenou kartu — přesně tu, kterou Teams v testu posílá zpátky. */
private class SingleTeamsMessageRepository : ReviewMessageRepository {
    override fun findByProviderMessage(
        channelType: ChannelType,
        conversationId: String,
        messageId: String,
    ): ReviewMessage? =
        if (channelType == ChannelType.TEAMS && conversationId == CONVERSATION && messageId == ACTIVITY) {
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

class TeamsWebhookRoutesTest :
    StringSpec({
        fun testApp(queued: MutableList<ReplyJobData>) =
            fun io.ktor.server.application.Application.() {
                installSerialization()
                installErrorHandling()
                installObservability(PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                teamsWebhookRoutes(
                    authenticator =
                        BotFrameworkAuthenticator(
                            httpClient = botConnectorClient(),
                            bot = TeamsBotIdentity(BOT_APP_ID, SecretPayload("heslo")),
                            clock = fixedClock,
                        ),
                    intake =
                        TeamsReplyIntake(SingleTeamsMessageRepository()) { data ->
                            queued += data
                            true
                        },
                )
            }

        suspend fun io.ktor.client.HttpClient.send(
            body: String,
            authorization: String? = "Bearer ${token()}",
        ) = post(TEAMS_MESSAGES_PATH) {
            authorization?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }

        "kliknutí na Odeslat zařadí publikaci a hned potvrdí" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                val response = client.send(activity())

                response.status shouldBe HttpStatusCode.OK
                queued shouldHaveSize 1
                val job = queued.single()
                job.orgId shouldBe ORG.toString()
                job.reviewId shouldBe REVIEW.toString()
                job.channelId shouldBe CHANNEL.toString()
                job.body shouldBe "Díky za zpětnou vazbu, opravujeme to."
                job.authorExternalId shouldBe "29:user"
                job.authorDisplayName shouldBe "Tadeáš Sosín"
                job.source shouldBe "TEAMS"
            }
        }

        "aktivita bez tokenu se odmítne a nic nezařadí" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send(activity(), authorization = null).status shouldBe HttpStatusCode.Forbidden
                queued.shouldHaveSize(0)
            }
        }

        "token vystavený pro jiného bota neprojde" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send(activity(), authorization = "Bearer ${token(audience = "cizi-bot")}").status shouldBe
                    HttpStatusCode.Forbidden
                queued.shouldHaveSize(0)
            }
        }

        "systémová aktivita se potvrdí a zahodí" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send(activity(type = "conversationUpdate")).status shouldBe HttpStatusCode.OK
                queued.shouldHaveSize(0)
            }
        }

        "neznámá karta dostane 200, ale nic se nepublikuje" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send(activity(replyToId = "9999999999")).status shouldBe HttpStatusCode.OK
                queued.shouldHaveSize(0)
            }
        }

        "prázdný vstup se nepublikuje" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send(activity(text = "   ")).status shouldBe HttpStatusCode.OK
                queued.shouldHaveSize(0)
            }
        }

        "nečitelné tělo je špatný požadavek, ne přijatá odpověď" {
            val queued = mutableListOf<ReplyJobData>()
            testApplication {
                application(testApp(queued))

                client.send("tohle není JSON").status shouldBe HttpStatusCode.BadRequest
                queued.shouldHaveSize(0)
            }
        }
    })
