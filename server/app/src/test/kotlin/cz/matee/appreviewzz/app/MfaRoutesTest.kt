package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Totp
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val EMAIL = "tadeas@example.com"
private const val PASSWORD = "dostatecne-dlouhe-heslo"

private fun HttpResponse.field(name: String): String =
    checkNotNull(Regex(""""$name":"([^"]+)"""").find(bodyText)) { "V odpovědi není '$name': $bodyText" }
        .groupValues[1]

private val HttpResponse.bodyText: String get() = kotlinx.coroutines.runBlocking { bodyAsText() }

private fun codesOf(body: String): List<String> = Regex("""[a-z0-9]{5}-[a-z0-9]{5}""").findAll(body).map { it.value }.toList()

class MfaRoutesTest :
    StringSpec({

        lateinit var clock: TestClock

        /** Přihlášený člověk se zapnutým druhým faktorem; vrací jeho tajemství. */
        suspend fun ApplicationTestBuilder.enableTotp(client: HttpClient): SecretPayload {
            client.signUp(EMAIL, PASSWORD)
            val setup = client.postJson("/api/auth/totp/setup", "{}")
            setup.status shouldBe HttpStatusCode.OK
            val secret = SecretPayload(setup.field("secret"))
            val confirmed =
                client.postJson(
                    "/api/auth/totp/confirm",
                    """{"code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""",
                )
            confirmed.status shouldBe HttpStatusCode.OK
            // Krok použitý při potvrzení je spotřebovaný — tentýž kód by se podruhé nepřijal.
            clock.advance(Totp.PERIOD)
            return secret
        }

        beforeTest {
            TestDatabase.reset()
            clock = TestClock()
        }

        "zapnutí druhého faktoru vrátí tajemství, odkaz pro appku a záchranné kódy" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val client = browser()
                client.signUp(EMAIL, PASSWORD)

                val setup = client.postJson("/api/auth/totp/setup", "{}")
                setup.bodyText shouldContain "otpauth://totp/appreviewzz:$EMAIL".replace("@", "%40")

                val secret = SecretPayload(setup.field("secret"))
                // Dokud se nastavení nepotvrdí, přihlášení se nemění.
                client.get("/api/auth/totp").bodyText shouldContain """"setupPending":true"""

                val confirmed =
                    client.postJson(
                        "/api/auth/totp/confirm",
                        """{"code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""",
                    )

                codesOf(confirmed.bodyText) shouldHaveSize 10
                client.get("/api/auth/totp").bodyText shouldContain """"enabled":true"""
            }
        }

        "špatný kód nastavení nezapne" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val client = browser()
                client.signUp(EMAIL, PASSWORD)
                client.postJson("/api/auth/totp/setup", "{}")

                val confirmed = client.postJson("/api/auth/totp/confirm", """{"code":"000000"}""")

                confirmed.status shouldBe HttpStatusCode.BadRequest
                client.get("/api/auth/totp").bodyText shouldContain """"enabled":false"""
            }
        }

        "se zapnutým druhým faktorem samotné heslo relaci nevydá" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                enableTotp(browser())

                val client = browser()
                val login = client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""")

                login.status shouldBe HttpStatusCode.Accepted
                login.bodyText shouldContain "challenge"
                // Žádná cookie, žádná relace — na tom celý druhý faktor stojí.
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "kód z appky přihlášení dokončí" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val secret = enableTotp(browser())

                val client = browser()
                val challenge =
                    client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")
                val code = Totp.code(secret, Totp.stepAt(clock.current))

                val done = client.postJson("/api/auth/mfa/verify", """{"challenge":"$challenge","code":"$code"}""")

                done.status shouldBe HttpStatusCode.OK
                done.bodyText shouldContain """"mfaEnabled":true"""
                client.get("/api/auth/me").status shouldBe HttpStatusCode.OK
            }
        }

        "překlep v kódu nezruší rozdělané přihlášení" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val secret = enableTotp(browser())

                val client = browser()
                val challenge =
                    client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")

                client
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$challenge","code":"000000"}""")
                    .status shouldBe HttpStatusCode.Unauthorized

                // Tatáž challenge musí dál platit, jinak by jeden překlep znamenal zadávat heslo znovu.
                val code = Totp.code(secret, Totp.stepAt(clock.current))
                client
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$challenge","code":"$code"}""")
                    .status shouldBe HttpStatusCode.OK
            }
        }

        "tentýž kód podruhé neprojde ani ve svém okně" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val secret = enableTotp(browser())
                val code = Totp.code(secret, Totp.stepAt(clock.current))

                val first = browser()
                val firstChallenge =
                    first.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")
                first
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$firstChallenge","code":"$code"}""")
                    .status shouldBe HttpStatusCode.OK

                // Odposlechnutý kód platí ještě zbytek okna; přihlásit se jím podruhé ale nejde.
                clock.advance(5.seconds)
                val second = browser()
                val secondChallenge =
                    second.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")

                second
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$secondChallenge","code":"$code"}""")
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "záchranný kód pustí dovnitř právě jednou" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val client = browser()
                client.signUp(EMAIL, PASSWORD)
                val setup = client.postJson("/api/auth/totp/setup", "{}")
                val secret = SecretPayload(setup.field("secret"))
                val recovery =
                    codesOf(
                        client
                            .postJson(
                                "/api/auth/totp/confirm",
                                """{"code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""",
                            ).bodyText,
                    ).first()

                val first = browser()
                val firstChallenge =
                    first.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")
                first
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$firstChallenge","code":"$recovery"}""")
                    .status shouldBe HttpStatusCode.OK

                val second = browser()
                val secondChallenge =
                    second.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")

                second
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$secondChallenge","code":"$recovery"}""")
                    .status shouldBe HttpStatusCode.Unauthorized
                first.get("/api/auth/totp").bodyText shouldContain """"remainingRecoveryCodes":9"""
            }
        }

        "rozdělané přihlášení po pár minutách vyprší" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val secret = enableTotp(browser())

                val client = browser()
                val challenge =
                    client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")

                clock.advance(6.minutes)
                val code = Totp.code(secret, Totp.stepAt(clock.current))

                client
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$challenge","code":"$code"}""")
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "vypnout druhý faktor chce heslo i kód" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val client = browser()
                val secret = enableTotp(client)

                // Samotná relace nestačí: ukradená cookie nesmí druhý faktor sundat.
                client
                    .postJson("/api/auth/totp/disable", """{"password":"$PASSWORD","code":"000000"}""")
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .postJson(
                        "/api/auth/totp/disable",
                        """{"password":"spatne-heslo-ale-dlouhe","code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""",
                    ).status shouldBe HttpStatusCode.Unauthorized

                clock.advance(30.seconds)
                val disabled =
                    client.postJson(
                        "/api/auth/totp/disable",
                        """{"password":"$PASSWORD","code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""",
                    )

                disabled.status shouldBe HttpStatusCode.NoContent
                client.get("/api/auth/totp").bodyText shouldContain """"enabled":false"""
            }
        }

        "rotace datového klíče druhý faktor nezneplatní" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val secret = enableTotp(browser())

                // Nový DEK a přešifrování; tajemství se nemění, jen klíč, pod kterým leží.
                consoleAppSecrets().rotateDataKey() shouldBe 1

                val client = browser()
                val challenge =
                    client.postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""").field("challenge")
                val code = Totp.code(secret, Totp.stepAt(clock.current))

                client
                    .postJson("/api/auth/mfa/verify", """{"challenge":"$challenge","code":"$code"}""")
                    .status shouldBe HttpStatusCode.OK
            }
        }

        "zapnutý druhý faktor se nedá přepsat novým nastavením" {
            testApplication {
                consoleModule(RecordingMailer(), clock = clock)
                val client = browser()
                enableTotp(client)

                client.postJson("/api/auth/totp/setup", "{}").status shouldBe HttpStatusCode.Conflict
            }
        }
    })
