package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes

private const val EMAIL = "tadeas@example.com"
private const val PASSWORD = "dostatecne-dlouhe-heslo"

class AuthRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        suspend fun HttpClient.register(
            email: String = EMAIL,
            password: String = PASSWORD,
        ): HttpResponse = postJson("/api/auth/register", """{"email":"$email","password":"$password","displayName":"Tadeáš"}""")

        suspend fun HttpClient.login(
            email: String = EMAIL,
            password: String = PASSWORD,
        ): HttpResponse = postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "registrace, přihlášení a profil projdou celou smyčkou" {
            testApplication {
                consoleModule(mailer)
                val client = browser()

                client.register().status shouldBe HttpStatusCode.Created
                mailer.sent shouldHaveSize 1
                mailer.sent.single().subject shouldContain "Potvrď"

                client.login().status shouldBe HttpStatusCode.OK

                val me = client.get("/api/auth/me")
                me.status shouldBe HttpStatusCode.OK
                me.bodyAsText() shouldContain "\"email\":\"$EMAIL\""
                me.bodyAsText() shouldContain "\"emailVerified\":false"
                me.bodyAsText() shouldContain "\"organizations\":[]"
            }
        }

        "potvrzení e-mailu z odkazu se propíše do profilu" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()
                val token = mailer.lastToken()

                client.postJson("/api/auth/email/verify", """{"token":"$token"}""").status shouldBe
                    HttpStatusCode.NoContent
                client.login()
                client.get("/api/auth/me").bodyAsText() shouldContain "\"emailVerified\":true"

                // Podruhé už odkaz neplatí.
                client.postJson("/api/auth/email/verify", """{"token":"$token"}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "bez CSRF hlavičky se nedá přihlásit ani nic jiného změnit" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()

                val response =
                    client.post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain "csrf_failed"
            }
        }

        "cizí CSRF token neprojde" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()
                client.csrf()

                client
                    .postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""", "token-odjinud")
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        "profil bez přihlášení je 401" {
            testApplication {
                consoleModule(mailer)
                browser().get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "krátké heslo se odmítne s vysvětlením" {
            testApplication {
                consoleModule(mailer)
                val response = browser().register(password = "kratke")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "weak_password"
            }
        }

        "druhá registrace téhož e-mailu skončí konfliktem" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()
                client.register().status shouldBe HttpStatusCode.Conflict
            }
        }

        "po sérii špatných hesel se účet dočasně zamkne" {
            testApplication {
                consoleModule(mailer, AuthPolicy(maxFailedLogins = 3, lockFor = 5.minutes))
                val client = browser()
                client.register()

                repeat(2) { client.login(password = "spatne-heslo-nekde").status shouldBe HttpStatusCode.Unauthorized }
                client.login(password = "spatne-heslo-nekde").status shouldBe HttpStatusCode.Locked

                // Zamčení platí i pro správné heslo — jinak by brzda nebyla k ničemu.
                val locked = client.login()
                locked.status shouldBe HttpStatusCode.Locked
                locked.bodyAsText() shouldContain "account_locked"
            }
        }

        "odhlášení zneplatní session" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()
                client.login()

                client.postJson("/api/auth/logout", "{}").status shouldBe HttpStatusCode.NoContent
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "obnova hesla nastaví nové heslo a zruší běžící relace" {
            testApplication {
                consoleModule(mailer)
                val client = browser()
                client.register()
                client.login()

                client.postJson("/api/auth/password/forgot", """{"email":"$EMAIL"}""").status shouldBe
                    HttpStatusCode.Accepted
                val token = mailer.lastToken()

                val other = browser()
                other
                    .postJson("/api/auth/password/reset", """{"token":"$token","password":"uplne-nove-dlouhe-heslo"}""")
                    .status shouldBe HttpStatusCode.NoContent

                // Relace z doby před resetem padla.
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
                client.login(password = PASSWORD).status shouldBe HttpStatusCode.Unauthorized
                client.login(password = "uplne-nove-dlouhe-heslo").status shouldBe HttpStatusCode.OK
            }
        }

        "žádost o obnovu neprozradí, jestli e-mail známe" {
            testApplication {
                consoleModule(mailer)
                browser()
                    .postJson("/api/auth/password/forgot", """{"email":"nikdo@example.com"}""")
                    .status shouldBe HttpStatusCode.Accepted
                mailer.sent shouldHaveSize 0
            }
        }

        "změna hesla odhlásí ostatní prohlížeče, ten aktuální ne" {
            testApplication {
                consoleModule(mailer)
                val first = browser()
                first.register()
                first.login()

                val second = browser()
                second.login().status shouldBe HttpStatusCode.OK

                second
                    .postJson(
                        "/api/auth/password/change",
                        """{"currentPassword":"$PASSWORD","newPassword":"jeste-delsi-nove-heslo"}""",
                    ).status shouldBe HttpStatusCode.NoContent

                second.get("/api/auth/me").status shouldBe HttpStatusCode.OK
                first.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "účet založený bez hesla (CLI, pozvánka) se registrací aktivuje" {
            testApplication {
                consoleModule(mailer)
                val users = ExposedUserRepository(TestDatabase.database.exposed)
                val existing = users.create(EMAIL, "Založený ručně")

                val client = browser()
                client.register().status shouldBe HttpStatusCode.Created
                client.login().status shouldBe HttpStatusCode.OK
                client.get("/api/auth/me").bodyAsText() shouldContain "\"id\":\"${existing.id}\""

                val account = users.findAccountById(existing.id).shouldNotBeNull()
                account.passwordHash.shouldNotBeNull()
            }
        }
    })
