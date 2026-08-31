package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.SealedSecret
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.PlatformAuditEntry
import cz.matee.appreviewzz.core.port.PlatformAuditRepository
import cz.matee.appreviewzz.core.port.PlatformSecretMeta
import cz.matee.appreviewzz.core.port.PlatformSecretRepository
import cz.matee.appreviewzz.core.port.PlatformSettingRepository
import cz.matee.appreviewzz.core.port.PlatformStats
import cz.matee.appreviewzz.core.port.PlatformStatsRepository
import cz.matee.appreviewzz.persistence.schema.Apps
import cz.matee.appreviewzz.persistence.schema.FailedJobs
import cz.matee.appreviewzz.persistence.schema.Organizations
import cz.matee.appreviewzz.persistence.schema.PlatformAuditLogs
import cz.matee.appreviewzz.persistence.schema.PlatformSecrets
import cz.matee.appreviewzz.persistence.schema.PlatformSettingsTable
import cz.matee.appreviewzz.persistence.schema.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Uložené hodnoty platformní konfigurace (F7.2). Čte se to celé najednou — je to nejvýš
 * pár desítek řádků a nad tím sedí cache s TTL, takže dotaz na jednotlivý klíč by znamenal
 * jen víc kulatých cest do databáze.
 */
class ExposedPlatformSettingRepository(
    private val database: ExposedDatabase,
) : PlatformSettingRepository {
    override fun all(): Map<String, String> =
        transaction(database) {
            PlatformSettingsTable
                .selectAll()
                .associate { it[PlatformSettingsTable.key] to it[PlatformSettingsTable.value] }
        }

    override fun upsert(
        key: String,
        value: String,
        actor: UserId?,
        at: Instant,
    ) {
        transaction(database) {
            PlatformSettingsTable.upsert {
                it[PlatformSettingsTable.key] = key
                it[PlatformSettingsTable.value] = value
                it[updatedAt] = at
                it[updatedBy] = actor
            }
        }
    }

    override fun delete(key: String): Boolean =
        transaction(database) {
            PlatformSettingsTable.deleteWhere { PlatformSettingsTable.key eq key } > 0
        }
}

/**
 * Tajemství platformy. `ciphertext` opouští tuhle třídu jen jako [SealedSecret] do vaultu —
 * metody, které vracejí něco do API ([listMeta], [findMeta]), sloupec vůbec nečtou.
 */
class ExposedPlatformSecretRepository(
    private val database: ExposedDatabase,
) : PlatformSecretRepository {
    override fun listMeta(): List<PlatformSecretMeta> =
        transaction(database) {
            PlatformSecrets
                .select(
                    PlatformSecrets.key,
                    PlatformSecrets.fingerprint,
                    PlatformSecrets.hint,
                    PlatformSecrets.updatedAt,
                    PlatformSecrets.updatedBy,
                ).orderBy(PlatformSecrets.key to SortOrder.ASC)
                .map { it.toSecretMeta() }
        }

    override fun findMeta(key: String): PlatformSecretMeta? =
        transaction(database) {
            PlatformSecrets
                .select(
                    PlatformSecrets.key,
                    PlatformSecrets.fingerprint,
                    PlatformSecrets.hint,
                    PlatformSecrets.updatedAt,
                    PlatformSecrets.updatedBy,
                ).where { PlatformSecrets.key eq key }
                .firstOrNull()
                ?.toSecretMeta()
        }

    override fun findSealed(key: String): SealedSecret? =
        transaction(database) {
            PlatformSecrets
                .selectAll()
                .where { PlatformSecrets.key eq key }
                .firstOrNull()
                ?.let { SealedSecret(it[PlatformSecrets.dataKeyId], it[PlatformSecrets.ciphertext]) }
        }

    override fun upsert(
        key: String,
        secret: SealedSecret,
        fingerprint: String,
        hint: String?,
        actor: UserId?,
        at: Instant,
    ) {
        transaction(database) {
            PlatformSecrets.upsert {
                it[PlatformSecrets.key] = key
                it[dataKeyId] = secret.dataKeyId
                it[ciphertext] = secret.ciphertext
                it[PlatformSecrets.fingerprint] = fingerprint
                it[PlatformSecrets.hint] = hint
                it[updatedAt] = at
                it[updatedBy] = actor
            }
        }
    }

    override fun delete(key: String): Boolean =
        transaction(database) {
            PlatformSecrets.deleteWhere { PlatformSecrets.key eq key } > 0
        }

    override fun listSealed(): List<Pair<String, SealedSecret>> =
        transaction(database) {
            PlatformSecrets.selectAll().map {
                it[PlatformSecrets.key] to SealedSecret(it[PlatformSecrets.dataKeyId], it[PlatformSecrets.ciphertext])
            }
        }

    override fun reseal(
        key: String,
        secret: SealedSecret,
    ) {
        transaction(database) {
            PlatformSecrets.update({ PlatformSecrets.key eq key }) {
                it[dataKeyId] = secret.dataKeyId
                it[ciphertext] = secret.ciphertext
            }
        }
    }

    private fun ResultRow.toSecretMeta() =
        PlatformSecretMeta(
            key = this[PlatformSecrets.key],
            fingerprint = this[PlatformSecrets.fingerprint],
            hint = this[PlatformSecrets.hint],
            updatedAt = this[PlatformSecrets.updatedAt],
            updatedBy = this[PlatformSecrets.updatedBy],
        )
}

class ExposedPlatformAuditRepository(
    private val database: ExposedDatabase,
) : PlatformAuditRepository {
    override fun append(entry: PlatformAuditEntry) {
        transaction(database) {
            PlatformAuditLogs.insert {
                it[actorUserId] = entry.actorUserId
                it[actorLabel] = entry.actorLabel
                it[action] = entry.action
                it[targetKey] = entry.targetKey
                it[metadata] = entry.metadata
                entry.createdAt?.let { at -> it[createdAt] = at }
            }
        }
    }

    override fun listRecent(limit: Int): List<PlatformAuditEntry> =
        transaction(database) {
            PlatformAuditLogs
                .selectAll()
                .orderBy(PlatformAuditLogs.id to SortOrder.DESC)
                .limit(limit)
                .map {
                    PlatformAuditEntry(
                        actorUserId = it[PlatformAuditLogs.actorUserId],
                        actorLabel = it[PlatformAuditLogs.actorLabel],
                        action = it[PlatformAuditLogs.action],
                        targetKey = it[PlatformAuditLogs.targetKey],
                        metadata = it[PlatformAuditLogs.metadata],
                        createdAt = it[PlatformAuditLogs.createdAt],
                    )
                }
        }
}

/**
 * Provozní čísla do platformního přehledu. Schválně **jen agregáty**: kdo chce vidět recenze
 * klienta, musí být jeho členem — správa platformy k tomu opravňuje stejně málo jako dřív.
 */
class ExposedPlatformStatsRepository(
    private val database: ExposedDatabase,
) : PlatformStatsRepository {
    override fun stats(): PlatformStats =
        transaction(database) {
            PlatformStats(
                organizations = Organizations.selectAll().count(),
                users = Users.selectAll().count(),
                apps = Apps.selectAll().count(),
                enabledApps = Apps.selectAll().where { Apps.enabled eq true }.count(),
                failedJobs = FailedJobs.selectAll().where { FailedJobs.resolvedAt.isNull() }.count(),
                appsWithIntervalOverride = Apps.selectAll().where { Apps.ingestIntervalMinutes.isNotNull() }.count(),
            )
        }
}
