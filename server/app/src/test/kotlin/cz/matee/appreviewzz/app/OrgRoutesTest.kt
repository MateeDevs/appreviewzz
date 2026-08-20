package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
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

private suspend fun HttpClient.createOrg(
    name: String = "Matee interní",
    slug: String? = null,
): HttpResponse =
    postJson(
        "/api/orgs",
        if (slug == null) """{"name":"$name"}""" else """{"name":"$name","slug":"$slug"}""",
    )

private suspend fun HttpClient.invite(
    slug: String,
    email: String,
    role: String = "MEMBER",
): HttpResponse = postJson("/api/orgs/$slug/invitations", """{"email":"$email","role":"$role"}""")

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/**
 * Organizace, členové a pozvánky přes API. Kromě šťastné cesty tu jde hlavně o dvě věci:
 * že se cizí organizace tváří jako neexistující a že organizace nikdy nezůstane bez vlastníka.
 */
class OrgRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "založení organizace udělá zakladatele vlastníkem a slug se odvodí z názvu" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)

                val created = owner.createOrg()
                created.status shouldBe HttpStatusCode.Created
                created.bodyAsText() shouldContain "\"slug\":\"matee-interni\""
                created.bodyAsText() shouldContain "\"role\":\"OWNER\""

                owner.get("/api/orgs").bodyAsText() shouldContain "matee-interni"
                owner.get("/api/auth/me").bodyAsText() shouldContain "\"role\":\"OWNER\""
            }
        }

        "bez potvrzeného e-mailu organizaci založit nejde" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUp(OWNER)

                val response = owner.createOrg()
                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain "email_not_verified"
            }
        }

        "obsazený slug se odmítne" {
            testApplication {
                consoleModule(mailer)
                val first = browser()
                first.signUpVerified(OWNER, mailer)
                first.createOrg(slug = "matee")

                val second = browser()
                second.signUpVerified(COLLEAGUE, mailer)
                val response = second.createOrg(name = "Jiná firma", slug = "matee")
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "slug_taken"
            }
        }

        "cizí organizace se tváří, že neexistuje" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")

                val outsider = browser()
                outsider.signUpVerified(OUTSIDER, mailer)

                // 404, ne 403: jinak by šlo hádáním adres zjistit, kdo je náš zákazník.
                outsider.get("/api/orgs/matee").status shouldBe HttpStatusCode.NotFound
                outsider.get("/api/orgs/matee/members").status shouldBe HttpStatusCode.NotFound
                outsider.invite("matee", "kdokoli@example.com").status shouldBe HttpStatusCode.NotFound
                outsider.get("/api/orgs").bodyAsText() shouldNotContain "matee"
            }
        }

        "pozvánka přivede kolegu do organizace a rovnou mu ověří e-mail" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                val slug = owner.createOrg(slug = "matee").bodyAsText().jsonValue("slug")

                val invited = owner.invite(slug, COLLEAGUE, role = "ADMIN")
                invited.status shouldBe HttpStatusCode.Created
                invited.bodyAsText() shouldContain "\"delivered\":true"
                owner.get("/api/orgs/$slug/invitations").bodyAsText() shouldContain COLLEAGUE

                val token = mailer.tokenOf(mailer.lastTo(COLLEAGUE))
                val colleague = browser()
                colleague.signUp(COLLEAGUE)

                val accepted = colleague.postJson("/api/invitations/accept", """{"token":"$token"}""")
                accepted.status shouldBe HttpStatusCode.OK
                accepted.bodyAsText() shouldContain "\"role\":\"ADMIN\""

                // Kliknutí na odkaz z e-mailu je důkaz, že adresa patří jemu.
                colleague.get("/api/auth/me").bodyAsText() shouldContain "\"emailVerified\":true"
                owner.get("/api/orgs/$slug/members").bodyAsText() shouldContain COLLEAGUE
                owner.get("/api/orgs/$slug/invitations").bodyAsText() shouldBe "[]"
            }
        }

        "pozvánku nelze přijmout cizím účtem ani dvakrát" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")
                owner.invite("matee", COLLEAGUE)
                val token = mailer.tokenOf(mailer.lastTo(COLLEAGUE))

                val outsider = browser()
                outsider.signUpVerified(OUTSIDER, mailer)
                val stolen = outsider.postJson("/api/invitations/accept", """{"token":"$token"}""")
                stolen.status shouldBe HttpStatusCode.BadRequest
                stolen.bodyAsText() shouldContain "invitation_invalid"

                val colleague = browser()
                colleague.signUp(COLLEAGUE)
                colleague.postJson("/api/invitations/accept", """{"token":"$token"}""").status shouldBe HttpStatusCode.OK
                colleague.postJson("/api/invitations/accept", """{"token":"$token"}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "nová pozvánka na tutéž adresu nahradí tu předchozí" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")

                owner.invite("matee", COLLEAGUE)
                val first = mailer.tokenOf(mailer.lastTo(COLLEAGUE))
                owner.invite("matee", COLLEAGUE, role = "ADMIN")

                val pending = owner.get("/api/orgs/matee/invitations").bodyAsText()
                pending shouldContain "\"role\":\"ADMIN\""

                val colleague = browser()
                colleague.signUp(COLLEAGUE)
                colleague.postJson("/api/invitations/accept", """{"token":"$first"}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "člen nesmí zvát ani měnit role" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")
                owner.invite("matee", COLLEAGUE)

                val colleague = joinViaInvitation(mailer, COLLEAGUE)

                val forbidden = colleague.invite("matee", "dalsi@example.com")
                forbidden.status shouldBe HttpStatusCode.Forbidden
                forbidden.bodyAsText() shouldContain "forbidden"

                val members = owner.get("/api/orgs/matee/members").bodyAsText()
                val colleagueId = Regex(""""userId":"([^"]+)","email":"$COLLEAGUE"""").find(members)!!.groupValues[1]
                colleague
                    .patchJson("/api/orgs/matee/members/$colleagueId", """{"role":"OWNER"}""")
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        "poslední vlastník se nedá odebrat ani degradovat" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")

                val members = owner.get("/api/orgs/matee/members").bodyAsText()
                val ownerId = members.jsonValue("userId")

                val degraded = owner.patchJson("/api/orgs/matee/members/$ownerId", """{"role":"ADMIN"}""")
                degraded.status shouldBe HttpStatusCode.Conflict
                degraded.bodyAsText() shouldContain "last_owner"

                owner.deleteSigned("/api/orgs/matee/members/$ownerId").status shouldBe HttpStatusCode.Conflict
            }
        }

        "vlastník může předat organizaci a pak z ní odejít" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")
                owner.invite("matee", COLLEAGUE, role = "ADMIN")

                val colleague = joinViaInvitation(mailer, COLLEAGUE)

                val members = owner.get("/api/orgs/matee/members").bodyAsText()
                val ownerId = Regex(""""userId":"([^"]+)","email":"$OWNER"""").find(members)!!.groupValues[1]
                val colleagueId = Regex(""""userId":"([^"]+)","email":"$COLLEAGUE"""").find(members)!!.groupValues[1]

                owner.patchJson("/api/orgs/matee/members/$colleagueId", """{"role":"OWNER"}""").status shouldBe
                    HttpStatusCode.NoContent
                owner.deleteSigned("/api/orgs/matee/members/$ownerId").status shouldBe HttpStatusCode.NoContent

                owner.get("/api/orgs/matee").status shouldBe HttpStatusCode.NotFound
                colleague.get("/api/orgs/matee/members").bodyAsText() shouldNotContain OWNER
            }
        }

        "zrušená pozvánka už nefunguje" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")
                owner.invite("matee", COLLEAGUE)
                val token = mailer.tokenOf(mailer.lastTo(COLLEAGUE))
                val id = owner.get("/api/orgs/matee/invitations").bodyAsText().jsonValue("id")

                owner.deleteSigned("/api/orgs/matee/invitations/$id").status shouldBe HttpStatusCode.NoContent

                val colleague = browser()
                colleague.signUp(COLLEAGUE)
                colleague.postJson("/api/invitations/accept", """{"token":"$token"}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "pozvat někoho, kdo už v organizaci je, nejde" {
            testApplication {
                consoleModule(mailer)
                val owner = browser()
                owner.signUpVerified(OWNER, mailer)
                owner.createOrg(slug = "matee")

                val response = owner.invite("matee", OWNER)
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "invalid_input"
            }
        }

        "organizace bez přihlášení nejsou vidět" {
            testApplication {
                consoleModule(mailer)
                val anonymous = browser()
                anonymous.get("/api/orgs").status shouldBe HttpStatusCode.Unauthorized
                anonymous.postJson("/api/orgs", """{"name":"Podvod"}""").status shouldBe HttpStatusCode.Unauthorized
                mailer.sent shouldHaveSize 0
            }
        }
    })
