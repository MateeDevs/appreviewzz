package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private const val CONVERSATION_ID = "19:abcdef@thread.tacv2;messageid=1755600000000"
private const val ACTIVITY_ID = "1755600000000"

class TeamsNotificationChannelTest :
    FunSpec({
        fun channelOf(engine: RecordingEngine): TeamsNotificationChannel {
            val client = engine.client()
            return TeamsNotificationChannel(TeamsApi(client), TeamsTokens(client), BOT)
        }

        test("recenze zakládá v kanálu vlastní vlákno a vrací jeho ID") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.toString().contains("/oauth2/")) {
                        tokenResponse()
                    } else {
                        respondJson("""{"id":"$CONVERSATION_ID","activityId":"$ACTIVITY_ID"}""")
                    }
                }

            val posted =
                channelOf(engine).postReview(
                    target = ChannelTarget(conversationId = TEAMS_CHANNEL_ID, credential = INSTALL),
                    notification = notification(),
                )

            posted shouldBe PostedMessage(CONVERSATION_ID, ACTIVITY_ID)

            val request = engine.requests.last()
            request.method shouldBe HttpMethod.Post
            request.url.toString() shouldBe "$SERVICE_URL/v3/conversations"
            request.headers["Authorization"] shouldBe "Bearer bot-token"

            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body["isGroup"]?.jsonPrimitive?.content shouldBe "true"
            body.at("channelData", "tenant", "id")?.jsonPrimitive?.content shouldBe TENANT_ID
            body.at("channelData", "channel", "id")?.jsonPrimitive?.content shouldBe TEAMS_CHANNEL_ID
            body.at("activity", "attachments")?.toString() shouldContain TeamsApi.ADAPTIVE_CARD_CONTENT_TYPE
            // Karta nese vstup s návrhem od AI — bez něj by tým psal odpovědi od nuly.
            body.render() shouldContain TeamsCards.REPLY_INPUT_ID
            body.render() shouldContain "Mrzí nás to"
        }

        test("po odeslání odpovědi se karta přepíše, ne že by přibyla další zpráva") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.toString().contains("/oauth2/")) tokenResponse() else respondJson("{}")
                }

            channelOf(engine).markReplied(
                target = ChannelTarget(conversationId = TEAMS_CHANNEL_ID, credential = INSTALL),
                message = PostedMessage(CONVERSATION_ID, ACTIVITY_ID),
                rendering =
                    ReplyRendering(
                        notification = notification(),
                        replyText = "Díky za hlášku, opravíme to v příští verzi.",
                        authorDisplayName = "Tadeáš",
                        repliedAt = Instant.parse("2026-08-21T09:00:00Z"),
                    ),
            )

            val request = engine.requests.last()
            request.method shouldBe HttpMethod.Put
            request.url.toString() shouldBe "$SERVICE_URL/v3/conversations/$CONVERSATION_ID/activities/$ACTIVITY_ID"

            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body.render() shouldContain "Díky za hlášku"
            // Vstup ani tlačítko na přepsané kartě nezůstávají: zpráva je od téhle chvíle historie.
            body.render() shouldNotContain TeamsCards.REPLY_INPUT_ID
            body.render() shouldNotContain "Action.Submit"
        }

        test("chyba publikace jde do vlákna pod kartu, aby šlo zkusit znovu") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.toString().contains("/oauth2/")) tokenResponse() else respondJson("""{"id":"reply-1"}""")
                }

            channelOf(engine).reportFailure(
                target = ChannelTarget(conversationId = TEAMS_CHANNEL_ID, credential = INSTALL),
                message = PostedMessage(CONVERSATION_ID, ACTIVITY_ID),
                notification = notification(),
                error = "Google Play: 400 reply too long",
            )

            val request = engine.requests.last()
            request.url.toString() shouldBe "$SERVICE_URL/v3/conversations/$CONVERSATION_ID/activities"
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body.text("replyToId") shouldBe ACTIVITY_ID
            body.render() shouldContain "reply too long"
        }

        test("ověření kanálu pošle krátké potvrzení bez formuláře") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.toString().contains("/oauth2/")) {
                        tokenResponse()
                    } else {
                        respondJson("""{"id":"$CONVERSATION_ID","activityId":"$ACTIVITY_ID"}""")
                    }
                }

            channelOf(engine).postConnectivityCheck(
                target = ChannelTarget(conversationId = TEAMS_CHANNEL_ID, credential = INSTALL),
                notice = ConnectivityNotice(appName = "IsleGrow", locale = MessageLocale.CS),
            )

            val body =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .last()
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject
            body.render() shouldContain "Kanál je připojený"
            body.render() shouldNotContain TeamsCards.REPLY_INPUT_ID
        }

        test("odvolaný secret je chyba pro člověka, limit se zkusí znovu") {
            suspend fun postWith(status: HttpStatusCode): ChannelException {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.toString().contains("/oauth2/")) {
                            tokenResponse()
                        } else {
                            respondError(status, """{"error":{"message":"nope"}}""", headers = jsonHeaders)
                        }
                    }
                return shouldThrow {
                    channelOf(engine).postReview(
                        ChannelTarget(TEAMS_CHANNEL_ID, INSTALL),
                        notification(),
                    )
                }
            }

            postWith(HttpStatusCode.Forbidden).kind shouldBe ChannelErrorKind.AUTH
            postWith(HttpStatusCode.NotFound).kind shouldBe ChannelErrorKind.NOT_FOUND
            postWith(HttpStatusCode.TooManyRequests).kind shouldBe ChannelErrorKind.RATE_LIMITED
            postWith(HttpStatusCode.BadGateway).kind shouldBe ChannelErrorKind.TRANSIENT
            postWith(HttpStatusCode.BadRequest).kind shouldBe ChannelErrorKind.INVALID_REQUEST
        }
    })
