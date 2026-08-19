package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AuditEntry
import cz.matee.appreviewzz.core.model.BackupRun
import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.core.model.FailedJob
import cz.matee.appreviewzz.core.model.FailedJobId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSnapshot
import cz.matee.appreviewzz.core.model.RatingSnapshotId
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.BackupRunRepository
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.core.port.RatingSnapshotRepository
import cz.matee.appreviewzz.persistence.schema.AuditLogs
import cz.matee.appreviewzz.persistence.schema.BackupRuns
import cz.matee.appreviewzz.persistence.schema.FailedJobs
import cz.matee.appreviewzz.persistence.schema.RatingSnapshots
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

private const val AVERAGE_SCALE = 3

class ExposedRatingSnapshotRepository(
    private val database: ExposedDatabase,
) : RatingSnapshotRepository {
    override fun upsert(
        orgId: OrganizationId,
        snapshot: NewRatingSnapshot,
        collectedAt: Instant,
    ): RatingSnapshot =
        transaction(database) {
            val average = snapshot.average?.let { BigDecimal(it).setScale(AVERAGE_SCALE, RoundingMode.HALF_UP) }
            val existingId =
                RatingSnapshots
                    .selectAll()
                    .where { scope(orgId, snapshot.appId, snapshot.platform, snapshot.date, snapshot.territory) }
                    .firstOrNull()
                    ?.get(RatingSnapshots.id)

            val id = existingId ?: RatingSnapshotId(Uuid.random())
            if (existingId == null) {
                RatingSnapshots.insert {
                    it[RatingSnapshots.id] = id
                    it[RatingSnapshots.orgId] = orgId
                    it[appId] = snapshot.appId
                    it[platform] = snapshot.platform
                    it[snapshotDate] = snapshot.date
                    it[territory] = snapshot.territory
                    it[RatingSnapshots.average] = average
                    it[totalCount] = snapshot.totalCount
                    it[histogram] = snapshot.histogram.ifEmpty { null }
                    it[ratingSource] = snapshot.source
                    it[RatingSnapshots.collectedAt] = collectedAt
                }
            } else {
                // Opakovaný běh téhož dne hodnoty přepíše — snapshot je stav, ne přírůstek.
                RatingSnapshots.update({ RatingSnapshots.id eq id }) {
                    it[RatingSnapshots.average] = average
                    it[totalCount] = snapshot.totalCount
                    it[histogram] = snapshot.histogram.ifEmpty { null }
                    it[ratingSource] = snapshot.source
                    it[RatingSnapshots.collectedAt] = collectedAt
                }
            }
            RatingSnapshot(
                id = id,
                orgId = orgId,
                appId = snapshot.appId,
                platform = snapshot.platform,
                date = snapshot.date,
                territory = snapshot.territory,
                average = average?.toDouble(),
                totalCount = snapshot.totalCount,
                histogram = snapshot.histogram,
                source = snapshot.source,
                collectedAt = collectedAt,
            )
        }

    override fun findByDate(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        date: LocalDate,
        territory: String,
    ): RatingSnapshot? =
        transaction(database) {
            RatingSnapshots
                .selectAll()
                .where { scope(orgId, appId, platform, date, territory) }
                .firstOrNull()
                ?.toRatingSnapshot()
        }

    override fun listRecent(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        limit: Int,
    ): List<RatingSnapshot> =
        transaction(database) {
            RatingSnapshots
                .selectAll()
                .where {
                    (RatingSnapshots.orgId eq orgId) and
                        (RatingSnapshots.appId eq appId) and
                        (RatingSnapshots.platform eq platform)
                }.orderBy(RatingSnapshots.snapshotDate to SortOrder.DESC)
                .limit(limit)
                .map { it.toRatingSnapshot() }
        }

    private fun scope(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        date: LocalDate,
        territory: String,
    ) = (RatingSnapshots.orgId eq orgId) and
        (RatingSnapshots.appId eq appId) and
        (RatingSnapshots.platform eq platform) and
        (RatingSnapshots.snapshotDate eq date) and
        (RatingSnapshots.territory eq territory)
}

class ExposedAuditLogRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : AuditLogRepository {
    override fun append(entry: AuditEntry) {
        transaction(database) {
            AuditLogs.insert {
                it[orgId] = entry.orgId
                it[actorType] = entry.actorType
                it[actorUserId] = entry.actorUserId
                it[actorLabel] = entry.actorLabel
                it[action] = entry.action
                it[targetType] = entry.targetType
                it[targetId] = entry.targetId
                it[metadata] = entry.metadata
                it[createdAt] = entry.createdAt ?: clock.now()
            }
        }
    }

