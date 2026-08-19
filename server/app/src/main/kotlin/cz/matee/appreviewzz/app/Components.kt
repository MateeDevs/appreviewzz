package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.ai.SuggestReplyProviders
import cz.matee.appreviewzz.ai.aiHttpClient
import cz.matee.appreviewzz.backup.BackupRetention
import cz.matee.appreviewzz.backup.BackupService
import cz.matee.appreviewzz.backup.BackupStores
import cz.matee.appreviewzz.backup.PostgresCommands
import cz.matee.appreviewzz.backup.PostgresTarget
import cz.matee.appreviewzz.channels.slack.SlackApi
import cz.matee.appreviewzz.channels.slack.SlackInstallStates
import cz.matee.appreviewzz.channels.slack.SlackNotificationChannel
import cz.matee.appreviewzz.channels.slack.SlackOAuth
import cz.matee.appreviewzz.channels.slack.SlackSignatureVerifier
import cz.matee.appreviewzz.channels.slack.slackHttpClient
import cz.matee.appreviewzz.connectors.appstore.AppStoreConnector
import cz.matee.appreviewzz.connectors.appstore.appStoreHttpClient
import cz.matee.appreviewzz.connectors.googleplay.GooglePlayConnector
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import cz.matee.appreviewzz.core.usecase.DeliverReviewUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.core.usecase.PublishReplyUseCase
import cz.matee.appreviewzz.crypto.CredentialVault
import cz.matee.appreviewzz.crypto.KekProviders
import cz.matee.appreviewzz.crypto.KekUsage
import cz.matee.appreviewzz.crypto.MeteredKekProvider
import cz.matee.appreviewzz.jobs.BackupJobs
import cz.matee.appreviewzz.jobs.DeliveryJobs
import cz.matee.appreviewzz.jobs.IngestJobs
import cz.matee.appreviewzz.jobs.ReplyJobs
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedBackupRunRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import io.ktor.client.HttpClient
import java.time.Duration
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * Ruční wiring — dokud se komponenty vejdou na obrazovku, je čitelnější než DI kontejner
 * a chybějící závislost spadne při kompilaci, ne až za běhu.
 *
 * Drahé věci (HTTP klienti storů, vault sahající na KMS) vznikají líně: `org list` ze seed CLI
 * nemá důvod otevírat spojení do Googlu ani chtít `VAULT_KEK_URI`.
 */
