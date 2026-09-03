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
                    orgId = organization.id.toString(),
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

    /**
     * Zneplatnění klíče, který jsme organizaci vydali — volá se po smazání záznamu z vaultu.
     *
     * Service account v našem projektu **zůstává**: klient ho má pozvaný v Play Console a
     * pozvánka je to jediné, co po něm chceme. Bez klíčů je účet mrtvý, a když si store
     * napojí znovu, dostane tentýž e-mail a pozvánka platí dál ([provision] účet adoptuje).
     *
     * Selhání se jen loguje: záznam už je pryč a klient s tím nic nezmůže. Sirotčí klíč
     * v IAM je pak úklid na naší straně, ne chyba, kterou má vidět v consoli.
     */
    suspend fun revokeKeys(
        organization: Organization,
        meta: CredentialMeta,
    ) {
        if (meta.origin != CredentialOrigin.PROVISIONED || meta.type != CredentialType.GP_SERVICE_ACCOUNT) return
        val email = meta.hint ?: return
        try {
            val settings = settings()
            provisioner.revokeKeys(settings.provisioner, settings.projectId, email)
            audit.append(
                auditEntry(
                    orgId = organization.id,
                    action = "credential.revoked",
                    actorType = ActorType.SYSTEM,
                    targetType = "credential",
                    targetId = meta.id.toString(),
                    metadata = mapOf("account" to email),
                ),
            )
        } catch (error: StoreConnectorException) {
            logger.error(error) { "Klíče service accountu $email se nepodařilo zneplatnit — zruš je v IAM ručně" }
        } catch (error: ConsoleException) {
            logger.error(error) { "Klíče service accountu $email se nepodařilo zneplatnit — zruš je v IAM ručně" }
        }
    }

    /**
     * Doplnění značky vlastníka na spravované účty organizace — jednorázový úklid po
     * zavedení adopce ([provision]).
     *
     * Účty vyrobené dřív značku nemají, takže by se při dalším napojení storu neadoptovaly:
     * jméno by bylo obsazené, klient by dostal účet `…-1@…` a v Play Console by musel
     * pozvat další e-mail. Pouští se přes CLI a dá se opakovat — už označený účet vrátí
     * `added = false`.
     */
    suspend fun markOwner(organization: Organization): List<OwnerMark> {
        val managed =
            credentials
                .listByOrg(organization.id, CredentialType.GP_SERVICE_ACCOUNT)
                .filter { it.origin == CredentialOrigin.PROVISIONED }
        if (managed.isEmpty()) return emptyList()

        val settings = settings()
        return managed.mapNotNull { meta ->
            val email = meta.hint ?: return@mapNotNull null
            OwnerMark(
                email = email,
                added = provisioner.markOwner(settings.provisioner, settings.projectId, email, organization.id.toString()),
            )
        }
    }

    /** Výsledek značkování jednoho účtu. `added = false` znamená, že značku už měl. */
    data class OwnerMark(
        val email: String,
        val added: Boolean,
    )

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