    override fun list(
        orgId: OrganizationId,
        limit: Int,
    ): List<AuditEntry> =
        transaction(database) {
            AuditLogs
                .selectAll()
                .where { AuditLogs.orgId eq orgId }
                .orderBy(AuditLogs.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toAuditEntry() }
        }
}

class ExposedFailedJobRepository(
    private val database: ExposedDatabase,
) : FailedJobRepository {
    override fun record(
        taskName: String,
        taskInstance: String,
        orgId: OrganizationId?,
        payload: String?,
        errorClass: String?,
        errorMessage: String?,
        failedAt: Instant,
    ): FailedJob =
        transaction(database) {
            val open =
                FailedJobs
                    .selectAll()
                    .where { openScope(taskName, taskInstance) }
                    .firstOrNull()
                    ?.toFailedJob()

            if (open == null) {
                val job =
                    FailedJob(
                        id = FailedJobId(Uuid.random()),
                        orgId = orgId,
                        taskName = taskName,
                        taskInstance = taskInstance,
                        payload = payload,
                        errorClass = errorClass,
                        errorMessage = errorMessage,
                        attempts = 1,
                        firstFailedAt = failedAt,
                        lastFailedAt = failedAt,
                        resolvedAt = null,
                    )
                FailedJobs.insert {
                    it[id] = job.id
                    it[FailedJobs.orgId] = job.orgId
                    it[FailedJobs.taskName] = job.taskName
                    it[FailedJobs.taskInstance] = job.taskInstance
                    it[FailedJobs.payload] = job.payload
                    it[FailedJobs.errorClass] = job.errorClass
                    it[FailedJobs.errorMessage] = job.errorMessage
                    it[attempts] = 1
                    it[firstFailedAt] = failedAt
                    it[lastFailedAt] = failedAt
                }
                job
            } else {
                // Opakované selhání téhož tasku jen přičte pokus; DLQ má být přehled, ne changelog.
                FailedJobs.update({ FailedJobs.id eq open.id }) {
                    it[attempts] = open.attempts + 1
                    it[lastFailedAt] = failedAt
                    it[FailedJobs.errorClass] = errorClass
                    it[FailedJobs.errorMessage] = errorMessage
                }
                open.copy(
                    attempts = open.attempts + 1,
                    lastFailedAt = failedAt,
                    errorClass = errorClass,
                    errorMessage = errorMessage,
                )
            }
        }

    override fun resolve(
        taskName: String,
        taskInstance: String,
        resolvedAt: Instant,
    ): Boolean =
        transaction(database) {
            FailedJobs.update({ openScope(taskName, taskInstance) }) {
                it[FailedJobs.resolvedAt] = resolvedAt
            } > 0
        }

    override fun listOpen(limit: Int): List<FailedJob> =
        transaction(database) {
            FailedJobs
                .selectAll()
                .where { FailedJobs.resolvedAt.isNull() }
                .orderBy(FailedJobs.lastFailedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toFailedJob() }
        }

    override fun listOpenByOrg(
        orgId: OrganizationId,
        limit: Int,
    ): List<FailedJob> =
        transaction(database) {
            FailedJobs
                .selectAll()
                .where { (FailedJobs.orgId eq orgId) and FailedJobs.resolvedAt.isNull() }
                .orderBy(FailedJobs.lastFailedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toFailedJob() }
        }

    private fun openScope(
        taskName: String,
        taskInstance: String,
    ) = (FailedJobs.taskName eq taskName) and
        (FailedJobs.taskInstance eq taskInstance) and
        FailedJobs.resolvedAt.isNull()
}

class ExposedBackupRunRepository(
    private val database: ExposedDatabase,
) : BackupRunRepository {
    override fun record(run: BackupRun): BackupRun =
        transaction(database) {
            BackupRuns.insert {
                it[id] = run.id
                it[startedAt] = run.startedAt
                it[finishedAt] = run.finishedAt
                it[status] = run.status
                it[location] = run.location
                it[sizeBytes] = run.sizeBytes
                it[checksum] = run.checksum
                // Chyba se ukazuje člověku v konzoli; dlouhý stack trace by tabulku jen zaplevelil.
                it[error] = run.error?.take(ERROR_LIMIT)
            }
            run
        }

    override fun lastSuccessful(): BackupRun? =
        transaction(database) {
            BackupRuns
                .selectAll()
                .where { BackupRuns.status eq BackupStatus.SUCCEEDED }
                .orderBy(BackupRuns.finishedAt to SortOrder.DESC)
                .firstOrNull()
                ?.toBackupRun()
        }

    override fun listRecent(limit: Int): List<BackupRun> =
        transaction(database) {
            BackupRuns
                .selectAll()
                .orderBy(BackupRuns.finishedAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toBackupRun() }
        }

    private companion object {
        const val ERROR_LIMIT = 2000
    }
}
