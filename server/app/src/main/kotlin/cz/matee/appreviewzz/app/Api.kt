package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.slack.SlackOAuth
import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.usecase.AuthenticationService
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

    val install = installRoutes(components)
    val console =
        ConsoleWiring(
            auth = components.authentication,
            cookies = components.sessionCookies,
            organizations = components.organizations,
            memberships = components.memberships,
        )

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { apiModule(database, metrics, verifier, intake, install, console) },
    ).start(wait = true)
}

/**
 * Instalační flow se zapíná až s OAuth údaji a veřejnou adresou. Do té doby appku instalujeme
 * ručně (vlastní dev workspace) a install endpointy vůbec neexistují.
 */
private fun installRoutes(components: Components): SlackInstallRoutes? {
    val oauth = components.slackOAuth ?: return null
    val states = components.slackInstallStates ?: return null
    val redirectUri = components.slackRedirectUri ?: return null
    logger.info { "Slack install flow je zapnutý, redirect URL: $redirectUri" }
    return SlackInstallRoutes(oauth, states, components.slackInstallStore, redirectUri)
}

/** Pohromadě, protože buď je nastavené všechno, nebo se endpointy neregistrují vůbec. */
class SlackInstallRoutes(
    val oauth: SlackOAuth,
    val states: SlackInstallStates,
    val store: SlackInstallStore,
    val redirectUri: String,
)

/** Co potřebuje console. Pohromadě, ať `apiModule` nemá deset volitelných parametrů. */
class ConsoleWiring(
    val auth: AuthenticationService,
    val cookies: SessionCookies,
    val organizations: OrganizationRepository,
    val memberships: MembershipRepository,
)

fun Application.apiModule(
    database: Database,
    metrics: PrometheusMeterRegistry,
    slackVerifier: SlackSignatureVerifier? = null,
    slackIntake: SlackReplyIntake? = null,
    slackInstall: SlackInstallRoutes? = null,
    console: ConsoleWiring? = null,
) {
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = database::isHealthy)
    // Bez ověření podpisu endpoint nevzniká: otevřený webhook by uměl publikovat odpovědi
    // jménem klienta.
    if (slackVerifier != null && slackIntake != null) slackWebhookRoutes(slackVerifier, slackIntake)
    slackInstall?.let { slackInstallRoutes(it.oauth, it.states, it.store, it.redirectUri) }
    console?.let { authRoutes(it.auth, it.cookies, it.organizations, it.memberships) }
}
