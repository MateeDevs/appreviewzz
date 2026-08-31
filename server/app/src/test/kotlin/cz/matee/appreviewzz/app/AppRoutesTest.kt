package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

private const val OWNER = "vlastnik@example.com"
private const val COLLEAGUE = "kolega@example.com"
private const val OUTSIDER = "cizi@example.com"
private const val SLUG = "matee"

private suspend fun HttpClient.createApp(body: String = """{"name":"Testovací appka","gpPackageName":"cz.matee.test"}"""): HttpResponse =
    postJson("/api/orgs/$SLUG/apps", body)

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/** Zakladatel s hotovou organizací — od toho začíná každý test tady. */
private suspend fun io.ktor.server.testing.ApplicationTestBuilder.ownerWithOrg(mailer: RecordingMailer): HttpClient {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    return owner
}

class AppRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "založení appky vyplní výchozí hodnoty a pozná platformy" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)

                val created = owner.createApp()
                created.status shouldBe HttpStatusCode.Created
                val body = created.bodyAsText()
                body shouldContain "\"platforms\":[\"ANDROID\"]"
                body shouldContain "\"locale\":\"CS\""
                body shouldContain "\"timezone\":\"Europe/Prague\""
                body shouldContain "\"ingestIntervalMinutes\":30"
                body shouldContain "\"dailyDigestAt\":\"08:30\""
                body shouldContain "\"enabled\":true"

                owner.get("/api/orgs/$SLUG/apps").bodyAsText() shouldContain "cz.matee.test"
            }
        }

        "appka jen s App Store ID projde, bez obou storů ne" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)

                owner
                    .createApp("""{"name":"iOS appka","ascAppId":"1234567890"}""")
                    .bodyAsText() shouldContain "\"platforms\":[\"IOS\"]"

                val neither = owner.createApp("""{"name":"Nic"}""")
                neither.status shouldBe HttpStatusCode.BadRequest
                neither.bodyAsText() shouldContain "invalid_input"
            }
        }

        "appka bez zadaného notifyFrom má watermark od svého založení" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)

                // Klient v consoli datum nevyplní skoro nikdy — a bez watermarku by první ingest
                // vysypal do kanálu i recenze staré měsíce.
                val body = owner.createApp().bodyAsText()
                body shouldNotContain "\"notifyFrom\":null"
                body shouldContain "\"notifyFrom\":\"2"
            }
        }

        "notifyFrom 'now' se uloží jako čas, ne jako text" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)

                val created =
                    owner.createApp("""{"name":"Migrovaná","gpPackageName":"cz.matee.migrace","notifyFrom":"now"}""")
                created.bodyAsText() shouldNotContain "\"notifyFrom\":\"now\""
                created.bodyAsText() shouldContain "\"notifyFrom\":\"2"
            }
        }

        "tentýž balíček podruhé se odmítne" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)
                owner.createApp()

                val duplicate = owner.createApp("""{"name":"Kopie","gpPackageName":"cz.matee.test"}""")
                duplicate.status shouldBe HttpStatusCode.BadRequest
                duplicate.bodyAsText() shouldContain "Testovací appka"
            }
        }

        "nesmyslná zóna a interval mimo rozsah se vysvětlí" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)

                val zone = owner.createApp("""{"name":"A","gpPackageName":"cz.a","timezone":"Europe/Vysocina"}""")
                zone.status shouldBe HttpStatusCode.BadRequest
                zone.bodyAsText() shouldContain "Europe/Prague"

                val interval = owner.createApp("""{"name":"B","gpPackageName":"cz.b","ingestIntervalMinutes":1}""")
                interval.status shouldBe HttpStatusCode.BadRequest
                interval.bodyAsText() shouldContain "5"
            }
        }

        "úprava nastavení mění jen to, co se pošle" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)
                val id = owner.createApp().bodyAsText().jsonValue("id")

                val updated =
                    owner.patchJson(
                        "/api/orgs/$SLUG/apps/$id",
                        """{"name":"Přejmenovaná","locale":"en","ingestIntervalMinutes":60,"enabled":false}""",
                    )
                updated.status shouldBe HttpStatusCode.OK
                val body = updated.bodyAsText()
                body shouldContain "\"name\":\"Přejmenovaná\""
                body shouldContain "\"locale\":\"EN\""
                body shouldContain "\"ingestIntervalMinutes\":60"
                body shouldContain "\"enabled\":false"
                // Nezmíněná pole zůstala.
                body shouldContain "\"timezone\":\"Europe/Prague\""
                body shouldContain "\"gpPackageName\":\"cz.matee.test\""
            }
        }

        "člen appky vidí, ale nezakládá; mazat smí jen vlastník" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)
                val id = owner.createApp().bodyAsText().jsonValue("id")
                owner.postJson("/api/orgs/$SLUG/invitations", """{"email":"$COLLEAGUE","role":"ADMIN"}""")
                val admin = joinViaInvitation(mailer, COLLEAGUE)

                admin.get("/api/orgs/$SLUG/apps").bodyAsText() shouldContain "cz.matee.test"
                admin.createApp("""{"name":"Adminova","gpPackageName":"cz.matee.admin"}""").status shouldBe
                    HttpStatusCode.Created

                // Smazání bere s sebou recenze i kanály — proto jen vlastník.
                admin.deleteSigned("/api/orgs/$SLUG/apps/$id").status shouldBe HttpStatusCode.Forbidden
                owner.deleteSigned("/api/orgs/$SLUG/apps/$id").status shouldBe HttpStatusCode.NoContent
                owner.get("/api/orgs/$SLUG/apps/$id").status shouldBe HttpStatusCode.NotFound
            }
        }

        "appky cizí organizace nejsou vidět ani přes přímé ID" {
            testApplication {
                consoleModule(mailer)
                val owner = ownerWithOrg(mailer)
                val id = owner.createApp().bodyAsText().jsonValue("id")

                val outsider = browser()
                outsider.signUpVerified(OUTSIDER, mailer)
                outsider.postJson("/api/orgs", """{"name":"Cizí"}""")

                outsider.get("/api/orgs/$SLUG/apps/$id").status shouldBe HttpStatusCode.NotFound
                // Ani pod vlastní organizací: appka do ní nepatří.
                outsider.get("/api/orgs/cizi/apps/$id").status shouldBe HttpStatusCode.NotFound
                outsider.get("/api/orgs/cizi/apps").bodyAsText() shouldBe "[]"
            }
        }
    })
