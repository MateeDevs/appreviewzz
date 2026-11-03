package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.slack.SlackOAuth
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication

private val states = SlackInstallStates(SecretPayload("pilot-test-signing-secret"))
private const val CALLBACK = "https://api.test/slack/oauth/callback"

private fun pilotOAuth(ok: Boolean = true) =
    SlackOAuth(
        HttpClient(
            MockEngine {
                respond(
                    if (ok) {
                        """{"ok":true,"access_token":"xoxb-secret","bot_user_id":"U1","team":{"id":"T1","name":"Studio"},"scope":"chat:write,channels:read"}"""
                    } else {
                        """{"ok":false,"error":"invalid_code"}"""
                    },
                    headers = headersOf("Content-Type", "application/json"),
                )
            },
        ),
        "client-id",
        SecretPayload("client-secret"),
    )

class SlackInstallRoutesTest :
    StringSpec({
        beforeTest { TestDatabase.reset() }

        "OAuth uloží instalaci a vrátí uživatele ke stejné aplikaci bez odhalení tokenu" {
            val database = TestDatabase.database.exposed
            val org = ExposedOrganizationRepository(database).create("Studio", "studio")
            val credentials = ExposedCredentialRepository(database)
            val store = SlackInstallStore(consoleVault(), credentials, ExposedAuditLogRepository(database))
            val state = states.issue(org.id.toString(), "/studio/aplikace/app-1?tab=klice")
            testApplication {
                application { slackInstallRoutes(pilotOAuth(), states, store, CALLBACK, consoleBaseUrl = "https://console.test") }
                val browser = createClient { followRedirects = false }
                val response = browser.get("/slack/oauth/callback?state=$state&code=valid")
                response.status shouldBe HttpStatusCode.Found
                val location = response.headers[HttpHeaders.Location].orEmpty()
                location shouldContain "https://console.test/studio/aplikace/app-1?tab=klice"
                location shouldContain "slack=connected"
                location shouldContain "slackCredential="
                location shouldContain "#kanaly"
                location shouldNotContain "xoxb"
                credentials.listByOrg(org.id, CredentialType.SLACK_INSTALL).size shouldBe 1
                browser.get("/slack/oauth/callback?state=$state&code=valid").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "zrušení i odmítnutí instalace vrátí uživatele do rozpracovaného onboardingu" {
            val database = TestDatabase.database.exposed
            val org = ExposedOrganizationRepository(database).create("Studio", "studio")
            val credentials = ExposedCredentialRepository(database)
            val store = SlackInstallStore(consoleVault(), credentials, ExposedAuditLogRepository(database))
            testApplication {
                application { slackInstallRoutes(pilotOAuth(false), states, store, CALLBACK) }
                val browser = createClient { followRedirects = false }
                val cancelled =
                    browser.get(
                        "/slack/oauth/callback?state=${states.issue(
                            org.id.toString(),
                            "/studio/onboarding?step=extras",
                        )}&error=access_denied",
                    )
                cancelled.headers[HttpHeaders.Location] shouldBe "/studio/onboarding?step=extras&slack=cancelled#kanaly"
                val failed =
                    browser.get(
                        "/slack/oauth/callback?state=${states.issue(org.id.toString(), "/studio/onboarding?step=extras")}&code=invalid",
                    )
                failed.headers[HttpHeaders.Location] shouldBe "/studio/onboarding?step=extras&slack=failed#kanaly"
                credentials.listByOrg(org.id, CredentialType.SLACK_INSTALL).size shouldBe 0
                browser.get("/slack/oauth/callback?state=forged&error=access_denied").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "přejmenovaný workspace aktualizuje původní instalaci a zachová napojené kanály" {
            val database = TestDatabase.database.exposed
            val org = ExposedOrganizationRepository(database).create("Studio", "studio")
            val credentials = ExposedCredentialRepository(database)
            val store = SlackInstallStore(consoleVault(), credentials, ExposedAuditLogRepository(database))
            val first =
                cz.matee.appreviewzz.channels.slack
                    .SlackInstall("xoxb-first", "T1", "Before")
            val id = store.save(org.id, first).id
            store.save(org.id, first.copy(botToken = "xoxb-second", teamName = "After")).id shouldBe id
            credentials.listByOrg(org.id, CredentialType.SLACK_INSTALL).size shouldBe 1
        }

        "CLI odkaz bez návratu stále zobrazí potvrzení instalace" {
            val database = TestDatabase.database.exposed
            val org = ExposedOrganizationRepository(database).create("Studio", "studio")
            val store = SlackInstallStore(consoleVault(), ExposedCredentialRepository(database), ExposedAuditLogRepository(database))
            testApplication {
                application { slackInstallRoutes(pilotOAuth(), states, store, CALLBACK) }
                val response = client.get("/slack/oauth/callback?state=${states.issue(org.id.toString())}&code=valid")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Studio"
                response.bodyAsText() shouldNotContain "xoxb-secret"
            }
        }
    })
