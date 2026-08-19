package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.backup.BackupRetention
import cz.matee.appreviewzz.backup.BackupService
import cz.matee.appreviewzz.backup.BackupStores
import cz.matee.appreviewzz.backup.PostgresCommands
import cz.matee.appreviewzz.backup.PostgresTarget
import cz.matee.appreviewzz.connectors.appstore.AppStoreConnector
import cz.matee.appreviewzz.connectors.appstore.appStoreHttpClient
import cz.matee.appreviewzz.connectors.googleplay.GooglePlayConnector
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.crypto.CredentialVault
import cz.matee.appreviewzz.crypto.KekProviders
import cz.matee.appreviewzz.crypto.KekUsage
import cz.matee.appreviewzz.crypto.MeteredKekProvider
import cz.matee.appreviewzz.jobs.BackupJobs
import cz.matee.appreviewzz.jobs.IngestJobs
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedBackupRunRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
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
    val audit = ExposedAuditLogRepository(exposed)
    val backupRuns = ExposedBackupRunRepository(exposed)

    private val dataKeys = ExposedDataKeyRepository(exposed)
    private val failedJobs = ExposedFailedJobRepository(exposed)

    private val storeClientsDelegate = lazy { StoreClients() }
    private val storeClients by storeClientsDelegate

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

    val reviewSources: List<ReviewSource> by lazy {
        listOf(
            GooglePlayConnector(storeClients.googlePlay),
            AppStoreConnector(storeClients.appStore),
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
            sweepInterval = Duration.ofSeconds(config.worker.sweepIntervalSeconds),
        )

    /** Pustí HTTP klienty storů. Volá jen CLI — servery drží klienty po celou dobu běhu. */
    override fun close() {
        if (storeClientsDelegate.isInitialized()) storeClients.close()
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
