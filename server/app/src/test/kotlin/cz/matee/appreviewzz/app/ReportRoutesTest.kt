package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.OrgPlan
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

private const val OWNER = "vlastnik@example.com"
private const val SLUG = "matee"

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

private suspend fun ApplicationTestBuilder.ownerWithPaidApp(mailer: RecordingMailer): Pair<HttpClient, String> {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    val organizations = ExposedOrganizationRepository(TestDatabase.database.exposed)
    // Plán se nevynucuje, ale report se pro Starter negeneruje — ať se databáze neplní
    // JSONem, na který nikdo neklikne.
    organizations.updatePlan(checkNotNull(organizations.findBySlug(SLUG)).id, OrgPlan.INSIGHTS)
    val app =
        owner
            .postJson("/api/orgs/$SLUG/apps", """{"name":"Testovací appka","gpPackageName":"cz.matee.test"}""")
            .bodyAsText()
            .jsonValue("id")
    return owner to app
}

/**
 * Měsíční report a jeho sdílení (C1).
 *
 * Zajímavá je hlavně veřejná stránka: je to jediná adresa v celé aplikaci, která nemá
 * session, a jediné, co ji odemyká, je token v odkaze.
 */
class ReportRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "report se vygeneruje, sdílí odkazem a veřejná stránka nemá skripty" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithPaidApp(mailer)

                val created = owner.postJson("/api/orgs/$SLUG/apps/$appId/reports", "{}")
                created.status shouldBe HttpStatusCode.Created
                val reportId = created.bodyAsText().jsonValue("id")

                val shared = owner.postJson("/api/orgs/$SLUG/apps/$appId/reports/$reportId/share", "{}")
                val url = shared.bodyAsText().jsonValue("shareUrl")
                val token = url.substringAfterLast("/r/")

                val page = owner.get("/r/$token")
                page.status shouldBe HttpStatusCode.OK
                val html = page.bodyAsText()
                html shouldContain "Testovací appka"
                html shouldContain "Rozbor recenzí"
                html shouldContain "noindex"
                // Report se posílá cizímu člověku a otevírá v čemkoli — žádný skript, žádný
                // externí zdroj. Kdyby se sem někdy vloudil, tenhle test to zachytí.
                html shouldNotContain "<script"
                html shouldNotContain "http://"
            }
        }

        "zrušené sdílení odkaz zneplatní a neexistující token vypadá stejně" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithPaidApp(mailer)
                val reportId = owner.postJson("/api/orgs/$SLUG/apps/$appId/reports", "{}").bodyAsText().jsonValue("id")
                val url =
                    owner
                        .postJson("/api/orgs/$SLUG/apps/$appId/reports/$reportId/share", "{}")
                        .bodyAsText()
                        .jsonValue("shareUrl")
                val token = url.substringAfterLast("/r/")

                owner.deleteSigned("/api/orgs/$SLUG/apps/$appId/reports/$reportId/share").status shouldBe HttpStatusCode.NoContent

                owner.get("/r/$token").status shouldBe HttpStatusCode.NotFound
                owner.get("/r/uplne-vymysleny-token").status shouldBe HttpStatusCode.NotFound
            }
        }

        "přegenerování měsíce sdílený odkaz nezruší" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithPaidApp(mailer)
                val reportId = owner.postJson("/api/orgs/$SLUG/apps/$appId/reports", "{}").bodyAsText().jsonValue("id")
                val url =
                    owner
                        .postJson("/api/orgs/$SLUG/apps/$appId/reports/$reportId/share", "{}")
                        .bodyAsText()
                        .jsonValue("shareUrl")

                // Agentura odkaz poslala klientovi; doplnění výkladů ho nesmí odstřihnout.
                owner.postJson("/api/orgs/$SLUG/apps/$appId/reports", "{}")

                owner.get("/api/orgs/$SLUG/apps/$appId/reports").bodyAsText() shouldContain url
                owner.get("/r/${url.substringAfterLast("/r/")}").status shouldBe HttpStatusCode.OK
            }
        }

        "organizaci na plánu Starter se report negeneruje" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.postJson("/api/orgs", """{"name":"Matee"}""")
                val app =
                    owner
                        .postJson("/api/orgs/$SLUG/apps", """{"name":"Appka","gpPackageName":"cz.matee.test"}""")
                        .bodyAsText()
                        .jsonValue("id")

                val response = owner.postJson("/api/orgs/$SLUG/apps/$app/reports", "{}")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Starter"
            }
        }

        "reporty cizí organizace nejsou vidět" {
            testApplication {
                consoleModule(mailer)
                val (_, appId) = ownerWithPaidApp(mailer)

                val stranger = browser()
                stranger.signUpVerified("cizi@example.com", mailer)

                stranger.get("/api/orgs/$SLUG/apps/$appId/reports").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
