package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.persistence.Database
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val logger = KotlinLogging.logger {}

/**
 * Stejný image jako API, jiný entrypoint. Ve F0 jen drží health/metrics endpointy,
 * aby ho orchestrátor uměl probovat; db-scheduler tasky (ingest, ratings, health)
 * se registrují ve F1.
 */
fun runWorker(
    config: AppConfig,
    database: Database,
) {
    logger.info { "Worker started — no scheduled tasks registered yet (F1)" }
    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { workerModule(database) },
    ).start(wait = true)
}

fun Application.workerModule(database: Database) {
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = database::isHealthy, metrics = metrics)
}
