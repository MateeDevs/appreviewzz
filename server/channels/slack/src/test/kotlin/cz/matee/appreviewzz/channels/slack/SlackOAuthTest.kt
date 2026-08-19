package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val NOW = Instant.parse("2026-08-19T12:00:00Z")
private const val CLIENT_ID = "1234567890.0987654321"
private val CLIENT_SECRET = SecretPayload("tajny-klic-appky")
private const val REDIRECT_URI = "https://api.appreviewzz.com/slack/oauth/callback"

private fun clockAt(instant: Instant): Clock =
    object : Clock {
        override fun now(): Instant = instant
    }

class SlackOAuthTest :
    FunSpec({
        test("autorizační odkaz nese jen scopes, které opravdu potřebujeme") {
            val oauth = SlackOAuth(RecordingEngine { respond("{}") }.client(), CLIENT_ID, CLIENT_SECRET)

            val url = oauth.authorizeUrl(state = "podepsany-state", redirectUri = REDIRECT_URI)

            url shouldContain "client_id=$CLIENT_ID"
            url shouldContain "state=podepsany-state"
            url shouldContain "chat%3Awrite"
            // Stav „odpovězeno" skládáme z dat, historii kanálu tedy číst nepotřebujeme.
            url shouldNotContain "channels%3Ahistory"
            url shouldNotContain "users%3Aread"
        }

        test("výměna kódu vrátí token workspace") {
            val engine =
                RecordingEngine {
                    respond(
                        """
                        {
                          "ok": true,
                          "access_token": "xoxb-workspace-token",
                          "token_type": "bot",
                          "scope": "chat:write,chat:write.public,channels:read",
                          "bot_user_id": "U0BOT",
                          "team": { "id": "T0123", "name": "IsleGrow" }
                        }
                        """.trimIndent(),
                        headers = jsonHeaders,
                    )
                }
            val oauth = SlackOAuth(engine.client(), CLIENT_ID, CLIENT_SECRET)

            val install = oauth.exchange("kod-ze-slacku", REDIRECT_URI)

            install.botToken shouldBe "xoxb-workspace-token"
            install.teamId shouldBe "T0123"
            install.teamName shouldBe "IsleGrow"
            install.hint() shouldBe "IsleGrow (T0123)"
            val body =
                String(
                    engine.requests
                        .single()
                        .body
                        .toByteArray(),
                )
            body shouldContain "code=kod-ze-slacku"
            body shouldContain "client_id=$CLIENT_ID"
        }

        test("odmítnutá instalace je chyba, která chce člověka") {
            val engine = RecordingEngine { respond("""{"ok":false,"error":"invalid_code"}""", headers = jsonHeaders) }
            val oauth = SlackOAuth(engine.client(), CLIENT_ID, CLIENT_SECRET)

            shouldThrow<ChannelException> {
                oauth.exchange("stary-kod", REDIRECT_URI)
            }.kind shouldBe ChannelErrorKind.AUTH
        }

        test("instalace se ukládá jako payload, ze kterého jde přečíst workspace i token") {
            val install = SlackInstall(botToken = "xoxb-1", teamId = "T1", teamName = "Matee", scopes = "chat:write")

            val parsed = SlackInstall.parse(install.payload())

            parsed shouldBe install
            // Payload je tajemství: v logu z něj nesmí být nic.
            install.payload().toString() shouldNotContain "xoxb-1"
        }

        test("ručně vložený token se ověří a doplní workspace i scopes") {
            val engine =
                RecordingEngine {
                    respond(
                        """{"ok":true,"url":"https://matee.slack.com/","team":"Matee","user":"appreviewzz",
                           "team_id":"T0123","user_id":"U0BOT"}""",
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "x-oauth-scopes" to listOf("chat:write,chat:write.public"),
                            ),
                    )
                }
            val api = SlackApi(engine.client())

            val install = api.authTest(SecretPayload("xoxb-rucne-vlozeny"))

            install.teamId shouldBe "T0123"
            install.teamName shouldBe "Matee"
            install.botUserId shouldBe "U0BOT"
            install.scopes shouldBe "chat:write,chat:write.public"
            engine.requests
                .single()
                .url
                .toString() shouldContain "/auth.test"
        }

        test("neplatný token se pozná při vkládání, ne až první recenzí") {
            val engine = RecordingEngine { respond("""{"ok":false,"error":"invalid_auth"}""", headers = jsonHeaders) }

            shouldThrow<ChannelException> {
                SlackApi(engine.client()).authTest(SecretPayload("xoxb-neplatny"))
            }.kind shouldBe ChannelErrorKind.AUTH
        }

        test("poškozený payload instalace chce novou instalaci, ne retry") {
            shouldThrow<ChannelException> {
                SlackInstall.parse(SecretPayload("tohle není JSON"))
            }.kind shouldBe ChannelErrorKind.AUTH
        }
    })

class SlackInstallStatesTest :
    FunSpec({
        val secret = SecretPayload("8f742231b10e8888abcd99yyyzzz85a5")

        test("vydaný state se dá ověřit a nese organizaci") {
            val states = SlackInstallStates(secret, clockAt(NOW))

            val state = states.issue("11111111-1111-1111-1111-111111111111")

            states.verify(state)?.orgId shouldBe "11111111-1111-1111-1111-111111111111"
        }

        test("podvržený state neprojde — jinak by šlo instalovat za cizí organizaci") {
            val states = SlackInstallStates(secret, clockAt(NOW))
            val foreign = SlackInstallStates(SecretPayload("cizi-secret"), clockAt(NOW))

            states.verify(foreign.issue("11111111-1111-1111-1111-111111111111")).shouldBeNull()
            states.verify("nesmysl").shouldBeNull()
            states.verify(null).shouldBeNull()
            states.verify(states.issue("org").dropLast(2)).shouldBeNull()
        }

        test("prošlý odkaz se nedá použít") {
            val issued = SlackInstallStates(secret, clockAt(NOW), validity = 30.minutes).issue("org-1")

            SlackInstallStates(secret, clockAt(NOW + 2.hours)).verify(issued).shouldBeNull()
            SlackInstallStates(secret, clockAt(NOW + 10.minutes)).verify(issued)?.orgId shouldBe "org-1"
        }
    })
