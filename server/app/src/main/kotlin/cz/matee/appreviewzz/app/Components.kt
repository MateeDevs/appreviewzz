package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.connectors.appstore.AppStoreConnector
import cz.matee.appreviewzz.connectors.appstore.appStoreHttpClient
import cz.matee.appreviewzz.connectors.googleplay.GooglePlayConnector
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.port.ReviewSource
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
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import io.ktor.client.HttpClient
import java.time.Duration

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

    private val dataKeys = ExposedDataKeyRepository(exposed)
    private val failedJobs = ExposedFailedJobRepository(exposed)

    private val storeClientsDelegate = lazy { StoreClients() }
    private val storeClients by storeClientsDelegate

    val vault: CredentialVault by lazy {
        val kekUri =
            config.vaultKekUri
                ?: throw IllegalStateException(
                    "Práce s credentials potřebuje VAULT_KEK_URI (aws-kms://… nebo local://…) — bez něj se nedají ani uložit, ani rozbalit",
                )
        CredentialVault(dataKeys, credentials, KekProviders.fromUri(kekUri))
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
