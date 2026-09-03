package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.ai.ConfiguredSuggestReplyProvider
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
import cz.matee.appreviewzz.channels.teams.BotFrameworkAuthenticator
import cz.matee.appreviewzz.channels.teams.TeamsApi
import cz.matee.appreviewzz.channels.teams.TeamsBotIdentity
import cz.matee.appreviewzz.channels.teams.TeamsNotificationChannel
import cz.matee.appreviewzz.channels.teams.TeamsTokens
import cz.matee.appreviewzz.channels.teams.teamsHttpClient
import cz.matee.appreviewzz.connectors.appstore.AppStoreConnector
import cz.matee.appreviewzz.connectors.appstore.AppStoreListingLookup
import cz.matee.appreviewzz.connectors.appstore.AppStoreListingRatingsSource
import cz.matee.appreviewzz.connectors.appstore.ITunesRatingsSource
import cz.matee.appreviewzz.connectors.appstore.appStoreHttpClient
import cz.matee.appreviewzz.connectors.googleplay.GcpIamProvisioner
import cz.matee.appreviewzz.connectors.googleplay.GooglePlayConnector
import cz.matee.appreviewzz.connectors.googleplay.PlayReportingRatingsSource
import cz.matee.appreviewzz.connectors.googleplay.PlayStoreListingLookup
import cz.matee.appreviewzz.connectors.googleplay.PlayStoreScrapeRatingsSource
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PasswordHasher
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReportingBucketProbe
import cz.matee.appreviewzz.core.port.ReviewRefreshSource
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import cz.matee.appreviewzz.core.usecase.AppService
import cz.matee.appreviewzz.core.usecase.AppSetupCheck
import cz.matee.appreviewzz.core.usecase.AuthPolicy
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ChannelService
import cz.matee.appreviewzz.core.usecase.ConsoleLinks
import cz.matee.appreviewzz.core.usecase.CredentialService
import cz.matee.appreviewzz.core.usecase.DailyRatingsUseCase
import cz.matee.appreviewzz.core.usecase.DeliverReviewUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.core.usecase.MfaService
import cz.matee.appreviewzz.core.usecase.OrganizationService
import cz.matee.appreviewzz.core.usecase.PlatformAdminService
import cz.matee.appreviewzz.core.usecase.PlatformConfig
import cz.matee.appreviewzz.core.usecase.PublishReplyUseCase
import cz.matee.appreviewzz.core.usecase.RatingsInsights
import cz.matee.appreviewzz.core.usecase.RefreshStoreRepliesUseCase
import cz.matee.appreviewzz.core.usecase.RevalidateCredentialsUseCase
import cz.matee.appreviewzz.core.usecase.ReviewInbox
import cz.matee.appreviewzz.crypto.AppSecretBox
import cz.matee.appreviewzz.crypto.Argon2PasswordHasher
import cz.matee.appreviewzz.crypto.CredentialVault
import cz.matee.appreviewzz.crypto.KekProviders
import cz.matee.appreviewzz.crypto.KekUsage
import cz.matee.appreviewzz.crypto.MeteredKekProvider
import cz.matee.appreviewzz.jobs.BackupJobs
import cz.matee.appreviewzz.jobs.DeliveryJobs
import cz.matee.appreviewzz.jobs.IngestJobs
import cz.matee.appreviewzz.jobs.MaintenanceJobs
import cz.matee.appreviewzz.jobs.RatingsJobs
import cz.matee.appreviewzz.jobs.RefreshRepliesJobs
import cz.matee.appreviewzz.jobs.ReplyJobs
import cz.matee.appreviewzz.jobs.RevalidateCredentialsJobs
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.repository.ExposedAppDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedBackupRunRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedInvitationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedPlatformAuditRepository
import cz.matee.appreviewzz.persistence.repository.ExposedPlatformSecretRepository
import cz.matee.appreviewzz.persistence.repository.ExposedPlatformSettingRepository
import cz.matee.appreviewzz.persistence.repository.ExposedPlatformStatsRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingSnapshotRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingsDigestRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import cz.matee.appreviewzz.persistence.repository.ExposedSessionRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserMfaRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserTokenRepository
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
    val invitations = ExposedInvitationRepository(exposed)
    val backupRuns = ExposedBackupRunRepository(exposed)
    val ratingSnapshots = ExposedRatingSnapshotRepository(exposed)
    val ratingsDigests = ExposedRatingsDigestRepository(exposed)

    val sessions = ExposedSessionRepository(exposed)
    val userTokens = ExposedUserTokenRepository(exposed)

    private val dataKeys = ExposedDataKeyRepository(exposed)
    private val appDataKeys = ExposedAppDataKeyRepository(exposed)
    private val userMfa = ExposedUserMfaRepository(exposed)

    /** Platformní konfigurace (F7). Bez `org_id` — nepatří k žádnému tenantovi. */
    val platformSettings = ExposedPlatformSettingRepository(exposed)
    val platformSecrets = ExposedPlatformSecretRepository(exposed)
    val platformAudit = ExposedPlatformAuditRepository(exposed)
    private val platformStats = ExposedPlatformStatsRepository(exposed)

    /** DLQ. Čte z ní jak scheduler, tak `jobs failed` v CLI — dokud není console (F3). */
    val failedJobs = ExposedFailedJobRepository(exposed)

    private val storeClientsDelegate = lazy { StoreClients() }
    private val storeClients by storeClientsDelegate

    private val aiClientDelegate = lazy { aiHttpClient() }
    private val slackClientDelegate = lazy { slackHttpClient() }
    private val teamsClientDelegate = lazy { teamsHttpClient() }

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
     * Druhý faktor (F5.3). `null` bez `VAULT_KEK_URI`: TOTP tajemství je plnohodnotný
     * credential a bez správce klíčů ho nemáme kam bezpečně uložit. Zapnout druhý faktor
     * tam pak nejde a console to řekne větou — to je poctivější než ho ukládat otevřeně.
     */
    val appSecrets: AppSecretBox? by lazy {
        val kekUri = config.vaultKekUri ?: return@lazy null
        AppSecretBox(
            keys = appDataKeys,
            kek = MeteredKekProvider(KekProviders.fromUri(kekUri), kekUsage),
            secrets = userMfa,
            platformSecrets = platformSecrets,
        )
    }

    val mfaService: MfaService? by lazy {
        MfaService(mfa = userMfa, vault = appSecrets ?: return@lazy null, users = users)
    }

    /**
     * Konektory vznikají jednou a slouží obojímu — čtení i publikaci. Sdílení instance není
     * kosmetika: uvnitř sedí cache OAuth tokenů, takže dva konektory nad jednou appkou by
     * si o token řekly dvakrát.
     */
    private val googlePlay: GooglePlayConnector by lazy { GooglePlayConnector(storeClients.googlePlay) }
    private val appStore: AppStoreConnector by lazy { AppStoreConnector(storeClients.appStore) }

    val reviewSources: List<ReviewSource> by lazy { listOf(googlePlay, appStore) }

    /** Dohledání jedné recenze umí zatím jen Google Play — ASC vrací historii celou. */
    val reviewRefreshSources: List<ReviewRefreshSource> by lazy { listOf(googlePlay) }

    /**
     * Zdroje hodnocení. Pro každou platformu dva: oficiální data a veřejný listing. Neslouží
     * jako alternativy — slučují se, protože každý dává něco jiného (průměr vs. rozpad po
     * hvězdách) a klient bez přístupu do Play Console jinak nemá odkud brát nic.
     */
    val ratingsSources: List<RatingsSource> by lazy {
        listOf(
            PlayReportingRatingsSource(storeClients.googlePlay),
            PlayStoreScrapeRatingsSource(storeClients.googlePlay),
            ITunesRatingsSource(storeClients.appStore),
            AppStoreListingRatingsSource(storeClients.appStore),
        )
    }

    /**
     * Návrhy odpovědí. Provider se řídí platformní konfigurací a umí se přestavět za běhu,
     * když se v consoli změní klíč (F7.6) — proměnné `AI_*` zůstávají výchozí hodnotou.
     * Bez AI se aplikace chová stejně, jen do Slacku chodí prázdný vstup.
     */
    val suggestions: SuggestReplyProvider by lazy {
        ConfiguredSuggestReplyProvider(config = platformConfig, httpClient = { aiClientDelegate.value })
    }

    /**
     * Kanály, do kterých se doručuje. Doručovací use-case o konkrétních kanálech neví nic —
     * pozná je jen podle [NotificationChannel.type].
     */
    val slackApi: SlackApi by lazy { SlackApi(slackClientDelegate.value) }

    /**
     * Registrace Azure Bota. `null` znamená instalaci bez Teams: kanály typu TEAMS pak zůstanou
     * v databázi, ale doručení je přeskočí a řekne proč — to je poctivější než tvářit se,
     * že se zpráva odeslala.
     */
    val teamsBot: TeamsBotIdentity? by lazy {
        val appId = config.teams.appId ?: return@lazy null
        val appPassword = config.teams.appPassword ?: return@lazy null
        TeamsBotIdentity(appId = appId, appPassword = SecretPayload(appPassword), tenantId = config.teams.tenantId)
    }

    val teamsApi: TeamsApi by lazy { TeamsApi(teamsClientDelegate.value) }

    val teamsTokens: TeamsTokens by lazy { TeamsTokens(teamsClientDelegate.value) }

    /**
     * Ověření příchozích aktivit z Bot Connectoru. `null` = bot není nastavený, takže se
     * messaging endpoint vůbec nezaregistruje (stejně jako u Slacku bez signing secretu).
     */
    val teamsAuthenticator: BotFrameworkAuthenticator? by lazy {
        teamsBot?.let { BotFrameworkAuthenticator(teamsClientDelegate.value, it) }
    }

    val notificationChannels: List<NotificationChannel> by lazy {
        listOfNotNull(
            SlackNotificationChannel(slackApi),
            teamsBot?.let { TeamsNotificationChannel(teamsApi, teamsTokens, it) },
        )
    }

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

    /** Noční úklid prošlých relací a uplatněných tokenů (F5.6). */
    val maintenanceJobs: MaintenanceJobs by lazy { MaintenanceJobs(sessions = sessions, tokens = userTokens) }

    /** Denní přehledy hodnocení (F4.4). */
    val dailyRatings: DailyRatingsUseCase by lazy {
        DailyRatingsUseCase(
            apps = apps,
            channels = channels,
            credentials = credentials,
            snapshots = ratingSnapshots,
            digests = ratingsDigests,
            secrets = vault,
            ratingsSources = ratingsSources,
            notificationChannels = notificationChannels,
        )
    }

    fun ratingsJobs(): RatingsJobs = RatingsJobs(ratings = dailyRatings, apps = apps, failedJobs = failedJobs)

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

    val refreshStoreReplies: RefreshStoreRepliesUseCase by lazy {
        RefreshStoreRepliesUseCase(
            apps = apps,
            credentials = credentials,
            reviews = reviews,
            secrets = vault,
            sources = reviewRefreshSources,
        )
    }

    val refreshRepliesJobs: RefreshRepliesJobs by lazy {
        RefreshRepliesJobs(refresh = refreshStoreReplies, apps = apps)
    }

    /**
     * Doověřování klíčů, které zatím nefungují (F onboarding). Hlídá hlavně pozvánku service
     * accountu do Play Console — klient jinak musí u dialogu sedět, než se práva propíšou.
     */
    val revalidateCredentials: RevalidateCredentialsUseCase by lazy {
        RevalidateCredentialsUseCase(
            apps = apps,
            credentials = credentials,
            secrets = vault,
            sources = reviewSources,
        )
    }

    val revalidateCredentialsJobs: RevalidateCredentialsJobs by lazy {
        RevalidateCredentialsJobs(revalidate = revalidateCredentials)
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
            ingestPolicy = platformConfig,
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

    /**
     * Přihlášení do console (F3.1). Hasher vzniká líně: seed CLI ani worker nemají důvod
     * držet v paměti nic z autentizace.
     */
    val passwordHasher: PasswordHasher by lazy { Argon2PasswordHasher() }

    /**
     * Dokud není nastavené SMTP, e-maily jdou do logu. Adresa console se bere z konfigurace,
     * a když chybí, z veřejné adresy API — pro lokální běh, kde je to totéž.
     */
    val mailer: Mailer by lazy {
        val mail = config.console.mail
        if (mail.smtpHost == null) {
            LoggingMailer(mail.from, mail.logLinks)
        } else {
            SmtpMailer(
                from = mail.from,
                host = mail.smtpHost,
                port = mail.smtpPort,
                user = mail.smtpUser,
                password = mail.smtpPassword,
                startTls = mail.startTls,
            )
        }
    }

    /**
     * Odkazy do konzole. Základ se bere z požadavku (viz [ConsoleLinks]), takže staging
     * posílá na staging; nakonfigurovaná adresa je záloha pro poštu mimo požadavek a zároveň
     * první povolená doména.
     */
    val consoleLinks: ConsoleLinks by lazy {
        ConsoleLinks(
            baseUrl = config.console.baseUrl ?: config.slack.publicBaseUrl,
            allowedHosts = config.console.allowedHosts,
        )
    }

    val authentication: AuthenticationService by lazy {
        AuthenticationService(
            users = users,
            sessions = sessions,
            tokens = userTokens,
            hasher = passwordHasher,
            mailer = mailer,
            links = consoleLinks,
            mfa = mfaService,
        )
    }

    /** Organizace, členové a pozvánky (F3.2). */
    val organizationService: OrganizationService by lazy {
        OrganizationService(
            organizations = organizations,
            memberships = memberships,
            users = users,
            invitations = invitations,
            audit = audit,
            mailer = mailer,
            links = consoleLinks,
        )
    }

    /**
     * Čtení platformní konfigurace (F7.2). Sedí v API i ve workeru — obojí se ptá na tentýž
     * interval a na tentýž klíč k AI, jen z jiného procesu.
     */
    val platformConfig: PlatformConfig by lazy {
        PlatformConfig(
            settings = platformSettings,
            secrets = platformSecrets,
            // Bez `VAULT_KEK_URI` zbývá prostředí: uložit tajemství nejde, přečíst z ENV ano.
            vault = appSecrets,
            env = System::getenv,
        )
    }

    /** `null` bez `VAULT_KEK_URI` jen u tajemství; sekce jako taková funguje i bez správce klíčů. */
    val platformAdmin: PlatformAdminService by lazy {
        PlatformAdminService(
            config = platformConfig,
            settings = platformSettings,
            secrets = platformSecrets,
            audit = platformAudit,
            stats = platformStats,
            apps = apps,
            vault = appSecrets,
        )
    }

    /** Sledované aplikace (F3.3). Interval stahování si bere z platformní konfigurace (F7.4). */
    val appService: AppService by lazy { AppService(apps = apps, audit = audit, ingest = platformConfig) }

    /** Čeká appka ještě na klíč nebo kanál (F3.3)? Jen čtení dvou tabulek, žádný stav. */
    val appSetupCheck: AppSetupCheck by lazy { AppSetupCheck(credentials = credentials, channels = channels) }

    /**
     * Vyčtení názvu z veřejného listingu, když si klient přidává appku odkazem ze storu.
     * Sahá na HTTP klienty storů, takže vzniká líně jako všechno ostatní kolem konektorů.
     */
    val storeLookup: StoreLookup by lazy {
        StoreLookup(
            listOf(
                PlayStoreListingLookup(storeClients.googlePlay),
                AppStoreListingLookup(storeClients.appStore),
            ),
        )
    }

    /**
     * Klíče ke storům (F3.4). Sahá na vault, takže vzniká líně — bez `VAULT_KEK_URI`
     * se console rozběhne a spadne až na první práci s klíčem, ne při startu.
     */
    val credentialService: CredentialService by lazy {
        CredentialService(
            credentials = credentials,
            apps = apps,
            channels = channels,
            vault = vault,
            sources = reviewSources,
            audit = audit,
            // Výpis aplikací účtu umí jen App Store Connect; Play takový endpoint nemá.
            catalogs = listOf(appStore),
            bucketProbes = ratingsSources.filterIsInstance<ReportingBucketProbe>(),
        )
    }

    /**
     * Výroba service accountů pro Google Play (onboarding). Sahá na vault i na HTTP klienta
     * Googlu, takže vzniká líně; jestli je provisioner opravdu nastavený, se pozná až při
     * volání — klíč se dá doplnit za běhu bez restartu.
     */
    val googlePlayProvisioning: GooglePlayProvisioning by lazy {
        GooglePlayProvisioning(
            provisioner = GcpIamProvisioner(storeClients.googlePlay),
            config = platformConfig,
            vault = vault,
            credentials = credentials,
            audit = audit,
        )
    }

    val channelService: ChannelService by lazy {
        ChannelService(
            channels = channels,
            apps = apps,
            credentials = credentials,
            secrets = vault,
            implementations = notificationChannels,
            audit = audit,
        )
    }

    /** Připojení Slacku z console. `null` by znamenalo instalaci úplně bez Slacku. */
    val consoleSlack: ConsoleSlack by lazy {
        ConsoleSlack(
            api = slackApi,
            vault = vault,
            audit = audit,
            installStates = slackInstallStates,
            publicBaseUrl = publicBaseUrl,
        )
    }

    /** Připojení Teams z console. Bot je app-level, per klient se drží jen tenant (F4.2). */
    val consoleTeams: ConsoleTeams by lazy { ConsoleTeams(teamsBot, teamsTokens, vault, audit) }

    /** Recenze, delivery health a audit log v consoli (F3.5). */
    val reviewInbox: ReviewInbox by lazy {
        ReviewInbox(
            reviews = reviews,
            messages = reviewMessages,
            replies = replies,
            apps = apps,
            channels = channels,
            credentials = credentials,
            failedJobs = failedJobs,
            audit = audit,
        )
    }

    /** Vývoj hodnocení pro graf v consoli (F4.5). */
    val ratingsInsights: RatingsInsights by lazy { RatingsInsights(apps = apps, snapshots = ratingSnapshots) }

    val sessionCookies: SessionCookies by lazy {
        SessionCookies(
            secure = config.console.secureCookies(config.environment),
            lifetime = AuthPolicy().sessionLifetime,
        )
    }

    /** Pustí HTTP klienty storů. Volá jen CLI — servery drží klienty po celou dobu běhu. */
    override fun close() {
        if (storeClientsDelegate.isInitialized()) storeClients.close()
        if (aiClientDelegate.isInitialized()) aiClientDelegate.value.close()
        if (slackClientDelegate.isInitialized()) slackClientDelegate.value.close()
        if (teamsClientDelegate.isInitialized()) teamsClientDelegate.value.close()
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
