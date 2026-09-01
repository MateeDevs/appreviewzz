package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.logging.LogLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
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
    deliveryJobs: DeliveryJobs? = null,
    replyJobs: ReplyJobs? = null,
    backupJobs: BackupJobs? = null,
    ratingsJobs: RatingsJobs? = null,
    maintenanceJobs: MaintenanceJobs? = null,
    refreshRepliesJobs: RefreshRepliesJobs? = null,
    revalidateCredentialsJobs: RevalidateCredentialsJobs? = null,
    config: SchedulerConfig = SchedulerConfig(),
): Scheduler {
    logger.info { "Scheduler: ${config.threads} vláken, polling po ${config.pollingInterval}" }
    // Sweep i záloha se plánují samy; ostatní úlohy zakládá až sweep podle stavu databáze.
    val selfScheduling =
        listOfNotNull(
            jobs.sweepTask,
            backupJobs?.backupTask,
            ratingsJobs?.sweepTask,
            maintenanceJobs?.cleanupTask,
            refreshRepliesJobs?.refreshTask,
            revalidateCredentialsJobs?.revalidateTask,
        )
    val knownTasks =
        listOfNotNull(jobs.ingestTask, deliveryJobs?.deliverTask, replyJobs?.publishTask, ratingsJobs?.ratingsTask)
    return Scheduler
        .create(dataSource, knownTasks)
        .threads(config.threads)
        .pollingInterval(config.pollingInterval)
        .serializer(JsonTaskSerializer)
        .commitWhenAutocommitDisabled(true)
        // startTasks registruje úlohy i jako známé tasky — do create() proto nepatří podruhé.
        .startTasks(selfScheduling)
        .failureLogging(LogLevel.WARN, true)
        .build()
}

/**
 * Klient fronty bez plánovače. Používá ho role `api`: webhook potřebuje **jen zařadit úlohu**
 * a hned odpovědět (Slack čeká na potvrzení do tří sekund), zpracování patří workeru.
 * Fronta je tabulka v Postgresu, takže si obě role vystačí bez brokera i bez volání mezi sebou.
 */
fun buildSchedulerClient(
    dataSource: DataSource,
    replyJobs: ReplyJobs,
): SchedulerClient =
    SchedulerClient.Builder
        .create(AutoCommitDataSource(dataSource), listOf(replyJobs.publishTask))
        .serializer(JsonTaskSerializer)
        .build()

/**
 * Spojení se zapnutým autocommitem. Náš pool ho má kvůli Exposedu vypnutý; plánovač na to má
 * přepínač (`commitWhenAutocommitDisabled`), ale **klient fronty ho nemá** — bez tohohle obalu
 * by se zařazená úloha nikdy nepotvrdila a webhook by odpovědi tiše zahazoval. Hikari při
 * vrácení spojení do poolu autocommit stejně resetuje, takže se nastavení nikam nešíří.
 */
private class AutoCommitDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    override fun getConnection(): Connection = delegate.connection.apply { autoCommit = true }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = delegate.getConnection(username, password).apply { autoCommit = true }
}
