package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.logging.LogLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

data class SchedulerConfig(
    val threads: Int = DEFAULT_THREADS,
    val pollingInterval: Duration = DEFAULT_POLLING_INTERVAL,
) {
    companion object {
        const val DEFAULT_THREADS = 5
        val DEFAULT_POLLING_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}

/**
 * Scheduler nad naším poolem. Dvě věci, které se nesmí zapomenout:
 *
 * - **`commitWhenAutocommitDisabled(true)`** — Hikari máme s vypnutým autocommitem kvůli Exposedu,
 *   bez tohohle by db-scheduler své zápisy nikdy nepotvrdil a fronta by se tvářila jako prázdná.
 * - **`startTasks(…)`** — sweep a noční záloha se plánují samy; všechno ostatní
 *   zakládá až sweep podle stavu databáze.
 */
fun buildScheduler(
    dataSource: DataSource,
    jobs: IngestJobs,
    backupJobs: BackupJobs? = null,
    config: SchedulerConfig = SchedulerConfig(),
): Scheduler {
    logger.info { "Scheduler: ${config.threads} vláken, polling po ${config.pollingInterval}" }
    // Sweep i záloha se plánují samy; ostatní úlohy zakládá až sweep podle stavu databáze.
    val selfScheduling = listOfNotNull(jobs.sweepTask, backupJobs?.backupTask)
    return Scheduler
        .create(dataSource, listOf(jobs.ingestTask))
        .threads(config.threads)
        .pollingInterval(config.pollingInterval)
        .serializer(JsonTaskSerializer)
        .commitWhenAutocommitDisabled(true)
        // startTasks registruje úlohy i jako známé tasky — do create() proto nepatří podruhé.
        .startTasks(selfScheduling)
        .failureLogging(LogLevel.WARN, true)
        .build()
}
