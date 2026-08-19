package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.jobs.buildSchedulerClient
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.asDataSource
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
    components: Components,
) {
    logger.info { "API listening on ${config.server.host}:${config.server.port}" }
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    startManagementServer(config, metrics)

    val verifier = components.slackSignatureVerifier
    if (verifier == null) {
        logger.warn { "SLACK_SIGNING_SECRET není nastavený — odpovědi ze Slacku se nepřijímají" }
    }
    // Klient fronty, ne plánovač: webhook úlohu jen zařadí, publikuje ji worker.
    val queue = verifier?.let { buildSchedulerClient(database.asDataSource(), components.replyJobs) }
    val intake =
        queue?.let { client ->
            SlackReplyIntake(components.reviewMessages) { data -> components.replyJobs.enqueue(client, data) }
        }

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { apiModule(database, metrics, verifier, intake) },
    ).start(wait = true)
}

fun Application.apiModule(
    database: Database,
    metrics: PrometheusMeterRegistry,
    slackVerifier: SlackSignatureVerifier? = null,
    slackIntake: SlackReplyIntake? = null,
) {
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = database::isHealthy)
    // Bez ověření podpisu endpoint nevzniká: otevřený webhook by uměl publikovat odpovědi
    // jménem klienta.
    if (slackVerifier != null && slackIntake != null) slackWebhookRoutes(slackVerifier, slackIntake)
}
