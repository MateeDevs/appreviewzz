package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.connectors.appstore.AppStoreConnector
import cz.matee.appreviewzz.connectors.appstore.appStoreHttpClient
import cz.matee.appreviewzz.connectors.googleplay.GooglePlayConnector
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.crypto.CredentialVault
import cz.matee.appreviewzz.crypto.KekProviders
import cz.matee.appreviewzz.jobs.IngestJobs
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import java.time.Duration

/**
 * Ruční wiring — dokud se komponenty vejdou na obrazovku, je čitelnější než DI kontejner
 * a chybějící závislost spadne při kompilaci, ne až za běhu.
 */
fun ingestJobs(
    config: AppConfig,
    database: Database,
): IngestJobs {
    val exposed = database.exposed
    val apps = ExposedAppRepository(exposed)
    val credentials = ExposedCredentialRepository(exposed)
    val dataKeys = ExposedDataKeyRepository(exposed)

    val kekUri =
        config.vaultKekUri
            ?: error("Role worker potřebuje VAULT_KEK_URI (aws-kms://… nebo local://…) — bez něj neumí rozbalit credentials")
    val vault = CredentialVault(dataKeys, credentials, KekProviders.fromUri(kekUri))

    val ingest =
        IngestReviewsUseCase(
            apps = apps,
            credentials = credentials,
            reviews = ExposedReviewRepository(exposed),
            secrets = vault,
            audit = ExposedAuditLogRepository(exposed),
            sources =
                listOf(
                    GooglePlayConnector(googleHttpClient()),
                    AppStoreConnector(appStoreHttpClient()),
                ),
        )

    return IngestJobs(
        ingest = ingest,
        apps = apps,
        failedJobs = ExposedFailedJobRepository(exposed),
        sweepInterval = Duration.ofSeconds(config.worker.sweepIntervalSeconds),
    )
}
