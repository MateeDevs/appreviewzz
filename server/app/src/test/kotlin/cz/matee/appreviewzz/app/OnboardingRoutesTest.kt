package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.StoreKeyFixtures
import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.OnboardingSync
import cz.matee.appreviewzz.persistence.repository.ExposedOnboardingRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun String.id(): String = Regex(""""id":"([^"]+)"""").find(this)!!.groupValues[1]

private const val PATH = "/api/orgs/studio/onboarding"

private suspend fun ApplicationTestBuilder.founder(mailer: RecordingMailer): HttpClient =
    browser().also {
        it.signUpVerified("founder@example.test", mailer)
        it.postJson("/api/orgs", """{"name":"Studio"}""")
    }

class OnboardingRoutesTest :
    StringSpec({
        lateinit var mailer: RecordingMailer
        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "průchod přežije novou session a nemění nastavení aplikace" {
            testApplication {
                consoleModule(mailer)
                val owner = founder(mailer)
                val app =
                    owner
                        .postJson(
                            "/api/orgs/studio/apps",
                            """{"name":"Atlas","gpPackageName":"cz.example.atlas","aiInstructions":"Původní instrukce"}""",
                        ).bodyAsText()
                        .id()
                owner.putJson(PATH, """{"appId":"$app","step":"extras","preferencesConfirmed":true}""").status shouldBe HttpStatusCode.OK
                val again = browser()
                again.postJson("/api/auth/login", """{"email":"founder@example.test","password":"dostatecne-dlouhe-heslo"}""")
                again.get(PATH).bodyAsText() shouldContain "\"step\":\"extras\""
                again.get("/api/orgs/studio/apps").bodyAsText() shouldContain "Původní instrukce"
            }
        }

        "dokončení vyžaduje vlastní aplikaci s ověřeným storem a potvrzené preference, kanál je volitelný" {
            testApplication {
                consoleModule(mailer)
                val owner = founder(mailer)
                val app =
                    owner
                        .postJson(
                            "/api/orgs/studio/apps",
                            """{"name":"Atlas","gpPackageName":"cz.example.atlas"}""",
                        ).bodyAsText()
                        .id()
                val progress = """{"appId":"$app","step":"sync","preferencesConfirmed":true,"completed":true}"""
                owner.putJson(PATH, progress).status shouldBe HttpStatusCode.BadRequest
                val content = StoreKeyFixtures.serviceAccountFile(Files.createTempDirectory("onboarding-key")).readText()
                val encoded = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(content))
                val key =
                    owner
                        .postJson(
                            "/api/orgs/studio/credentials",
                            """{"type":"gp","label":"Test","content":$encoded}""",
                        ).bodyAsText()
                        .id()
                owner.postJson("/api/orgs/studio/apps/$app/credentials", """{"credentialId":"$key"}""")
                owner.putJson(PATH, progress).status shouldBe HttpStatusCode.BadRequest
                owner.postJson("/api/orgs/studio/apps/$app/credentials/$key/validate", "{}")
                owner.putJson(PATH, progress.replace("true,\"completed", "false,\"completed")).status shouldBe HttpStatusCode.BadRequest
                owner.putJson(PATH, progress).status shouldBe HttpStatusCode.OK
                owner.get(PATH).bodyAsText() shouldContain "\"completed\":true"
                owner.get("/api/orgs/studio/apps/$app/channels").bodyAsText() shouldBe "[]"
                val sync = owner.postJson("$PATH/$app/sync", "{}")
                sync.status shouldBe HttpStatusCode.OK
                sync.bodyAsText() shouldContain "\"status\":\"SUCCEEDED\""
                sync.bodyAsText() shouldContain "\"fetched\":0"
                owner.get("$PATH/$app/sync").bodyAsText() shouldContain "\"fetched\":0"
            }
        }

        "ověření sdíleného klíče patří konkrétní aplikaci" {
            testApplication {
                val fakes = ConsoleFakes(FakeReviewSource(Platform.ANDROID), FakeReviewSource(Platform.IOS), FakeNotificationChannel())
                consoleModule(mailer, fakes = fakes)
                val owner = founder(mailer)
                val first =
                    owner
                        .postJson(
                            "/api/orgs/studio/apps",
                            """{"name":"První","gpPackageName":"cz.example.first"}""",
                        ).bodyAsText()
                        .id()
                val second =
                    owner
                        .postJson(
                            "/api/orgs/studio/apps",
                            """{"name":"Druhá","gpPackageName":"cz.example.second"}""",
                        ).bodyAsText()
                        .id()
                val content = StoreKeyFixtures.serviceAccountFile(Files.createTempDirectory("onboarding-shared-key")).readText()
                val encoded = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(content))
                val key =
                    owner
                        .postJson(
                            "/api/orgs/studio/credentials",
                            """{"type":"gp","label":"Sdílený","content":$encoded}""",
                        ).bodyAsText()
                        .id()
                owner.postJson("/api/orgs/studio/apps/$first/credentials", """{"credentialId":"$key"}""")
                owner.postJson("/api/orgs/studio/apps/$first/credentials/$key/validate", "{}")
                owner.postJson("/api/orgs/studio/apps/$second/credentials", """{"credentialId":"$key"}""")
                owner.get("/api/orgs/studio/apps/$second").bodyAsText() shouldContain "\"platformsWaitingForKey\":[\"ANDROID\"]"
                fakes.googlePlay.outcome =
                    cz.matee.appreviewzz.core.port
                        .ValidationOutcome(false, "K druhé aplikaci nemá přístup")
                owner.postJson("/api/orgs/studio/apps/$second/credentials/$key/validate", "{}")
                owner.get("/api/orgs/studio/apps/$first").bodyAsText() shouldContain "\"platformsWaitingForKey\":[]"
                owner.get("/api/orgs/studio/apps/$second").bodyAsText() shouldContain "\"platformsWaitingForKey\":[\"ANDROID\"]"
            }
        }

        "konkurence, cizí tenant, neplatné ID a člen neobejdou pravidla" {
            testApplication {
                consoleModule(mailer)
                val owner = founder(mailer)
                val competitor =
                    owner
                        .postJson(
                            "/api/orgs/studio/apps",
                            """{"name":"Konkurent","ascAppId":"123456789","competitor":true}""",
                        ).bodyAsText()
                        .id()
                owner.putJson(PATH, """{"appId":"$competitor","step":"connect"}""").status shouldBe HttpStatusCode.BadRequest
                owner.postJson("$PATH/$competitor/sync", "{}").status shouldBe HttpStatusCode.BadRequest
                owner.putJson(PATH, """{"appId":"invalid","step":"connect"}""").status shouldBe HttpStatusCode.BadRequest
                owner.postJson("/api/orgs", """{"name":"Other"}""")
                val foreign =
                    owner
                        .postJson(
                            "/api/orgs/other/apps",
                            """{"name":"Cizí","gpPackageName":"cz.example.other"}""",
                        ).bodyAsText()
                        .id()
                owner.putJson(PATH, """{"appId":"$foreign","step":"connect"}""").status shouldBe HttpStatusCode.NotFound
                owner.get("$PATH/$foreign/sync").status shouldBe HttpStatusCode.NotFound
                owner.postJson("$PATH/$foreign/sync", "{}").status shouldBe HttpStatusCode.NotFound
                val member = browser()
                member.signUpVerified("member@example.test", mailer)
                owner.postJson("/api/orgs/studio/invitations", """{"email":"member@example.test","role":"MEMBER"}""")
                member.postJson("/api/invitations/accept", """{"token":"${mailer.lastToken()}"}""")
                member.putJson(PATH, """{"step":"app"}""").status shouldBe HttpStatusCode.Forbidden
                member.postJson("$PATH/$competitor/sync", "{}").status shouldBe HttpStatusCode.Forbidden
                member.get(PATH).bodyAsText() shouldContain "\"started\":false"
                val outsider = browser()
                outsider.signUpVerified("outsider@example.test", mailer)
                outsider.get(PATH).status shouldBe HttpStatusCode.NotFound
                browser().get(PATH).status shouldBe HttpStatusCode.Unauthorized
                owner.post("$PATH/$competitor/sync").status shouldBe HttpStatusCode.Forbidden
            }
        }

        "sync lease je atomický, tenantový a starý běh nepřepíše novější" {
            testApplication {
                consoleModule(mailer)
                val owner = founder(mailer)
                val app =
                    AppId.parse(
                        owner
                            .postJson(
                                "/api/orgs/studio/apps",
                                """{"name":"Atlas","gpPackageName":"cz.example.atlas"}""",
                            ).bodyAsText()
                            .id(),
                    )
                val org = ExposedOrganizationRepository(TestDatabase.database.exposed).findBySlug("studio")!!.id
                val repository = ExposedOnboardingRepository(TestDatabase.database.exposed)
                val now = Instant.parse("2026-10-25T12:00:00Z")
                val starts =
                    (1..4)
                        .map {
                            async(kotlinx.coroutines.Dispatchers.IO) { repository.startSync(org, app, setOf(Platform.ANDROID), now) }
                        }.awaitAll()
                starts.count { it } shouldBe 1
                repository.startSync(org, app, setOf(Platform.ANDROID), now + 6.minutes) shouldBe true
                repository.finishSync(org, app, OnboardingSync(Platform.ANDROID, "SUCCEEDED", now, now, 0))
                repository.syncs(org, app).single().status shouldBe "RUNNING"
                repository.finishSync(
                    org,
                    app,
                    OnboardingSync(Platform.ANDROID, "FAILED", now + 6.minutes, now + 7.minutes, message = "Výpadek"),
                )
                repository.syncs(org, app).single().status shouldBe "FAILED"
            }
        }
    })
