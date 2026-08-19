package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private val TOKEN = SecretPayload("xoxb-testovaci-token")

class SlackApiTest :
    FunSpec({
        test("odeslaná recenze vrátí kanál a ts, tělo nese bloky i metadata") {
            val engine =
                RecordingEngine {
                    respond("""{"ok":true,"channel":"C123","ts":"1755600000.000100"}""", headers = jsonHeaders)
                }
            val channel = SlackNotificationChannel(SlackApi(engine.client()))

            val posted =
                channel.postReview(
                    target = ChannelTarget(conversationId = "C123", credential = TOKEN),
                    notification = notification(),
                )

            posted shouldBe PostedMessage("C123", "1755600000.000100")
            val request = engine.requests.single()
            request.url.toString() shouldContain "/chat.postMessage"
            request.headers["Authorization"] shouldBe "Bearer ${TOKEN.value}"

            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body.text("channel") shouldBe "C123"
            // Fallback text nese notifikace na mobilu — bez něj je z upozornění prázdný řádek.
            body.text("text") shouldContain "Nová recenze aplikace IsleGrow!"
            body.blocks().render() shouldContain SlackBlocks.REPLY_BLOCK_ID
            body.at("metadata", "event_type")?.jsonPrimitive?.content shouldBe "appreviewzz_review"
            body.at("metadata", "event_payload", "review_id")?.jsonPrimitive?.content shouldBe
                "11111111-1111-1111-1111-111111111111"
            body.at("metadata", "event_payload", "platform")?.jsonPrimitive?.content shouldBe "ANDROID"
        }

        test("chyba v těle s HTTP 200 je chyba, ne úspěch") {
            val engine = RecordingEngine { respond("""{"ok":false,"error":"not_in_channel"}""", headers = jsonHeaders) }
            val channel = SlackNotificationChannel(SlackApi(engine.client()))

            val error =
                shouldThrow<ChannelException> {
                    channel.postReview(ChannelTarget("C123", TOKEN), notification())
                }

            error.kind shouldBe ChannelErrorKind.NOT_FOUND
            error.isRetryable shouldBe false
        }

        test("mapování slackových chyb na druh selhání") {
            val cases =
                listOf(
                    "invalid_auth" to ChannelErrorKind.AUTH,
                    "token_revoked" to ChannelErrorKind.AUTH,
                    "missing_scope" to ChannelErrorKind.AUTH,
                    "channel_not_found" to ChannelErrorKind.NOT_FOUND,
                    "is_archived" to ChannelErrorKind.NOT_FOUND,
                    "ratelimited" to ChannelErrorKind.RATE_LIMITED,
                    "service_unavailable" to ChannelErrorKind.TRANSIENT,
                    "invalid_blocks" to ChannelErrorKind.INVALID_REQUEST,
                    "msg_too_long" to ChannelErrorKind.INVALID_REQUEST,
                )

            cases.forEach { (slackError, expected) ->
                val engine = RecordingEngine { respond("""{"ok":false,"error":"$slackError"}""", headers = jsonHeaders) }
                val channel = SlackNotificationChannel(SlackApi(engine.client()))

                withClue(slackError) {
                    shouldThrow<ChannelException> {
                        channel.postReview(ChannelTarget("C123", TOKEN), notification())
                    }.kind shouldBe expected
                }
            }
        }

        test("429 je opakovatelné, 500 taky") {
            val limited = RecordingEngine { respondError(HttpStatusCode.TooManyRequests) }
            shouldThrow<ChannelException> {
                SlackNotificationChannel(SlackApi(limited.client())).postReview(ChannelTarget("C1", TOKEN), notification())
            }.isRetryable shouldBe true

            val broken = RecordingEngine { respondError(HttpStatusCode.BadGateway) }
            shouldThrow<ChannelException> {
                SlackNotificationChannel(SlackApi(broken.client())).postReview(ChannelTarget("C1", TOKEN), notification())
            }.isRetryable shouldBe true
        }

        test("označení odpovězeno přepíše původní zprávu podle ts") {
            val engine = RecordingEngine { respond("""{"ok":true,"channel":"C123","ts":"1.1"}""", headers = jsonHeaders) }
            val channel = SlackNotificationChannel(SlackApi(engine.client()))

            channel.markReplied(
                target = ChannelTarget("C123", TOKEN),
                message = PostedMessage("C123", "1755600000.000100"),
                rendering =
                    ReplyRendering(
                        notification = notification(),
                        replyText = "Díky, opravujeme.",
                        authorDisplayName = "Tadeáš",
                        repliedAt = Instant.parse("2026-08-19T15:00:00Z"),
                    ),
            )

            val request = engine.requests.single()
            request.url.toString() shouldContain "/chat.update"
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            body.text("ts") shouldBe "1755600000.000100"
            body.blocks().render() shouldContain "Díky, opravujeme."
        }

        test("selhání publikace jde do vlákna pod zprávou, ne do kanálu") {
            val engine = RecordingEngine { respond("""{"ok":true,"channel":"C123","ts":"2.2"}""", headers = jsonHeaders) }
            val channel = SlackNotificationChannel(SlackApi(engine.client()))

            channel.reportFailure(
                target = ChannelTarget("C123", TOKEN),
                message = PostedMessage("C123", "1755600000.000100"),
                notification = notification(),
                error = "Google Play API vrátilo 403",
            )

            val body =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .single()
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject
            body.text("thread_ts") shouldBe "1755600000.000100"
            body.blocks().render() shouldContain "Google Play API vrátilo 403"
        }
    })
