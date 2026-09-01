package cz.matee.appreviewzz.crypto

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.OrgDataKey
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.DataKeyRepository
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.StoredCredential
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Paměťové náhrady repozitářů. Vault testujeme bez databáze schválně — zajímá nás
 * kryptografie, ne SQL; to má vlastní testy nad Testcontainers.
 */
class InMemoryDataKeyRepository : DataKeyRepository {
    private val keys = mutableListOf<OrgDataKey>()

    override fun findActive(orgId: OrganizationId): OrgDataKey? = keys.firstOrNull { it.orgId == orgId && it.active }

    override fun findById(
        orgId: OrganizationId,
        id: DataKeyId,
    ): OrgDataKey? = keys.firstOrNull { it.orgId == orgId && it.id == id }

    override fun create(
        orgId: OrganizationId,
        kekUri: String,
        wrappedDek: ByteArray,
        at: Instant,
    ): OrgDataKey {
        keys.replaceAll { key ->
            if (key.orgId == orgId && key.active) {
                OrgDataKey(key.id, key.orgId, key.kekUri, key.wrappedDek, active = false, key.createdAt, at)
            } else {
                key
            }
        }
        val created = OrgDataKey(DataKeyId(Uuid.random()), orgId, kekUri, wrappedDek, true, at, null)
        keys += created
        return created
    }
}

class InMemoryCredentialRepository(
    private val clock: Clock = Clock.System,
) : CredentialRepository {
    private data class Row(
        val meta: CredentialMeta,
        val dataKeyId: DataKeyId,
        val ciphertext: ByteArray,
    )

    private val rows = mutableMapOf<CredentialId, Row>()

    override fun create(
        orgId: OrganizationId,
        credential: NewCredential,
    ): CredentialMeta {
        val meta =
            CredentialMeta(
                id = credential.id,
                orgId = orgId,
                type = credential.type,
                label = credential.label,
                fingerprint = credential.fingerprint,
                hint = credential.hint,
                origin = credential.origin,
                validationStatus = ValidationStatus.UNKNOWN,
                validationError = null,
                validatedAt = null,
                createdAt = clock.now(),
            )
        rows[credential.id] = Row(meta, credential.dataKeyId, credential.ciphertext)
        return meta
    }

    override fun findMeta(
        orgId: OrganizationId,
        id: CredentialId,
    ): CredentialMeta? = rows[id]?.meta?.takeIf { it.orgId == orgId }

    override fun listByOrg(
        orgId: OrganizationId,
        type: CredentialType?,
    ): List<CredentialMeta> =
        rows.values
            .map { it.meta }
            .filter { it.orgId == orgId && (type == null || it.type == type) }

    override fun loadForDecryption(
        orgId: OrganizationId,
        id: CredentialId,
    ): StoredCredential? =
        rows[id]
            ?.takeIf { it.meta.orgId == orgId }
            ?.let { StoredCredential(it.meta, it.dataKeyId, it.ciphertext) }

    override fun replacePayload(
        orgId: OrganizationId,
        id: CredentialId,
        credential: NewCredential,
    ): CredentialMeta? {
        val row = rows[id]?.takeIf { it.meta.orgId == orgId } ?: return null
        val meta =
            row.meta.copy(
                label = credential.label,
                fingerprint = credential.fingerprint,
                hint = credential.hint,
                validationStatus = ValidationStatus.UNKNOWN,
                validationError = null,
                validatedAt = null,
            )
        rows[id] = Row(meta, credential.dataKeyId, credential.ciphertext)
        return meta
    }

    override fun reencrypt(
        orgId: OrganizationId,
        id: CredentialId,
        dataKeyId: DataKeyId,
        ciphertext: ByteArray,
    ): Boolean {
        val row = rows[id]?.takeIf { it.meta.orgId == orgId } ?: return false
        rows[id] = row.copy(dataKeyId = dataKeyId, ciphertext = ciphertext)
        return true
    }

    override fun recordValidation(
        orgId: OrganizationId,
        id: CredentialId,
        status: ValidationStatus,
        error: String?,
        at: Instant,
    ): CredentialMeta? {
        val row = rows[id]?.takeIf { it.meta.orgId == orgId } ?: return null
        val meta = row.meta.copy(validationStatus = status, validationError = error, validatedAt = at)
        rows[id] = row.copy(meta = meta)
        return meta
    }

    override fun attachToApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ) = error("Vazby na appky vault neřeší")

    override fun detachFromApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ): Boolean = error("Vazby na appky vault neřeší")

    override fun findForApp(
        orgId: OrganizationId,
        appId: AppId,
        purpose: CredentialPurpose,
        type: CredentialType,
    ): CredentialMeta? = error("Vazby na appky vault neřeší")

    override fun delete(
        orgId: OrganizationId,
        id: CredentialId,
    ): Boolean = rows.remove(id) != null

    /** Testovací pomůcka: přepíše ciphertext, jako by ho někdo podstrčil přímo do databáze. */
    fun overwriteCiphertext(
        id: CredentialId,
        ciphertext: ByteArray,
        dataKeyId: DataKeyId,
    ) {
        val row = requireNotNull(rows[id]) { "Credential $id v testovacím úložišti není" }
        rows[id] = row.copy(ciphertext = ciphertext, dataKeyId = dataKeyId)
    }

    fun rawCiphertext(id: CredentialId): ByteArray = requireNotNull(rows[id]).ciphertext
}
