package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.OutgoingMail
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ConsoleLinks
import cz.matee.appreviewzz.crypto.Argon2PasswordHasher
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedSessionRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserTokenRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.time.Duration.Companion.minutes

private const val EMAIL = "tadeas@example.com"
private const val PASSWORD = "dostatecne-dlouhe-heslo"

/** Zachytává, co by šlo e-mailem — jednorázový odkaz jinak z aplikace nevyleze. */
private class RecordingMailer : Mailer {
    val sent = mutableListOf<OutgoingMail>()

    override fun send(mail: OutgoingMail) {
        sent += mail
    }

    fun lastToken(): String {
        val body = sent.last().body
        return checkNotNull(Regex("""token=([A-Za-z0-9_-]+)""").find(body)) { "V e-mailu není odkaz: $body" }
            .groupValues[1]
    }
}

/**
 * Přihlášení do console nad opravdovým Postgresem. Session, zamykání účtu i CSRF stojí na
 * tom, co se doopravdy zapíše do databáze — s falešnými repozitáři by test ověřoval sám sebe.
 */
class AuthRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        fun ApplicationTestBuilder.consoleModule(policy: AuthPolicy = AuthPolicy()) {
            val exposed = TestDatabase.database.exposed
            val organizations = ExposedOrganizationRepository(exposed)
            val memberships = ExposedMembershipRepository(exposed)
            val auth =
                AuthenticationService(
                    users = ExposedUserRepository(exposed),
                    sessions = ExposedSessionRepository(exposed),
                    tokens = ExposedUserTokenRepository(exposed),
                    // Levné parametry: test ověřuje smyčku přihlášení, ne odolnost argon2.
                    hasher = Argon2PasswordHasher(memoryKib = 256, iterations = 1, parallelism = 1),
                    mailer = mailer,
                    links = ConsoleLinks("https://console.test"),
                    policy = policy,
                )
            application {
                val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                apiModule(
                    database = TestDatabase.database,
                    metrics = metrics,
                    console =
                        ConsoleWiring(
                            auth = auth,
                            cookies = SessionCookies(secure = false, lifetime = policy.sessionLifetime),
                            organizations = organizations,
                            memberships = memberships,
                        ),
                )
            }
        }

        /** Klient, který si drží cookies jako prohlížeč — bez toho není co testovat. */
        fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies) }

        suspend fun HttpClient.csrf(): String {
            val response = get("/api/auth/csrf")
            response.status shouldBe HttpStatusCode.OK
            return Regex(""""token":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]
        }

        suspend fun HttpClient.postJson(
            path: String,
            body: String,
            csrf: String?,
        ): HttpResponse =
            post(path) {
                contentType(ContentType.Application.Json)
                csrf?.let { header(CSRF_HEADER, it) }
                setBody(body)
            }

        suspend fun HttpClient.register(
            email: String = EMAIL,
            password: String = PASSWORD,
        ): HttpResponse =
            postJson(
                "/api/auth/register",
                """{"email":"$email","password":"$password","displayName":"Tadeáš"}""",
                csrf(),
            )

        suspend fun HttpClient.login(
            email: String = EMAIL,
            password: String = PASSWORD,
        ): HttpResponse = postJson("/api/auth/login", """{"email":"$email","password":"$password"}""", csrf())

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "registrace, přihlášení a profil projdou celou smyčkou" {
            testApplication {
                consoleModule()
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
                consoleModule()
                val client = browser()
                client.register()
                val token = mailer.lastToken()

                client.postJson("/api/auth/email/verify", """{"token":"$token"}""", client.csrf()).status shouldBe
                    HttpStatusCode.NoContent
                client.login()
                client.get("/api/auth/me").bodyAsText() shouldContain "\"emailVerified\":true"

                // Podruhé už odkaz neplatí.
                client.postJson("/api/auth/email/verify", """{"token":"$token"}""", client.csrf()).status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "bez CSRF hlavičky se nedá přihlásit ani nic jiného změnit" {
            testApplication {
                consoleModule()
                val client = browser()
                client.register()

                val response = client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""", csrf = null)
                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain "csrf_failed"
            }
        }

        "cizí CSRF token neprojde" {
            testApplication {
                consoleModule()
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
                consoleModule()
                browser().get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "krátké heslo se odmítne s vysvětlením" {
            testApplication {
                consoleModule()
                val client = browser()
                val response = client.register(password = "kratke")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "weak_password"
            }
        }

        "druhá registrace téhož e-mailu skončí konfliktem" {
            testApplication {
                consoleModule()
                val client = browser()
                client.register()
                client.register().status shouldBe HttpStatusCode.Conflict
            }
        }

        "po sérii špatných hesel se účet dočasně zamkne" {
            testApplication {
                consoleModule(AuthPolicy(maxFailedLogins = 3, lockFor = 5.minutes))
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
                consoleModule()
                val client = browser()
                client.register()
                client.login()

                client.postJson("/api/auth/logout", "{}", client.csrf()).status shouldBe HttpStatusCode.NoContent
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "obnova hesla nastaví nové heslo a zruší běžící relace" {
            testApplication {
                consoleModule()
                val client = browser()
                client.register()
                client.login()

                client.postJson("/api/auth/password/forgot", """{"email":"$EMAIL"}""", client.csrf()).status shouldBe
                    HttpStatusCode.Accepted
                val token = mailer.lastToken()

                val other = browser()
                other
                    .postJson(
                        "/api/auth/password/reset",
                        """{"token":"$token","password":"uplne-nove-dlouhe-heslo"}""",
                        other.csrf(),
                    ).status shouldBe HttpStatusCode.NoContent

                // Relace z doby před resetem padla.
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
                client.login(password = PASSWORD).status shouldBe HttpStatusCode.Unauthorized
                client.login(password = "uplne-nove-dlouhe-heslo").status shouldBe HttpStatusCode.OK
            }
        }

        "žádost o obnovu neprozradí, jestli e-mail známe" {
            testApplication {
                consoleModule()
                val client = browser()
                client
                    .postJson("/api/auth/password/forgot", """{"email":"nikdo@example.com"}""", client.csrf())
                    .status shouldBe HttpStatusCode.Accepted
                mailer.sent shouldHaveSize 0
            }
        }

        "změna hesla odhlásí ostatní prohlížeče, ten aktuální ne" {
            testApplication {
                consoleModule()
                val first = browser()
                first.register()
                first.login()

                val second = browser()
                second.login().status shouldBe HttpStatusCode.OK

                second
                    .postJson(
                        "/api/auth/password/change",
                        """{"currentPassword":"$PASSWORD","newPassword":"jeste-delsi-nove-heslo"}""",
                        second.csrf(),
                    ).status shouldBe HttpStatusCode.NoContent

                second.get("/api/auth/me").status shouldBe HttpStatusCode.OK
                first.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "účet založený bez hesla (CLI, pozvánka) se registrací aktivuje" {
            testApplication {
                consoleModule()
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
