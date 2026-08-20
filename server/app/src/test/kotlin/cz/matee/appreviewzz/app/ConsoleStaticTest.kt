package cz.matee.appreviewzz.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Servírování console ze stejného procesu jako API. Testovací zdroje obsahují náhradní
 * `console/index.html`, takže se ověřuje přesně to chování, které bude mít produkční image.
 */
class ConsoleStaticTest :
    StringSpec({

        fun io.ktor.server.testing.ApplicationTestBuilder.staticModule() {
            application {
                val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                installObservability(metrics)
                installSerialization()
                installErrorHandling()
                healthRoutes(readiness = { true })
                consoleStaticRoutes()
            }
        }

        "kořen vrací index console" {
            testApplication {
                staticModule()
                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "<div id=\"root\">"
            }
        }

        "cesta routeru v prohlížeči taky vrací index, ne 404" {
            testApplication {
                staticModule()
                // Refresh na /matee/recenze musí fungovat stejně jako proklik v aplikaci.
                val response = client.get("/matee/recenze")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "<div id=\"root\">"
                response.headers[HttpHeaders.CacheControl] shouldContain "no-cache"
            }
        }

        "asset se servíruje se správným typem a dlouhou cache" {
            testApplication {
                staticModule()
                val response = client.get("/assets/index-test.js")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Text.JavaScript
                // Jméno souboru nese otisk obsahu, takže se smí cacheovat natrvalo.
                response.headers[HttpHeaders.CacheControl] shouldContain "immutable"
            }
        }

        "neznámá cesta API zůstává JSON chybou, ne HTML stránkou" {
            testApplication {
                staticModule()
                listOf("/api/neexistuje", "/webhooks/slack/neco", "/health/neco").forEach { path ->
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.bodyAsText() shouldContain "not_found"
                }
            }
        }

        "chybějící soubor je 404, ne HTML" {
            testApplication {
                staticModule()
                // Cesty routeru nemají příponu; co ji má, je asset — a ten prostě chybí.
                client.get("/favicon.ico").status shouldBe HttpStatusCode.NotFound
                client.get("/assets/neexistuje.js").status shouldBe HttpStatusCode.NotFound
            }
        }

        "cesta ven z adresáře console se nedá vylákat" {
            testApplication {
                staticModule()
                // Průchod nahoru nesmí vydat nic z jaru — ani obsah, ani náznak, že tam něco je.
                listOf("/../application.conf", "/..%2Flogback.xml", "/assets/../../logback.xml").forEach { path ->
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.bodyAsText() shouldNotContain "appender"
                }
            }
        }
    })
