package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialOrigin
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.ChannelRepository
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.CredentialStore
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.StoreApp
import cz.matee.appreviewzz.core.port.StoreAppCatalog
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.port.auditEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Klíče ke storům (F3.4) — nejcitlivější část onboardingu.
 *
 * Pravidla, která tu platí bez výjimky:
 *
 * - **Payload jde jen dovnitř.** Ven z téhle třídy vychází [CredentialMeta]: otisk, štítek
 *   a neutrální nápověda (client_email, Key ID). Rozbalený klíč se dostane výhradně do
 *   konektoru při ověření, a to v rámci jednoho volání.
 * - **Nahraný klíč se hned ověří proti storu.** Chyba při onboardingu je levná; tentýž
 *   překlep zjištěný až prvním ingestem stojí klienta den čekání na recenze, které nedorazí.
 * - **Se klíči smí jen ADMIN a výš.** MEMBER v consoli vidí, že klíč existuje a jestli
 *   funguje — kvůli diagnostice —, ale nemůže s ním hnout.
 */
class CredentialService(
    private val credentials: CredentialRepository,
    private val apps: AppRepository,
    private val channels: ChannelRepository,
    private val vault: CredentialStore,
    private val sources: List<ReviewSource>,
    private val audit: AuditLogRepository,
    /** Storů, které umí vypsat aplikace účtu, je zatím jediný — App Store Connect. */
    private val catalogs: List<StoreAppCatalog> = emptyList(),
    private val clock: Clock = Clock.System,
) {
    fun list(
        orgId: OrganizationId,
        type: CredentialType? = null,
    ): List<CredentialMeta> = credentials.listByOrg(orgId, type)

    fun get(
        orgId: OrganizationId,
        id: CredentialId,
    ): CredentialMeta =
        credentials.findMeta(orgId, id)
            ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový klíč tu není")

    /**
     * Uložení nahraného klíče. `hint` počítá volající, protože k rozparsování klíče je
     * potřeba konektor daného storu — doména o formátu `.p8` ani service accountu neví.
     */
    fun add(
        organization: Organization,
        actor: OrgActor,
        type: CredentialType,
        label: String,
        payload: SecretPayload,
        hint: String?,
        origin: CredentialOrigin = CredentialOrigin.UPLOADED,
    ): CredentialMeta {
        requireRole(actor, OrgRole.ADMIN)
        val trimmed = label.trim().ifEmpty { throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Klíč potřebuje štítek") }

        val meta = vault.store(organization.id, type, trimmed, payload, hint, origin)
        audit(
            organization.id,
            actor,
            "credential.created",
            meta.id.toString(),
            mapOf("type" to meta.type.name, "fingerprint" to meta.fingerprint, "origin" to origin.name),
        )
        logger.info { "Credential ${meta.id} (${meta.type}) uložený v organizaci ${organization.slug}" }
        return meta
    }

    /**
     * Rotace: klient nahrál nový klíč pod stávající záznam. Zůstávají přiřazení k appkám
     * i kanály — proto se rotuje, místo aby se zakládal nový a starý mazal.
     *
     * Výsledek předchozího ověření se zahazuje: platil pro jiný obsah.
     */
    fun rotate(
        organization: Organization,
        actor: OrgActor,
        id: CredentialId,
        payload: SecretPayload,
        hint: String?,
    ): CredentialMeta {
        requireRole(actor, OrgRole.ADMIN)
        val before = get(organization.id, id)
        val meta =
            vault.replace(organization.id, id, payload, hint = hint)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový klíč tu není")
        credentials.recordValidation(organization.id, id, ValidationStatus.UNKNOWN, null, clock.now())
        audit(
            organization.id,
            actor,
            "credential.rotated",
            id.toString(),
            mapOf("from" to before.fingerprint, "to" to meta.fingerprint),
        )
        return meta.copy(validationStatus = ValidationStatus.UNKNOWN, validationError = null, validatedAt = null)
    }

    fun delete(
        organization: Organization,
        actor: OrgActor,
        id: CredentialId,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val meta = get(organization.id, id)
        // Kanál drží na credential cizí klíč; smazat ho zpod běžícího kanálu by rozbilo
        // doručování a databáze by to stejně odmítla — tohle je ta samá věta srozumitelně.
        val used =
            apps
                .listByOrg(organization.id)
                .flatMap { channels.listByApp(organization.id, it.id) }
                .filter { it.credentialId == id }
        if (used.isNotEmpty()) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Klíč používá ${used.size} kanál(ů) — nejdřív je odpoj",
            )
        }

        credentials.delete(organization.id, id)
        audit(organization.id, actor, "credential.deleted", id.toString(), mapOf("type" to meta.type.name))
    }

    /**
     * Přiřazení klíče k aplikaci. Typ klíče musí sedět se storem, který appka sleduje —
     * jinak by se chyba projevila až prvním ingestem hláškou, která nedává smysl.
     */
    fun attach(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val app = app(organization.id, appId)
        val meta = get(organization.id, credentialId)
        val platform = platformOf(meta.type)
        if (platform == null || app.storeIdentifier(platform) == null) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Klíč typu ${meta.type.name} k aplikaci ${app.name} nepatří — ta sleduje ${app.platforms().joinToString()}",
            )
        }

        credentials.attachToApp(organization.id, appId, credentialId, purpose)
        audit(
            organization.id,
            actor,
            "credential.attached",
            credentialId.toString(),
            mapOf("app" to appId.toString(), "purpose" to purpose.name),
        )
    }

    fun detach(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val removed = credentials.detachFromApp(organization.id, appId, credentialId, purpose)
        if (!removed) throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takové přiřazení tu není")
        audit(
            organization.id,
            actor,
            "credential.detached",
            credentialId.toString(),
            mapOf("app" to appId.toString(), "purpose" to purpose.name),
        )
    }

    /**
     * Ověření proti storu. Výsledek se zapisuje do metadat, takže console umí říct
     * „klíč funguje / přestal fungovat" i bez dalšího klikání.
     */
    suspend fun validate(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        credentialId: CredentialId,
    ): ValidationOutcome {
        requireRole(actor, OrgRole.ADMIN)
        val app = app(organization.id, appId)
        val meta = get(organization.id, credentialId)
        val platform =
            platformOf(meta.type)
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Klíč typu ${meta.type.name} se proti storu neověřuje")
        val identifier =
            app.storeIdentifier(platform)
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Aplikace ${app.name} nemá identifikátor pro $platform")
        val source =
            sources.firstOrNull { it.platform == platform }
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Pro $platform není zaregistrovaný konektor")

        val outcome =
            try {
                source.validate(StoreContext(identifier, vault.load(organization.id, credentialId)))
            } catch (error: StoreConnectorException) {
                // Chyba konektoru je taky výsledek ověření, ne pád requestu: klient má vidět
                // „klíč nemá oprávnění", ne pětistovku.
                ValidationOutcome(valid = false, message = "${error.kind}: ${error.message}")
            }

        credentials.recordValidation(
            organization.id,
            credentialId,
            if (outcome.valid) ValidationStatus.VALID else ValidationStatus.INVALID,
            outcome.message.takeUnless { outcome.valid },
            clock.now(),
        )
        audit(
            organization.id,
            actor,
            if (outcome.valid) "credential.validated" else "credential.validation_failed",
            credentialId.toString(),
            mapOf("app" to appId.toString()),
        )
        return outcome
    }

    /**
     * Aplikace, na které klíč dosáhne — vstup do výběru appek při onboardingu.
     *
     * Úspěšné volání je zároveň důkaz, že klíč funguje, takže se rovnou zapíše jako ověřený:
     * jinak by klient hned po výběru appek koukal na „klíč zatím neověřený", i když se právě
     * prokázal. Naopak selhání ověření zapisuje jen u chyby oprávnění — výpadek App Store
     * o klíči nic neříká.
     */
    suspend fun listStoreApps(
        organization: Organization,
        actor: OrgActor,
        credentialId: CredentialId,
    ): List<StoreApp> {
        requireRole(actor, OrgRole.ADMIN)
        val meta = get(organization.id, credentialId)
        val platform =
            platformOf(meta.type)
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Klíč typu ${meta.type.name} k žádnému storu nepatří")
        val catalog =
            catalogs.firstOrNull { it.platform == platform }
                ?: throw ConsoleException(
                    ConsoleFailure.INVALID_INPUT,
                    "$platform aplikace nevypisuje — jejich seznam se z API storu zjistit nedá",
                )

        return try {
            catalog.listApps(vault.load(organization.id, credentialId)).also {
                credentials.recordValidation(organization.id, credentialId, ValidationStatus.VALID, null, clock.now())
            }
        } catch (error: StoreConnectorException) {
            if (error.kind == StoreErrorKind.AUTH) {
                credentials.recordValidation(
                    organization.id,
                    credentialId,
                    ValidationStatus.INVALID,
                    error.message,
                    clock.now(),
                )
            }
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, error.message ?: "Store seznam aplikací nevrátil")
        }
    }

    private fun app(
        orgId: OrganizationId,
        id: AppId,
    ): App = apps.findById(orgId, id) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")

    private fun platformOf(type: CredentialType): Platform? =
        when (type) {
            CredentialType.GP_SERVICE_ACCOUNT -> Platform.ANDROID
            CredentialType.ASC_API_KEY -> Platform.IOS
            // Slack a Teams se neváží ke storu; jejich credential drží kanál, ne appka.
            CredentialType.SLACK_INSTALL, CredentialType.TEAMS_BOT_REF -> null
        }

    private fun audit(
        orgId: OrganizationId,
        actor: OrgActor,
        action: String,
        targetId: String,
        metadata: Map<String, String>,
    ) {
        audit.append(
            auditEntry(
                orgId = orgId,
                action = action,
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "credential",
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }
}
