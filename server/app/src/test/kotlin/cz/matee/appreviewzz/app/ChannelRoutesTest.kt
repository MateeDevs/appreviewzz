package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.channels.slack.SlackApi
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.uuid.Uuid

private const val OWNER = "vlastnik@example.com"
private const val SLUG = "matee"
private const val CHANNEL_ID = "C0123456789"

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/** Zakladatel s organizací a appkou — každý test tady od toho začíná. */
private suspend fun ApplicationTestBuilder.ownerWithApp(mailer: RecordingMailer): Pair<HttpClient, String> {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    val app =
        owner
            .postJson("/api/orgs/$SLUG/apps", """{"name":"Testovací appka","gpPackageName":"cz.matee.test"}""")
            .bodyAsText()
            .jsonValue("id")
    return owner to app
}

/** Instalace Slacku, jaká vznikne připojením workspace — tady rovnou přes vault. */
private fun installSlack(orgSlug: String): String {
    val organizations =
        cz.matee.appreviewzz.persistence.repository
            .ExposedOrganizationRepository(TestDatabase.database.exposed)
    val orgId: OrganizationId = checkNotNull(organizations.findBySlug(orgSlug)).id
    return consoleVault()
        .store(orgId, CredentialType.SLACK_INSTALL, "Slack Matee", SecretPayload("""{"botToken":"xoxb-test"}"""), "Matee")
        .id
        .toString()
}

/** Slack API, které neopouští proces. Odpověď je zkrácená na to, co `auth.test` doopravdy vrací. */
private fun fakeSlackApi(
    ok: Boolean = true,
    scopes: String = "chat:write,chat:write.public,channels:read",
): SlackApi {
    val engine =
        MockEngine { _ ->
            respond(
                content =
                    if (ok) {
                        """{"ok":true,"team_id":"T0123","team":"Matee","user_id":"U0BOT"}"""
                    } else {
                        """{"ok":false,"error":"invalid_auth"}"""
                    },
                headers = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()), "x-oauth-scopes" to listOf(scopes)),
            )
        }
    return SlackApi(
        io.ktor.client.HttpClient(engine) {
            install(ContentNegotiation) { json() }
        },
    )
}

private fun consoleSlack(api: SlackApi = fakeSlackApi()): ConsoleSlack =
    ConsoleSlack(
        api = api,
        vault = consoleVault(),
        audit = ExposedAuditLogRepository(TestDatabase.database.exposed),
        installStates = null,
        publicBaseUrl = null,
    )

/**
 * Kanály a připojení Slacku. Nejdůležitější je tu `channels/test`: to je jediné místo,
 * kde se odvolaný token nebo nepozvaný bot pozná dřív než první nedoručenou recenzí.
 */
class ChannelRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer
        lateinit var fakes: ConsoleFakes

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
            fakes = ConsoleFakes(FakeReviewSource(Platform.ANDROID), FakeReviewSource(Platform.IOS), FakeNotificationChannel())
        }

        "kanál se připojí k appce a zdědí její jazyk" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)

                val created =
                    owner.postJson(
                        "/api/orgs/$SLUG/apps/$appId/channels",
                        """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                    )
                created.status shouldBe HttpStatusCode.Created
                created.bodyAsText() shouldContain "\"locale\":\"CS\""
                created.bodyAsText() shouldContain "\"enabled\":true"

                owner.get("/api/orgs/$SLUG/apps/$appId/channels").bodyAsText() shouldContain CHANNEL_ID
            }
        }

        "jméno kanálu místo ID se odmítne s vysvětlením" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)

                val response =
                    owner.postJson(
                        "/api/orgs/$SLUG/apps/$appId/channels",
                        """{"targetRef":"#recenze","credentialId":"$credentialId"}""",
                    )
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "View channel details"
            }
        }

        "zkušební zpráva projde a nese jméno aplikace" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)
                owner.postJson(
                    "/api/orgs/$SLUG/apps/$appId/channels",
                    """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                )

                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/channels/test", "{}")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"ok\":true"
                fakes.slack.notices
                    .single()
                    .appName shouldBe "Testovací appka"
            }
        }

        "nepozvaný bot se pozná při ověření, ne až první recenzí" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)
                owner.postJson(
                    "/api/orgs/$SLUG/apps/$appId/channels",
                    """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                )
                fakes.slack.failWith = ChannelException(ChannelErrorKind.NOT_FOUND, "channel_not_found")

                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/channels/test", "{}")
                // Chyba kanálu není chyba requestu: klient má vidět stav všech kanálů najednou.
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"ok\":false"
                body shouldContain "/invite @appreviewzz"
            }
        }

        "kanál se dá vypnout a smazat" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)
                val channel =
                    owner
                        .postJson(
                            "/api/orgs/$SLUG/apps/$appId/channels",
                            """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                        ).bodyAsText()
                        .jsonValue("id")

                owner.patchJson("/api/orgs/$SLUG/apps/$appId/channels/$channel", """{"enabled":false}""").status shouldBe
                    HttpStatusCode.NoContent
                owner.get("/api/orgs/$SLUG/apps/$appId/channels").bodyAsText() shouldContain "\"enabled\":false"

                owner.deleteSigned("/api/orgs/$SLUG/apps/$appId/channels/$channel").status shouldBe HttpStatusCode.NoContent
                owner.get("/api/orgs/$SLUG/apps/$appId/channels").bodyAsText() shouldBe "[]"
            }
        }

        "klíč, který drží kanál, nejde smazat" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)
                owner.postJson(
                    "/api/orgs/$SLUG/apps/$appId/channels",
                    """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                )

                val response = owner.deleteSigned("/api/orgs/$SLUG/credentials/$credentialId")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "nejdřív je odpoj"
            }
        }

        "ověření bez jediného kanálu řekne, že chybí" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/channels/test", "{}")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "zatím nemá kanál"
            }
        }

        "připojení workspace tokenem uloží instalaci a token ven nevrátí" {
            testApplication {
                consoleModule(mailer, slack = consoleSlack(), fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/slack/connect", """{"token":"xoxb-opravdovy-token"}""")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "\"workspace\":\"Matee (T0123)\""
                body shouldContain "\"missingScopes\":[]"
                body shouldNotContain "xoxb-opravdovy-token"

                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "SLACK_INSTALL"
            }
        }

        "chybějící scope je vidět hned při připojení" {
            testApplication {
                consoleModule(mailer, slack = consoleSlack(fakeSlackApi(scopes = "channels:read")), fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                owner
                    .postJson("/api/orgs/$SLUG/slack/connect", """{"token":"xoxb-bez-scope"}""")
                    .bodyAsText() shouldContain "\"missingScopes\":[\"chat:write\"]"
            }
        }

        "user token místo bot tokenu se odmítne dřív, než se na něj Slack zeptá" {
            testApplication {
                consoleModule(mailer, slack = consoleSlack(), fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/slack/connect", """{"token":"xoxp-user-token"}""")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "xoxb-"
            }
        }

        "odvolaný token Slack odmítne a my to řekneme větou" {
            testApplication {
                consoleModule(mailer, slack = consoleSlack(fakeSlackApi(ok = false)), fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/slack/connect", """{"token":"xoxb-odvolany"}""")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "invalid_auth"
            }
        }

        "instalační odkaz bez nastavené Slack Appky nabídne ruční token" {
            testApplication {
                consoleModule(mailer, slack = consoleSlack(), fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val response = owner.get("/api/orgs/$SLUG/slack/install-url")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "vlož bot token ručně"
            }
        }

        "kanál cizí organizace není vidět ani přes přímé ID" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = installSlack(SLUG)
                val channel =
                    owner
                        .postJson(
                            "/api/orgs/$SLUG/apps/$appId/channels",
                            """{"targetRef":"$CHANNEL_ID","credentialId":"$credentialId"}""",
                        ).bodyAsText()
                        .jsonValue("id")

                val outsider = browser()
                outsider.signUpVerified("cizi@example.com", mailer)
                outsider.postJson("/api/orgs", """{"name":"Cizí"}""")
                val foreignApp =
                    outsider
                        .postJson("/api/orgs/cizi/apps", """{"name":"Cizí appka","gpPackageName":"cz.cizi"}""")
                        .bodyAsText()
                        .jsonValue("id")

                outsider.get("/api/orgs/$SLUG/apps/$appId/channels").status shouldBe HttpStatusCode.NotFound
                outsider
                    .deleteSigned("/api/orgs/cizi/apps/$foreignApp/channels/$channel")
                    .status shouldBe HttpStatusCode.NotFound
                // A kanál pořád stojí.
                owner.get("/api/orgs/$SLUG/apps/$appId/channels").bodyAsText() shouldContain channel
            }
        }

        "neexistující UUID v adrese je 404, ne pětistovka" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)

                owner.get("/api/orgs/$SLUG/apps/necoJinehoNezUuid/channels").status shouldBe HttpStatusCode.NotFound
                owner
                    .deleteSigned("/api/orgs/$SLUG/apps/$appId/channels/${Uuid.random()}")
                    .status shouldBe HttpStatusCode.NotFound
            }
        }
    })
