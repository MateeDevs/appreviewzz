package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.persistence.Database
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val logger = KotlinLogging.logger {}

fun runApi(
    config: AppConfig,
    database: Database,
) {
    logger.info { "API listening on ${config.server.host}:${config.server.port}" }
    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { apiModule(database) },
    ).start(wait = true)
}

fun Application.apiModule(database: Database) {
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = database::isHealthy, metrics = metrics)
}
