package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.core.port.BackupRunRepository
import cz.matee.appreviewzz.core.port.DatabaseBackup
import cz.matee.appreviewzz.core.port.FailedJobRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Naplánovaná záloha databáze (F1.8). Jedna instance na celý systém — zálohuje se databáze,
 * ne tenant, takže se úloha plánuje sama při startu jako sweep.
 *
 * Retry se schválně nedělá: když noční záloha selže, další pokus za pár minut narazí na tutéž
 * příčinu (plný disk, chybějící klíč do S3). Záznam v DLQ zůstane otevřený až do prvního
 * úspěšného běhu, takže selhání nezapadne.
 */
class BackupJobs(
    private val backup: DatabaseBackup,
    private val backupRuns: BackupRunRepository,
    private val failedJobs: FailedJobRepository,
    schedule: Schedule = dailyAt(DEFAULT_TIME, ZoneId.of("UTC")),
    private val clock: Clock = Clock.System,
) {
    val backupTask: RecurringTask<Void> =
        Tasks
            .recurring(BACKUP_TASK, schedule)
            .execute { _, _ -> runBackup() }

    private fun runBackup() {
        val run = backup.backupNow()
        when (run.status) {
            BackupStatus.SUCCEEDED -> {
                logger.info { "Záloha uložena do ${run.location}" }
                failedJobs.resolve(BACKUP_TASK, RecurringTask.INSTANCE, clock.now())
            }

            BackupStatus.FAILED ->
                failedJobs.record(
                    taskName = BACKUP_TASK,
                    taskInstance = RecurringTask.INSTANCE,
                    orgId = null,
                    payload = null,
                    errorClass = null,
                    errorMessage = run.error,
                    failedAt = run.finishedAt,
                )
        }
    }

    /** Stáří poslední úspěšné zálohy v sekundách pro metriku; bez záznamu vrací `null`. */
    fun lastSuccessAgeSeconds(): Double? = backupRuns.lastSuccessful()?.let { (clock.now() - it.finishedAt).inWholeSeconds.toDouble() }

    companion object {
        const val BACKUP_TASK = "backup-database"

        /** Nad ránem v UTC — mimo špičku klientů a před ranními digesty (F4). */
        val DEFAULT_TIME: LocalTime = LocalTime.of(2, 30)

        fun dailyAt(
            time: LocalTime,
            zone: ZoneId,
        ): Schedule = Schedules.daily(zone, time)

        /** `HH:MM` z konfigurace; nesrozumitelná hodnota shodí start, ne až noční zálohu. */
        fun parseTime(raw: String): LocalTime {
            val parts = raw.split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull()
            val minute = parts.getOrNull(1)?.toIntOrNull()
            require(parts.size == 2 && hour != null && minute != null) {
                "Čas zálohy se zadává jako HH:MM, dostal jsem '$raw'"
            }
            return LocalTime.of(hour, minute)
        }
    }
}
