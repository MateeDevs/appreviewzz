package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class SecurityHeadersTest :
    StringSpec({

        beforeTest { TestDatabase.reset() }

        "odpověď nese CSP a spol." {
            testApplication {
                application {
                    apiModule(TestDatabase.database, PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                }

                val response = client.get("/health/live")

                response.status shouldBe HttpStatusCode.OK
                val csp = response.headers["Content-Security-Policy"]!!
                // Console je čistá SPA ze stejného původu — proto tu není žádné 'unsafe-inline'
                // u skriptů ani povolený cizí host.
                csp shouldContain "script-src 'self'"
                csp shouldContain "frame-ancestors 'none'"
                csp shouldContain "object-src 'none'"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers["X-Frame-Options"] shouldBe "DENY"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                response.headers["Permissions-Policy"] shouldContain "camera=()"
            }
        }

        "HSTS se posílá jen tam, kde jedeme na https" {
            testApplication {
                application {
                    apiModule(TestDatabase.database, PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                }

                // Na http by ji prohlížeč ignoroval a v lokálním běhu by akorát mátla.
                client.get("/health/live").headers["Strict-Transport-Security"].shouldBeNull()
            }

            testApplication {
                application {
                    apiModule(
                        TestDatabase.database,
                        PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
                        hardening = ApiHardening(https = true),
                    )
                }

                client.get("/health/live").headers["Strict-Transport-Security"] shouldContain "max-age="
            }
        }

        "odpovědi API se necachují" {
            testApplication {
                consoleModule(RecordingMailer())

                // Jsou v nich recenze, otisky klíčů a profil — do cache proxy nepatří.
                client.get("/api/auth/me").headers["Cache-Control"] shouldBe "no-store"
            }
        }
    })