class Components(
    private val config: AppConfig,
    database: Database,
) : AutoCloseable {
    private val exposed = database.exposed

    val organizations = ExposedOrganizationRepository(exposed)
    val users = ExposedUserRepository(exposed)
    val memberships = ExposedMembershipRepository(exposed)
    val apps = ExposedAppRepository(exposed)
    val credentials = ExposedCredentialRepository(exposed)
    val reviews = ExposedReviewRepository(exposed)
    val reviewMessages = ExposedReviewMessageRepository(exposed)
    val replies = ExposedReplyRepository(exposed)
    val channels = ExposedChannelRepository(exposed)
    val audit = ExposedAuditLogRepository(exposed)
    val backupRuns = ExposedBackupRunRepository(exposed)

    private val dataKeys = ExposedDataKeyRepository(exposed)

    /** DLQ. Čte z ní jak scheduler, tak `jobs failed` v CLI — dokud není console (F3). */
    val failedJobs = ExposedFailedJobRepository(exposed)

    private val storeClientsDelegate = lazy { StoreClients() }
    private val storeClients by storeClientsDelegate

    private val aiClientDelegate = lazy { aiHttpClient() }
    private val slackClientDelegate = lazy { slackHttpClient() }

    /**
     * Počítadla volání KEK. Vznikají hned, i když se vault nikdy nepoužije — worker nad nimi
     * registruje metriku při startu a ta nesmí záviset na tom, jestli už někdo sáhl na klíč.
     */
    val kekUsage = KekUsage()

    val vault: CredentialVault by lazy {
        val kekUri =
            config.vaultKekUri
                ?: throw IllegalStateException(
                    "Práce s credentials potřebuje VAULT_KEK_URI (aws-kms://… nebo local://…) — bez něj se nedají ani uložit, ani rozbalit",
                )
        CredentialVault(dataKeys, credentials, MeteredKekProvider(KekProviders.fromUri(kekUri), kekUsage))
    }

    /**
     * Konektory vznikají jednou a slouží obojímu — čtení i publikaci. Sdílení instance není
     * kosmetika: uvnitř sedí cache OAuth tokenů, takže dva konektory nad jednou appkou by
     * si o token řekly dvakrát.
     */
    private val googlePlay: GooglePlayConnector by lazy { GooglePlayConnector(storeClients.googlePlay) }
    private val appStore: AppStoreConnector by lazy { AppStoreConnector(storeClients.appStore) }

    val reviewSources: List<ReviewSource> by lazy { listOf(googlePlay, appStore) }

    /**
     * Návrhy odpovědí. Provider vzniká líně i s vlastním HTTP klientem — bez AI se aplikace
     * chová stejně, jen do Slacku chodí prázdný vstup.
     */
    val suggestions: SuggestReplyProvider by lazy {
        SuggestReplyProviders.fromConfig(
            provider = config.ai.provider,
            apiKey = config.ai.apiKey,
            model = config.ai.model,
            httpClient = { aiClientDelegate.value },
        )
    }

    /**
     * Kanály, do kterých se doručuje. Teams přibude ve F4 stejným způsobem — doručovací
     * use-case o konkrétních kanálech neví nic.
     */
    val slackApi: SlackApi by lazy { SlackApi(slackClientDelegate.value) }

    val notificationChannels: List<NotificationChannel> by lazy { listOf(SlackNotificationChannel(slackApi)) }

    val delivery: DeliverReviewUseCase by lazy {
        DeliverReviewUseCase(
            apps = apps,
            reviews = reviews,
            channels = channels,
            messages = reviewMessages,
            secrets = vault,
            suggestions = suggestions,
            notificationChannels = notificationChannels,
        )
    }

    val replyTargets: List<ReplyTarget> by lazy { listOf(googlePlay, appStore) }

    val publishReply: PublishReplyUseCase by lazy {
        PublishReplyUseCase(
            apps = apps,
            reviews = reviews,
            replies = replies,
            channels = channels,
            messages = reviewMessages,
            credentials = credentials,
            secrets = vault,
            audit = audit,
            replyTargets = replyTargets,
            notificationChannels = notificationChannels,
        )
    }

    val ingest: IngestReviewsUseCase by lazy {
        IngestReviewsUseCase(
            apps = apps,
            credentials = credentials,
            reviews = reviews,
            secrets = vault,
            audit = audit,
            sources = reviewSources,
        )
    }

    /**
     * Zálohovací služba. Vzniká líně stejně jako vault — `org list` ze seed CLI nemá důvod
     * otevírat spojení do S3 ani se ptát po `pg_dump`.
     */
    val backup: BackupService by lazy {
        val target =
            config.backup.target
                ?: throw IllegalStateException(
                    "Zálohy nejsou nastavené — chybí BACKUP_TARGET (s3://bucket/prefix nebo file:///cesta)",
                )
        BackupService(
            target =
                PostgresTarget.fromJdbcUrl(
                    jdbcUrl = config.database.jdbcUrl,
                    user = config.database.user,
                    password = config.database.password,
                ),
            store = BackupStores.fromUri(target, config.backup.s3Endpoint),
            runs = backupRuns,
            commands =
                PostgresCommands(
                    pgDumpPath = config.backup.pgDumpPath,
                    pgRestorePath = config.backup.pgRestorePath,
                    timeout = config.backup.timeoutMinutes.minutes,
                ),
            retention =
                BackupRetention(
                    days = config.backup.retentionDays,
                    keepAtLeast = config.backup.keepAtLeast,
                ),
        )
    }

    /** `null` = zálohy nejsou nastavené; worker to zahlásí a úlohu nezaregistruje. */
    fun backupJobs(): BackupJobs? =
        if (!config.backup.enabled) {
            null
        } else {
            BackupJobs(
                backup = backup,
                backupRuns = backupRuns,
                failedJobs = failedJobs,
                schedule = BackupJobs.dailyAt(BackupJobs.parseTime(config.backup.at), ZoneId.of("UTC")),
            )
        }

    fun ingestJobs(): IngestJobs =
        IngestJobs(
            ingest = ingest,
            apps = apps,
            failedJobs = failedJobs,
            delivery = deliveryJobs,
            sweepInterval = Duration.ofSeconds(config.worker.sweepIntervalSeconds),
        )

    /** Jedna instance: scheduler ji potřebuje znát jako registrovanou úlohu i jako plánovače. */
    val deliveryJobs: DeliveryJobs by lazy { DeliveryJobs(deliver = delivery, failedJobs = failedJobs) }

    val replyJobs: ReplyJobs by lazy { ReplyJobs(publish = publishReply, failedJobs = failedJobs) }

    /**
     * Ověření podpisu Slacku. `null` znamená nenastavený `SLACK_SIGNING_SECRET` — interactivity
     * endpoint se pak nezaregistruje vůbec, místo aby běžel bez kontroly.
     */
    val slackSignatureVerifier: SlackSignatureVerifier? by lazy {
        config.slack.signingSecret?.let { SlackSignatureVerifier(SecretPayload(it)) }
    }

    /**
     * Podpis instalačních odkazů. Používá tentýž app-level secret jako ověření webhooku —
     * je to jediné tajemství, které Slack App má, a obojí je „důkaz, že je to od nás".
     */
    val slackInstallStates: SlackInstallStates? by lazy {
        config.slack.signingSecret?.let { SlackInstallStates(SecretPayload(it)) }
    }

    val slackOAuth: SlackOAuth? by lazy {
        val clientId = config.slack.clientId ?: return@lazy null
        val clientSecret = config.slack.clientSecret ?: return@lazy null
        SlackOAuth(slackClientDelegate.value, clientId, SecretPayload(clientSecret))
    }

    /** Veřejná adresa API; potřebuje ji instalační odkaz i OAuth redirect. */
    val publicBaseUrl: String? get() = config.slack.publicBaseUrl

    /** Adresa, kterou musí mít Slack App nastavenou jako Redirect URL. */
    val slackRedirectUri: String? get() = config.slack.publicBaseUrl?.let { SlackOAuth.redirectUri(it) }

    val slackInstallStore: SlackInstallStore by lazy { SlackInstallStore(vault, credentials, audit) }

    /** Pustí HTTP klienty storů. Volá jen CLI — servery drží klienty po celou dobu běhu. */
    override fun close() {
        if (storeClientsDelegate.isInitialized()) storeClients.close()
        if (aiClientDelegate.isInitialized()) aiClientDelegate.value.close()
        if (slackClientDelegate.isInitialized()) slackClientDelegate.value.close()
    }

    private class StoreClients : AutoCloseable {
        val googlePlay: HttpClient = googleHttpClient()
        val appStore: HttpClient = appStoreHttpClient()

        override fun close() {
            googlePlay.close()
            appStore.close()
        }
    }
}
