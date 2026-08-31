package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.slack.SlackOAuth
import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.channels.teams.BotFrameworkAuthenticator
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.jobs.ReplyJobData
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

    if (config.console.baseUrl == null && config.console.allowedHosts.isEmpty()) {
        logger.warn {
            "CONSOLE_BASE_URL ani CONSOLE_ALLOWED_HOSTS nejsou nastavené — odkazy v e-mailech " +
                "vedou na doménu z požadavku, jakou si klient pošle. Pro produkci nastav CONSOLE_BASE_URL."
        }
    }

    val verifier = components.slackSignatureVerifier
    if (verifier == null) {
        logger.warn { "SLACK_SIGNING_SECRET není nastavený — odpovědi ze Slacku se nepřijímají" }
    }
    // Klient fronty, ne plánovač: API úlohu jen zařadí, publikuje ji worker. Používá ho
    // webhook ze Slacku i odpovídání z console — obojí musí přežít nasazení nové verze.
    val queue = buildSchedulerClient(database.asDataSource(), components.replyJobs)
    val intake =
        verifier?.let {
            SlackReplyIntake(components.reviewMessages) { data -> components.replyJobs.enqueue(queue, data) }
        }

    val teamsAuthenticator = components.teamsAuthenticator
    if (teamsAuthenticator == null) {
        logger.warn { "TEAMS_BOT_APP_ID/PASSWORD nejsou nastavené — odpovědi z Teams se nepřijímají" }
    }
    val teamsIntake =
        teamsAuthenticator?.let {
            TeamsReplyIntake(components.reviewMessages) { data -> components.replyJobs.enqueue(queue, data) }
        }

    val install = installRoutes(components)
    val console =
        ConsoleWiring(
            auth = components.authentication,
            mfa = components.mfaService,
            orgs = components.organizationService,
            apps = components.appService,
            appSetup = components.appSetupCheck,
            storeLookup = components.storeLookup,
            credentials = components.credentialService,
            channels = components.channelService,
            slack = components.consoleSlack,
            teams = components.consoleTeams,
            cookies = components.sessionCookies,
            organizations = components.organizations,
            memberships = components.memberships,
            reviews = components.reviewInbox,
            ratings = components.ratingsInsights,
            dailyRatings = components.dailyRatings,
            audit = components.audit,
            platform = components.platformAdmin,
            ingest = components.platformConfig,
            enqueueReply = { reply ->
                components.replyJobs.enqueue(
                    queue,
                    ReplyJobData(
                        orgId = reply.orgId,
                        reviewId = reply.reviewId,
                        // Odpověď z console nepatří do žádného kanálu; do Slacku se výsledek
                        // dopíše k té zprávě, která tam pro recenzi je (řeší publikace).
                        channelId = null,
                        body = reply.body,
                        source = ReplySource.CONSOLE.name,
                        authorExternalId = null,
                        authorDisplayName = reply.authorDisplayName,
                        authorUserId = reply.authorUserId,
                    ),
                )
            },
        )

    val rateLimits = RateLimits(config.rateLimit, metrics)
    if (!rateLimits.enabled) {
        logger.warn { "RATE_LIMIT_ENABLED=false — limity požadavků jsou vypnuté, musí je řešit proxy" }
    }
    val hardening =
        ApiHardening(
            rateLimits = rateLimits,
            trustedProxyHops = config.server.trustedProxyHops,
            https = config.console.baseUrl?.startsWith("https://") == true || config.environment != "local",
            indexable = config.environment == "prod",
        )

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = {
            apiModule(
                database = database,
                metrics = metrics,
                hardening = hardening,
                slackVerifier = verifier,
                slackIntake = intake,
                teamsAuthenticator = teamsAuthenticator,
                teamsIntake = teamsIntake,
                slackInstall = install,
                console = console,
            )
        },
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

/**
 * Ochrany, které se nasazují na celé API (F5). Pohromadě schválně: co z toho platí, závisí
 * na prostředí, a rozházené po parametrech by se na to snadno zapomnělo.
 *
 * Výchozí hodnoty jsou ty **nejmírnější**, protože je používají testy — limity vypnuté
 * (jinak by se testy odstřelily samy), žádná proxy před námi a http.
 */
class ApiHardening(
    val rateLimits: RateLimits = RateLimits.disabled(),
    /** Nová sada na každou aplikaci — jinak by si dvě instance v testu předávaly okno navzájem. */
    val replay: ReplayGuards = ReplayGuards(),
    val trustedProxyHops: Int = 0,
    val https: Boolean = false,
    /** Jen produkce patří do vyhledávačů. Výchozí `false`, protože špatně je tu jen jeden směr. */
    val indexable: Boolean = false,
)

/** Pohromadě, protože buď je nastavené všechno, nebo se endpointy neregistrují vůbec. */
class SlackInstallRoutes(
    val oauth: SlackOAuth,
    val states: SlackInstallStates,
    val store: SlackInstallStore,
    val redirectUri: String,
)

@Suppress("LongParameterList")
fun Application.apiModule(
    database: Database,
    metrics: PrometheusMeterRegistry,
    hardening: ApiHardening = ApiHardening(),
    slackVerifier: SlackSignatureVerifier? = null,
    slackIntake: SlackReplyIntake? = null,
    teamsAuthenticator: BotFrameworkAuthenticator? = null,
    teamsIntake: TeamsReplyIntake? = null,
    slackInstall: SlackInstallRoutes? = null,
    console: ConsoleWiring? = null,
) {
    installClientAddress(hardening.trustedProxyHops)
    installConsoleOrigin(hardening.trustedProxyHops, hardening.https)
    installSecurityHeaders(hardening.https, hardening.indexable)
    installObservability(metrics)
    installSerialization()
    installErrorHandling()
    healthRoutes(readiness = database::isHealthy)
    // Bez ověření podpisu endpoint nevzniká: otevřený webhook by uměl publikovat odpovědi
    // jménem klienta.
    if (slackVerifier != null &&
        slackIntake != null
    ) {
        slackWebhookRoutes(slackVerifier, slackIntake, hardening.rateLimits, hardening.replay.slack)
    }
    // Totéž pro Teams: bez ověření tokenu od Bot Connectoru endpoint nevzniká.
    if (teamsAuthenticator != null &&
        teamsIntake != null
    ) {
        teamsWebhookRoutes(teamsAuthenticator, teamsIntake, hardening.rateLimits, hardening.replay.teams)
    }
    slackInstall?.let { slackInstallRoutes(it.oauth, it.states, it.store, it.redirectUri, hardening.replay.slackInstall) }
    console?.let { consoleRoutes(it, hardening.rateLimits) }
    // Až po API: fallback bere všechno, co si nikdo jiný nevzal.
    consoleStaticRoutes()
}
