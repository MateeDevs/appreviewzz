package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class SessionIdleTest :
    StringSpec({

        beforeTest { TestDatabase.reset() }

        "relace, kterou nikdo týden nepoužil, se zruší" {
            val clock = TestClock()
            testApplication {
                consoleModule(
                    RecordingMailer(),
                    policy = AuthPolicy(sessionLifetime = 30.days, sessionIdleTimeout = 7.days),
                    clock = clock,
                )
                val client = browser()
                client.signUp("spici@example.com")
                client.get("/api/auth/me").status shouldBe HttpStatusCode.OK

                // Cookie pořád platí absolutní expirací, ale sedm dní se s ní nikdo nepřihlásil.
                clock.advance(7.days)

                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "používaná relace se nečinností nezruší" {
            val clock = TestClock()
            testApplication {
                consoleModule(
                    RecordingMailer(),
                    // Absolutní platnost schválně vysoko: tenhle test je o nečinnosti, ne o ní.
                    policy = AuthPolicy(sessionLifetime = 90.days, sessionIdleTimeout = 7.days),
                    clock = clock,
                )
                val client = browser()
                client.signUp("aktivni@example.com")

                // Každých pár dní jedno kliknutí — lhůta se posouvá.
                repeat(5) {
                    clock.advance(6.days)
                    client.get("/api/auth/me").status shouldBe HttpStatusCode.OK
                }
            }
        }

        "zrušená relace neožije pozdějším požadavkem" {
            val clock = TestClock()
            testApplication {
                consoleModule(
                    RecordingMailer(),
                    policy = AuthPolicy(sessionLifetime = 30.days, sessionIdleTimeout = 7.days),
                    clock = clock,
                )
                val client = browser()
                client.signUp("zrusena@example.com")

                clock.advance(7.days)
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized

                // Kdyby se relace jen odmítala místo rušení, tenhle požadavek by ji oživil.
                clock.advance(1.hours)
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
