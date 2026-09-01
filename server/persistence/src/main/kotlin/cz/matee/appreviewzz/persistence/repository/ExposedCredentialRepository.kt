package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialMeta
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.DataKeyId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.CredentialRepository
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.StoredCredential
import cz.matee.appreviewzz.persistence.schema.AppCredentials
import cz.matee.appreviewzz.persistence.schema.Apps
import cz.matee.appreviewzz.persistence.schema.Credentials
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Credentials v databázi. Payload sem přichází **už zašifrovaný** z vaultu (F1.2) —
 * repozitář otevřený text nikdy nevidí a `findMeta`/`listByOrg` záměrně nevracejí
 * ciphertext, aby se nedal omylem serializovat do API odpovědi.
 */
class ExposedCredentialRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : CredentialRepository {
    override fun create(
        orgId: OrganizationId,
        credential: NewCredential,
    ): CredentialMeta =
        transaction(database) {
            val now = clock.now()
            val id = credential.id
            Credentials.insert {
                it[Credentials.id] = id
                it[Credentials.orgId] = orgId
                it[type] = credential.type
                it[label] = credential.label
                it[dataKeyId] = credential.dataKeyId
                it[ciphertext] = credential.ciphertext
                it[fingerprint] = credential.fingerprint
                it[hint] = credential.hint
                it[origin] = credential.origin
                it[validationStatus] = ValidationStatus.UNKNOWN
                it[createdAt] = now
                it[updatedAt] = now
            }
            CredentialMeta(
                id = id,
                orgId = orgId,
                type = credential.type,
                label = credential.label,
                fingerprint = credential.fingerprint,
                hint = credential.hint,
                origin = credential.origin,
                validationStatus = ValidationStatus.UNKNOWN,
                validationError = null,
                validatedAt = null,
                createdAt = now,
            )
        }

    override fun findMeta(
        orgId: OrganizationId,
        id: CredentialId,
    ): CredentialMeta? =
        transaction(database) {
            Credentials
                .selectAll()
                .where { (Credentials.orgId eq orgId) and (Credentials.id eq id) }
                .firstOrNull()
                ?.toCredentialMeta()
        }

    override fun listByOrg(
        orgId: OrganizationId,
        type: CredentialType?,
    ): List<CredentialMeta> =
        transaction(database) {
            Credentials
                .selectAll()
                .where {
                    if (type == null) {
                        Credentials.orgId eq orgId
                    } else {
                        (Credentials.orgId eq orgId) and (Credentials.type eq type)
                    }
                }.orderBy(Credentials.createdAt to SortOrder.DESC)
                .map { it.toCredentialMeta() }
        }

    override fun loadForDecryption(
        orgId: OrganizationId,
        id: CredentialId,
    ): StoredCredential? =
        transaction(database) {
            Credentials
                .selectAll()
                .where { (Credentials.orgId eq orgId) and (Credentials.id eq id) }
                .firstOrNull()
                ?.let { row ->
                    StoredCredential(
                        meta = row.toCredentialMeta(),
                        dataKeyId = row[Credentials.dataKeyId],
                        ciphertext = row[Credentials.ciphertext],
                    )
                }
        }

    override fun replacePayload(
        orgId: OrganizationId,
        id: CredentialId,
        credential: NewCredential,
    ): CredentialMeta? =
        transaction(database) {
            val updated =
                Credentials.update({ (Credentials.orgId eq orgId) and (Credentials.id eq id) }) {
                    it[type] = credential.type
                    it[label] = credential.label
                    it[dataKeyId] = credential.dataKeyId
                    it[ciphertext] = credential.ciphertext
                    it[fingerprint] = credential.fingerprint
                    it[hint] = credential.hint
                    // Rotovaný klíč je znovu neověřený — starý výsledek by lhal.
                    it[validationStatus] = ValidationStatus.UNKNOWN
                    it[validationError] = null
                    it[validatedAt] = null
                }
            if (updated == 0) null else findMeta(orgId, id)
        }

    override fun reencrypt(
        orgId: OrganizationId,
        id: CredentialId,
        dataKeyId: DataKeyId,
        ciphertext: ByteArray,
    ): Boolean =
        transaction(database) {
            Credentials.update({ (Credentials.orgId eq orgId) and (Credentials.id eq id) }) {
                it[Credentials.dataKeyId] = dataKeyId
                it[Credentials.ciphertext] = ciphertext
            } > 0
        }

    override fun recordValidation(
        orgId: OrganizationId,
        id: CredentialId,
        status: ValidationStatus,
        error: String?,
        at: Instant,
    ): CredentialMeta? =
        transaction(database) {
            val updated =
                Credentials.update({ (Credentials.orgId eq orgId) and (Credentials.id eq id) }) {
                    it[validationStatus] = status
                    it[validationError] = error
                    it[validatedAt] = at
                }
            if (updated == 0) null else findMeta(orgId, id)
        }

    override fun attachToApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ) {
        transaction(database) {
            // Appka i credential musí patřit téže organizaci — bez téhle kontroly by šlo
            // připnout cizí klíč na vlastní appku a přes ingest ho začít používat.
            requireSameOrg(orgId, appId, credentialId)
            AppCredentials.insertIgnore {
                it[AppCredentials.appId] = appId
                it[AppCredentials.credentialId] = credentialId
                it[AppCredentials.purpose] = purpose
            }
        }
    }

    override fun detachFromApp(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
        purpose: CredentialPurpose,
    ): Boolean =
        transaction(database) {
            requireSameOrg(orgId, appId, credentialId)
            AppCredentials.deleteWhere {
                (AppCredentials.appId eq appId) and
                    (AppCredentials.credentialId eq credentialId) and
                    (AppCredentials.purpose eq purpose)
            } > 0
        }

    override fun findForApp(
        orgId: OrganizationId,
        appId: AppId,
        purpose: CredentialPurpose,
        type: CredentialType,
    ): CredentialMeta? =
        transaction(database) {
            val credentialIds =
                AppCredentials
                    .selectAll()
                    .where { (AppCredentials.appId eq appId) and (AppCredentials.purpose eq purpose) }
                    .map { it[AppCredentials.credentialId] }
            if (credentialIds.isEmpty()) {
                null
            } else {
                Credentials
                    .selectAll()
                    .where {
                        (Credentials.orgId eq orgId) and
                            (Credentials.type eq type) and
                            (Credentials.id inList credentialIds)
                    }.firstOrNull()
                    ?.toCredentialMeta()
            }
        }

    override fun delete(
        orgId: OrganizationId,
        id: CredentialId,
    ): Boolean =
        transaction(database) {
            Credentials.deleteWhere { (Credentials.orgId eq orgId) and (Credentials.id eq id) } > 0
        }

    private fun requireSameOrg(
        orgId: OrganizationId,
        appId: AppId,
        credentialId: CredentialId,
    ) {
        val appBelongs =
            Apps.selectAll().where { (Apps.orgId eq orgId) and (Apps.id eq appId) }.firstOrNull() != null
        val credentialBelongs =
            Credentials
                .selectAll()
                .where { (Credentials.orgId eq orgId) and (Credentials.id eq credentialId) }
                .firstOrNull() != null
        require(appBelongs && credentialBelongs) {
            "App $appId nebo credential $credentialId nepatří organizaci $orgId"
        }
    }
}
