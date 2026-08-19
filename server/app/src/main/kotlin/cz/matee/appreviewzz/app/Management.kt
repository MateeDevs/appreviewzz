package cz.matee.appreviewzz.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val logger = KotlinLogging.logger {}

/**
 * Metriky běží na vlastním portu, který se nikdy nevystavuje ven — scrape si pro ně
 * chodí monitoring po interní síti. Na veřejném portu by prozrazovaly vnitřní stav
 * aplikace i provoz na jednotlivých endpointech.
 */
fun startManagementServer(
    config: AppConfig,
    metrics: PrometheusMeterRegistry,
) {
    logger.info { "Management endpoints on ${config.server.host}:${config.server.managementPort}" }
    embeddedServer(
        Netty,
        port = config.server.managementPort,
        host = config.server.host,
        module = { managementModule(metrics) },
    ).start(wait = false)
}

fun Application.managementModule(metrics: PrometheusMeterRegistry) {
    routing {
        get("/metrics") {
            call.respondText(metrics.scrape())
        }
    }
}
