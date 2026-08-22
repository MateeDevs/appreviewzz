package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Malá routa, která jen řekne, za koho nás server považuje. */
private fun Application.whoAmI() {
    routing {
        get("/whoami") { call.respondText(call.clientIp() ?: "?") }
    }
}

class RateLimitingTest :
    StringSpec({

        "kbelík pustí burst a další požadavek odmítne s dobou čekání" {
            val limiter = RateLimiter(RateLimitRule("test", burst = 3, window = 1.minutes), TestClock())

            repeat(3) { limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Allowed>() }
            val rejected = limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Rejected>()

            // Tři za minutu = jeden token za dvacet sekund.
            rejected.retryAfter shouldBe 20.seconds
        }

        "kbelík se doplňuje průběžně, ne skokem na konci okna" {
            val clock = TestClock()
            val limiter = RateLimiter(RateLimitRule("test", burst = 3, window = 1.minutes), clock)
            repeat(3) { limiter.check("a") }

            clock.advance(20.seconds)
            limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Allowed>()
            limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Rejected>()
        }

        "každý klíč má vlastní kbelík" {
            val limiter = RateLimiter(RateLimitRule("test", burst = 1, window = 1.minutes), TestClock())

            limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Allowed>()
            limiter.check("b").shouldBeInstanceOf<RateLimitDecision.Allowed>()
            limiter.check("a").shouldBeInstanceOf<RateLimitDecision.Rejected>()
        }

        "plné kbelíky se uklidí, aby mapa nerostla donekonečna" {
            val clock = TestClock()
            val limiter = RateLimiter(RateLimitRule("test", burst = 1, window = 1.minutes), clock, maxKeys = 2)

            limiter.check("a")
            limiter.check("b")
            clock.advance(10.minutes)
            limiter.check("c")

            // 'a' i 'b' se mezitím doplnily na plno, takže se od nově založených neliší.
            limiter.trackedKeys shouldBe 1
        }

        "vypnuté limity nevytvoří žádný kbelík" {
            RateLimits.disabled().api shouldBe null
        }

        // --- adresa klienta ------------------------------------------------------------

        "za jednou proxy platí poslední položka X-Forwarded-For, ne první" {
            testApplication {
                application {
                    installClientAddress(trustedProxyHops = 1)
                    whoAmI()
                }

                // První položku si napsal klient sám, druhou připsala proxy.
                val seen = client.get("/whoami") { header("X-Forwarded-For", "9.9.9.9, 203.0.113.7") }.bodyAsText()

                seen shouldBe "203.0.113.7"
            }
        }

        "bez proxy se hlavička ignoruje úplně" {
            testApplication {
                application {
                    installClientAddress(trustedProxyHops = 0)
                    whoAmI()
                }

                val seen = client.get("/whoami") { header("X-Forwarded-For", "9.9.9.9") }.bodyAsText()

                seen shouldNotBe "9.9.9.9"
            }
        }

        // --- nasazení v API ------------------------------------------------------------

        "přihlašování se po několika pokusech zamkne na 429 s Retry-After" {
            TestDatabase.reset()
            testApplication {
                consoleModule(
                    RecordingMailer(),
                    limits = RateLimits(RateLimitConfig(authPerFiveMinutes = 3), clock = TestClock()),
                )
                val client = browser()
                val body = """{"email":"nikdo@example.com","password":"uplne-spatne-heslo"}"""

                repeat(3) { client.postJson("/api/auth/login", body).status shouldBe HttpStatusCode.Unauthorized }

                val blocked = client.postJson("/api/auth/login", body)
                blocked.status shouldBe HttpStatusCode.TooManyRequests
                blocked.headers["Retry-After"].shouldNotBeNull()
            }
        }

        "limit na e-mail platí i tam, kde se adresy střídají" {
            TestDatabase.reset()
            testApplication {
                consoleModule(
                    RecordingMailer(),
                    // Limity per adresa schválně vysoko: chytit to musí ten na účet.
                    limits =
                        RateLimits(
                            RateLimitConfig(apiPerMinute = 500, authPerFiveMinutes = 500, authPerIdentity = 2),
                            clock = TestClock(),
                        ),
                )
                val body = """{"email":"obet@example.com","password":"uplne-spatne-heslo"}"""

                repeat(2) {
                    browser().postJson("/api/auth/login", body).status shouldBe HttpStatusCode.Unauthorized
                }

                browser().postJson("/api/auth/login", body).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "obnova hesla se nedá použít na zaplavení cizí schránky" {
            TestDatabase.reset()
            val mailer = RecordingMailer()
            testApplication {
                consoleModule(
                    mailer,
                    limits =
                        RateLimits(
                            RateLimitConfig(apiPerMinute = 500, authPerFiveMinutes = 500, authPerIdentity = 2),
                            clock = TestClock(),
                        ),
                )
                val client = browser()
                client.signUp("obet@example.com")
                val before = mailer.sent.size

                repeat(2) { client.postJson("/api/auth/password/forgot", """{"email":"obet@example.com"}""") }
                val blocked = client.postJson("/api/auth/password/forgot", """{"email":"obet@example.com"}""")

                blocked.status shouldBe HttpStatusCode.TooManyRequests
                mailer.sent.size shouldBe before + 2
            }
        }
    })
