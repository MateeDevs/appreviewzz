package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.connectors.googleplay.GcpIamProvisioner
import cz.matee.appreviewzz.connectors.googleplay.GoogleServiceAccount
import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialOrigin
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.PlatformSettings
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.CredentialStore
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.auditEntry
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.OrgActor
import cz.matee.appreviewzz.core.usecase.PlatformConfig
import cz.matee.appreviewzz.core.usecase.requireRole
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Google Play bez zakládání čehokoli v Google Cloudu.
 *
 * Service account vyrobíme my v našem projektu a klientovi dáme jen e-mail, který pozve
 * do Play Console. Proti dřívějšímu postupu (klient si zakládá projekt, zapíná API, stahuje
 * JSON) je to jedna pozvánka místo desítky kroků v cizí konzoli.
 *
 * **Idempotentní záměrně.** Klient bude tlačítko mačkat znovu — z netrpělivosti, po refreshi,
 * u druhé appky. Druhé volání proto vrátí tentýž účet: jinak by v projektu přibývaly účty,
 * které nikdo nepozval, a klient by koukal na e-mail, který v Play Console nemá.
 */
class GooglePlayProvisioning(
    private val provisioner: GcpIamProvisioner,
    private val config: PlatformConfig,
    private val vault: CredentialStore,
    private val credentials: CredentialRepository,
    private val audit: AuditLogRepository,
) {
    suspend fun provision(
        organization: Organization,
        actor: OrgActor,
    ): CredentialMeta {
        requireRole(actor, OrgRole.ADMIN)

        existing(organization)?.let { meta ->
            logger.info { "Organizace ${organization.slug} už spravovaný service account má (${meta.hint})" }
            return meta
        }

        val settings = settings()
        val account =
            try {
                provisioner.provision(
                    provisioner = settings.provisioner,
                    projectId = settings.projectId,
                    orgSlug = organization.slug,
                    displayName = organization.name,
                )
            } catch (error: StoreConnectorException) {
                // Selhání je na naší straně (kvóta účtů v projektu, odebraná role), ne v datech
                // klienta — proto věta, která říká, že s tím nic nenadělá, a detail v logu.
                logger.error(error) { "Provisioning service accountu pro ${organization.slug} selhal" }
                throw ConsoleException(
                    ConsoleFailure.NOT_CONFIGURED,
                    "Google Play účet se nepodařilo vyrobit: ${error.message}. " +
                        "Ozvi se nám, nebo klíč nahraj ručně přes Pokročilé.",
                )
            }

        val meta =
            vault.store(
                orgId = organization.id,
                type = CredentialType.GP_SERVICE_ACCOUNT,
                label = MANAGED_LABEL,
                payload = account.key,
                // Celý e-mail: console ho ukazuje k pozvání do Play Console, dešifrovat kvůli
                // němu payload by znamenalo tahat klíč z vaultu kvůli zobrazení seznamu.
                hint = account.email,
                origin = CredentialOrigin.PROVISIONED,
            )

        audit.append(
            auditEntry(
                orgId = organization.id,
                action = "credential.provisioned",
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "credential",
                targetId = meta.id.toString(),
                metadata = mapOf("type" to CredentialType.GP_SERVICE_ACCOUNT.name, "account" to account.email),
            ),
        )
        return meta
    }

    /** Spravovaný účet organizace, pokud už existuje. Hledá se podle původu, ne podle štítku. */
    fun existing(organization: Organization): CredentialMeta? =
        credentials
            .listByOrg(organization.id, CredentialType.GP_SERVICE_ACCOUNT)
            .firstOrNull { it.origin == CredentialOrigin.PROVISIONED }

    private fun settings(): ProvisionerSettings {
        val projectId =
            config.text(PlatformSettings.GCP_PROVISIONER_PROJECT)
                ?: throw notConfigured("chybí GCP projekt")
        val key =
            config.secret(PlatformSettings.GCP_PROVISIONER_KEY)
                ?: throw notConfigured("chybí klíč provisioneru")
        val account =
            try {
                GoogleServiceAccount.parse(key)
            } catch (error: StoreConnectorException) {
                throw notConfigured("klíč provisioneru nejde načíst (${error.message})")
            }
        return ProvisionerSettings(account, projectId)
    }

    private fun notConfigured(detail: String) =
        ConsoleException(
            ConsoleFailure.NOT_CONFIGURED,
            "Automatické napojení Google Play zatím není na téhle instalaci nastavené ($detail). " +
                "Klíč jde nahrát ručně přes Pokročilé.",
        )

    private class ProvisionerSettings(
        val provisioner: GoogleServiceAccount,
        val projectId: String,
    )

    companion object {
        const val MANAGED_LABEL = "Google Play (spravovaný)"
    }
}
