package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.AnalysisAlertMessage
import cz.matee.appreviewzz.core.message.AnalysisDigest
import cz.matee.appreviewzz.core.message.PlatformRatings
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.AnalysisAlert
import cz.matee.appreviewzz.core.model.AnalysisAlertId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.usecase.AnalysisAggregates
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val routingSecret = SecretPayload("shared-slack-app-secret")
private val routing = SlackMessageRouting(routingSecret)
private val target = ChannelTarget("C123", SlackInstall("xoxb-test", "T1").payload())

class SlackRoutingTest :
    FunSpec({
        test("prostředí i identita recenze jsou podepsané a nelze je zaměnit") {
            val review = review()
            val value = routing.issue(SlackEnvironment.STAGING, review.orgId.toString(), review.id.toString())
            routing.verify(value) shouldBe SlackMessageRoute(SlackEnvironment.STAGING, review.orgId.toString(), review.id.toString())
            routing.verify(value.replace("staging", "prod")).shouldBeNull()
            SlackMessageRouting(SecretPayload("other-secret")).verify(value).shouldBeNull()
            routing.verify("appreviewz:v2:bad").shouldBeNull()
        }

        test("každý druh staging zprávy včetně úpravy a chyby končí oddělovačem a upozorněním ALPHA") {
            val engine = RecordingEngine { respond("""{"ok":true,"channel":"C123","ts":"1.1"}""", headers = jsonHeaders) }
            val channel = SlackNotificationChannel(SlackApi(engine.client()), SlackEnvironment.STAGING, routing)
            val notification = notification()
            val message = PostedMessage("C123", "1.1")
            channel.postReview(target, notification)
            channel.markReplied(target, message, ReplyRendering(notification, "Díky", "Tester", SUBMITTED))
            channel.reportFailure(target, message, notification, "Store unavailable")
            channel.postConnectivityCheck(target, ConnectivityNotice("Test app", MessageLocale.CS))
            val day = LocalDate(2026, 11, 2)
            channel.postRatingsDigest(
                target,
                RatingsDigest(
                    "Test app",
                    MessageLocale.CS,
                    "UTC",
                    day,
                    listOf(PlatformRatings(Platform.IOS, 4.5, 10, null, null, emptyMap(), day, null)),
                ),
            )
            val aggregates =
                AnalysisAggregates.of(
                    day,
                    day,
                    AnalysisPeriod.EMPTY,
                    AnalysisPeriod.EMPTY,
                    ReplyStats(0, 0, null),
                    null,
                    MessageLocale.CS,
                )
            channel.postAnalysisDigest(target, AnalysisDigest("Test app", MessageLocale.CS, aggregates))
            val review = review()
            val alert =
                AnalysisAlert(
                    AnalysisAlertId(Uuid.random()),
                    review.orgId,
                    review.appId,
                    AlertKind.NEGATIVE_SPIKE,
                    null,
                    day,
                    10,
                    1.0,
                    4.0,
                    SUBMITTED,
                )
            channel.postAnalysisAlert(target, AnalysisAlertMessage("Test app", MessageLocale.CS, alert, null))
            engine.requests.size shouldBe 7
            engine.requests.forEach { request ->
                val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                val blocks = body.blocks()
                blocks[blocks.lastIndex - 1].jsonObject["type"]?.jsonPrimitive?.content shouldBe "divider"
                blocks.last().toString() shouldContain "ALPHA"
                body.text("text") shouldContain "—\n⚠️ ALPHA"
            }
            val first =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .first()
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject
            first.blocks().render() shouldContain "appreviewz:v1:staging:"
        }

        test("dlouhá recenze neodřízne upozornění ALPHA z mobilní notifikace") {
            val engine = RecordingEngine { respond("""{"ok":true,"channel":"C123","ts":"1.1"}""", headers = jsonHeaders) }
            SlackNotificationChannel(SlackApi(engine.client()), SlackEnvironment.STAGING, routing)
                .postReview(target, notification(review(body = "x".repeat(10_000))))
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
            body.text("text").orEmpty().length shouldBe 3_000
            body.text("text").orEmpty().endsWith("⚠️ ALPHA · Zpráva z testovacího prostředí staging.") shouldBe true
        }

        test("produkční zpráva nemá ALPHA, ale obsahuje směrování prod") {
            val engine = RecordingEngine { respond("""{"ok":true,"channel":"C123","ts":"1.1"}""", headers = jsonHeaders) }
            SlackNotificationChannel(SlackApi(engine.client()), SlackEnvironment.PROD, routing).postReview(target, notification())
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
            body.toString() shouldNotContain "ALPHA"
            body.blocks().render() shouldContain "appreviewz:v1:prod:"
        }

        test("OAuth state nelze použít v druhém prostředí se stejným signing secretem") {
            val staging = SlackInstallStates(routingSecret, environment = "staging")
            val production = SlackInstallStates(routingSecret, environment = "prod")
            val state = staging.issue("org", "/studio/onboarding?step=extras")
            production.verify(state).shouldBeNull()
            staging.verify(state)?.environment shouldBe "staging"
        }

        test("relay podepisuje každý pokus čerstvě, váže prostředí a nepropaguje přesměrování") {
            var now = SUBMITTED
            val auth =
                SlackRelayAuthentication(
                    routingSecret,
                    object : Clock {
                        override fun now(): Instant = now
                    },
                )
            var attempts = 0
            val engine =
                RecordingEngine { request ->
                    val timestamp = request.headers[SlackRelayAuthentication.TIMESTAMP]
                    auth.verify(
                        SlackEnvironment.STAGING,
                        request.headers[SlackRelayAuthentication.DELIVERY_ID],
                        timestamp,
                        "{}",
                        request.headers[SlackRelayAuthentication.SIGNATURE],
                    ) shouldBe true
                    auth.verify(
                        SlackEnvironment.PROD,
                        request.headers[SlackRelayAuthentication.DELIVERY_ID],
                        timestamp,
                        "{}",
                        request.headers[SlackRelayAuthentication.SIGNATURE],
                    ) shouldBe false
                    if (++attempts == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond("", HttpStatusCode.OK)
                }
            val client = SlackRelayClient(engine.client(), auth, mapOf(SlackEnvironment.STAGING to "https://staging.test"))
            shouldThrow<ChannelException> { client.forward(SlackEnvironment.STAGING, "a".repeat(64), "{}") }.isRetryable shouldBe true
            now += 10.minutes
            client.forward(SlackEnvironment.STAGING, "a".repeat(64), "{}")
            attempts shouldBe 2
            val redirect = RecordingEngine { respond("", HttpStatusCode.Found, headersOf("Location", "https://untrusted.test")) }
            shouldThrow<ChannelException> {
                SlackRelayClient(redirect.client(), auth, mapOf(SlackEnvironment.STAGING to "https://staging.test"))
                    .forward(SlackEnvironment.STAGING, "a".repeat(64), "{}")
            }.isRetryable shouldBe false
            redirect.requests.size shouldBe 1
        }
    })
