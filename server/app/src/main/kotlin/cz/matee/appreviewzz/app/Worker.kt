package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.crypto.KekUsage
import cz.matee.appreviewzz.jobs.BackupJobs
import cz.matee.appreviewzz.jobs.SchedulerConfig
import cz.matee.appreviewzz.jobs.buildScheduler
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.asDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
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
    registerVaultMetrics(metrics, components.kekUsage)

    // Provider návrhů se jinak vyrábí až u prvního doručení: špatně nastavená AI by pak
    // nespadla při startu, ale jako řada nedoručených recenzí v DLQ. Radši hned tady.
    logger.info { "Návrhy odpovědí: ${describeAi(config.ai)}" }
    components.suggestions

    val backupJobs = components.backupJobs()
    if (backupJobs == null) {
        logger.warn { "BACKUP_TARGET není nastavený — databáze se nezálohuje" }
    } else {
        logger.info { "Záloha databáze naplánovaná na ${config.backup.at} UTC" }
        registerBackupMetrics(metrics, backupJobs)
    }

    val scheduler =
        buildScheduler(
            dataSource = database.asDataSource(),
            jobs = components.ingestJobs(),
            deliveryJobs = components.deliveryJobs,
            replyJobs = components.replyJobs,
            backupJobs = backupJobs,
            config =
                SchedulerConfig(
                    threads = config.worker.schedulerThreads,
                    pollingInterval = Duration.ofSeconds(config.worker.pollingIntervalSeconds),
                ),
        )
    // Zastavit scheduler dřív než pool: běžící úloha musí stihnout dopsat výsledek do databáze.
    Runtime.getRuntime().addShutdownHook(Thread(scheduler::stop, "scheduler-shutdown"))
    scheduler.start()
    logger.info { "Worker started — ingest a doručování registrované" }

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

private fun describeAi(ai: AiConfig): String =
    when {
        ai.provider.equals("none", ignoreCase = true) -> "vypnuté (AI_PROVIDER=none) — do Slacku jde prázdný vstup"
        else -> "${ai.provider}${ai.model?.let { " ($it)" }.orEmpty()}"
    }

/**
 * Stáří poslední úspěšné zálohy. Je to metrika, ze které se dá postavit jediný alarm, který
 * u záloh opravdu dává smysl: „poslední záloha je starší než den". Bez záznamu vrací NaN,
 * což Prometheus nezobrazí jako nulu — vypnuté zálohy se nesmí tvářit jako čerstvé.
 */
private fun registerBackupMetrics(
    metrics: PrometheusMeterRegistry,
    backupJobs: BackupJobs,
) {
    Gauge
        .builder("appreviewzz.backup.last_success.age") { backupJobs.lastSuccessAgeSeconds() ?: Double.NaN }
        .description("Stáří poslední úspěšné zálohy databáze")
        .baseUnit("seconds")
        .strongReference(true)
        .register(metrics)
}

/**
 * Volání do správce klíčů. V našem provozu totéž hlídá CloudTrail alarm (F1.9), tahle metrika
 * je jeho protějšek pro self-host — a zároveň druhý pohled na tentýž jev: kdyby počty
 * nesouhlasily, klíč používá i někdo jiný než aplikace.
 */
private fun registerVaultMetrics(
    metrics: PrometheusMeterRegistry,
    usage: KekUsage,
) {
    FunctionCounter
        .builder("appreviewzz.vault.kek.unwrap", usage) { it.unwrapCount.toDouble() }
        .description("Rozbalení datového klíče správcem klíčů (v našem provozu jedno kms:Decrypt)")
        .register(metrics)
    FunctionCounter
        .builder("appreviewzz.vault.kek.generate", usage) { it.generateCount.toDouble() }
        .description("Vyrobené datové klíče — první credential organizace nebo rotace")
        .register(metrics)
    FunctionCounter
        .builder("appreviewzz.vault.kek.failure", usage) { it.failureCount.toDouble() }
        .description("Volání správce klíčů, která skončila chybou")
        .register(metrics)
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
