package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.jobs.SchedulerConfig
import cz.matee.appreviewzz.jobs.buildScheduler
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.asDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Stejný image jako API, jiný entrypoint ([ADR 0006]). Worker točí naplánované úlohy nad
 * db-schedulerem; HTTP server drží jen proto, aby ho orchestrátor uměl probovat.
 */
fun runWorker(
    config: AppConfig,
    database: Database,
    components: Components,
) {
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    startManagementServer(config, metrics)

    val scheduler =
        buildScheduler(
            dataSource = database.asDataSource(),
            jobs = components.ingestJobs(),
            config =
                SchedulerConfig(
                    threads = config.worker.schedulerThreads,
                    pollingInterval = Duration.ofSeconds(config.worker.pollingIntervalSeconds),
                ),
        )
    // Zastavit scheduler dřív než pool: běžící úloha musí stihnout dopsat výsledek do databáze.
    Runtime.getRuntime().addShutdownHook(Thread(scheduler::stop, "scheduler-shutdown"))
    scheduler.start()
    logger.info { "Worker started — ingest tasks registered" }

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = {
            // Worker je připravený, až umí sáhnout do databáze i rozdávat úlohy.
            workerModule(metrics) { database.isHealthy() && scheduler.schedulerState.isStarted }
        },
    ).start(wait = true)
}

fun Application.workerModule(
    metrics: PrometheusMeterRegistry,
    readiness: () -> Boolean,
) {
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = readiness)
}
