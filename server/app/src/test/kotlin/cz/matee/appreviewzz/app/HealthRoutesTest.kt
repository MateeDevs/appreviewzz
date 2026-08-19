package cz.matee.appreviewzz.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class HealthRoutesTest :
    StringSpec({

        fun testModule(databaseUp: Boolean) =
            fun io.ktor.server.application.Application.() {
                val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                installObservability(metrics)
                installSerialization()
                installErrorHandling()
                healthRoutes(readiness = { databaseUp })
            }

        "liveness je UP i když databáze neodpovídá" {
            testApplication {
                application(testModule(databaseUp = false))
                val response = client.get("/health/live")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"status\":\"UP\""
            }
        }

        "readiness hlásí 503, když databáze neodpovídá" {
            testApplication {
                application(testModule(databaseUp = false))
                val response = client.get("/health/ready")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                response.bodyAsText() shouldContain "\"database\":\"DOWN\""
            }
        }

        "readiness hlásí 200, když databáze odpovídá" {
            testApplication {
                application(testModule(databaseUp = true))
                client.get("/health/ready").status shouldBe HttpStatusCode.OK
            }
        }

        "neznámá cesta vrací neutrální JSON chybu" {
            testApplication {
                application(testModule(databaseUp = true))
                val response = client.get("/neexistuje")
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain "not_found"
            }
        }

        "metriky nejsou na veřejném portu" {
            testApplication {
                application(testModule(databaseUp = true))
                client.get("/metrics").status shouldBe HttpStatusCode.NotFound
            }
        }

        "management port metriky vystavuje" {
            // JVM metriky do registru zapisuje až plugin na hlavním serveru, který oba
            // servery sdílí; tady stačí ověřit, že endpoint vrací obsah registru.
            val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            metrics.counter("test_marker").increment()

            testApplication {
                application { managementModule(metrics) }
                val response = client.get("/metrics")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "test_marker"
            }
        }
    })
