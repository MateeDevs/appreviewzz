package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_RESCHEDULE
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule
import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.usecase.HistoryImportReport
import cz.matee.appreviewzz.core.usecase.ImportReviewHistoryUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Selhání Cloud Storage, u kterého má smysl zkusit import znovu. */
class RetryableHistoryException(
    message: String,
) : RuntimeException(message)

/**
 * Payload průběžného importu historie. Nese čas i zónu appky ze stejného důvodu jako denní
 * přehled: import běží **hodinu před** ním, aby týdenní rozbor toho dne už viděl i to,
 * co přišlo archivem.
 */
@Serializable
data class ReviewHistoryJobData(
    val orgId: String,
    val appId: String,
    /** `HH:MM` v zóně aplikace — už posunuté o hodinu dozadu proti dennímu přehledu. */
    val at: String,
    val timezone: String,
) : ScheduleAndData {
    override fun getSchedule(): Schedule {
        val (hour, minute) = at.split(':').let { it[0].toInt() to it[1].toInt() }
        // db-scheduler chce šestidílný cron se sekundami; pětidílný odmítne až za běhu.
        return Schedules.cron("0 $minute $hour * * *", zoneId())
    }

    override fun getData(): Any = this

    private fun zoneId(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    companion object {
        private const val HOURS_PER_DAY = 24

        fun of(app: App): ReviewHistoryJobData {
            val hour = (app.dailyDigestAt.hour - 1 + HOURS_PER_DAY) % HOURS_PER_DAY
            return ReviewHistoryJobData(
                orgId = app.orgId.toString(),
                appId = app.id.toString(),
                at = "%02d:%02d".format(hour, app.dailyDigestAt.minute),
                timezone = app.timezone,
            )
        }
    }
}

/** Payload jednorázového dotažení historie — po přidání appky nebo z konzole. */
@Serializable
data class ReviewHistoryBackfillData(
    val orgId: String,
    val appId: String,
    val months: Int,
)

/**
 * Import historie recenzí z reportingu Play Console (A10).
 *
 * - **`reviews-history-app`** — denně, jedna instance na appku. Není to jen doplňování
 *   minulosti: export je jediné místo, kde jsou **hodnocení bez textu**, takže bez denního
 *   běhu by řada měla historii bohatší než živé období.
 * - **`reviews-history-sweep`** — sesouhlasí instance se seznamem appek, které mají Google
 *   Play i bucket. Appka, které bucket přibyl, se rozjede sama.
 * - **`reviews-history-backfill`** — jednorázové dotažení `history_months` zpátky.
 *
 * Opakovaný běh nic nezduplikuje: dedup drží ID z exportu a párování přes čas odeslání
 * ([ImportReviewHistoryUseCase]).
 */
class ReviewHistoryJobs(
    private val history: ImportReviewHistoryUseCase,
    private val apps: AppRepository,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
) {
    val historyTask: RecurringTaskWithPersistentSchedule<ReviewHistoryJobData> =
        Tasks
            .recurringWithPersistentSchedule(HISTORY_TASK, ReviewHistoryJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<ReviewHistoryJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUpAndKeepCadence),
            ).execute { instance, _ -> runDaily(instance) }

    val backfillTask: OneTimeTask<ReviewHistoryBackfillData> =
        Tasks
            .oneTime(BACKFILL_TASK, ReviewHistoryBackfillData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<ReviewHistoryBackfillData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(FailureHandler.OnFailureRetryLater(firstRetryDelay)),
            ).execute { instance, _ -> runBackfill(instance) }

    val sweepTask: RecurringTask<Void> =
        Tasks
            .recurring(SWEEP_TASK, Schedules.fixedDelay(sweepInterval))
            .execute { _, context -> sweep(context.schedulerClient) }

    /**
     * Naplánuje import jen appkám, které na archiv dosáhnou: bez Play Console bucketu není
     * co číst a instance navíc by jen každý den zapisovala „přeskočeno".
     */
    fun sweep(client: SchedulerClient) {
        val wanted =
            apps
                .listEnabled()
                .filter { it.gpPackageName != null && it.gpReportingBucket != null }
                .associate { it.id.toString() to ReviewHistoryJobData.of(it) }
        val scheduled =
            client
                .getScheduledExecutionsForTask(HISTORY_TASK, ReviewHistoryJobData::class.java)
                .associateBy { it.taskInstance.id }

        wanted.forEach { (instanceId, data) ->
            val existing = scheduled[instanceId]
            when {
                existing == null -> {
                    logger.info { "Plánuji import historie appky $instanceId na ${data.at} (${data.timezone})" }
                    client.schedule(historyTask.schedulableInstance(instanceId, data), WHEN_EXISTS_DO_NOTHING)
                }

                existing.data != data -> {
                    logger.info { "Appka $instanceId má nový čas importu historie, přeplánovávám" }
                    client.schedule(historyTask.schedulableInstance(instanceId, data), WHEN_EXISTS_RESCHEDULE)
                }

                else -> Unit
            }
        }

        scheduled.values
            .filter { it.taskInstance.id !in wanted.keys && !it.isPicked }
            .forEach { execution ->
                logger.info { "Ruším import historie appky ${execution.taskInstance.id}" }
                client.cancel(execution.taskInstance)
            }
    }

    /**
     * Zařadí jednorázové dotažení historie. Instance je `appId`, takže dvojí kliknutí
     * (nebo přidání appky a hned změna bucketu) nespustí dva běhy nad týmiž soubory.
     */
    fun scheduleBackfill(
        client: SchedulerClient,
        orgId: OrganizationId,
        appId: AppId,
        months: Int,
    ): Boolean =
        client.scheduleIfNotExists(
            backfillTask.instance(appId.toString(), ReviewHistoryBackfillData(orgId.toString(), appId.toString(), months)),
            Instant.now(),
        )

    private fun runDaily(instance: TaskInstance<ReviewHistoryJobData>) {
        val data = instance.data
        val report =
            runBlocking { history.run(OrganizationId.parse(data.orgId), AppId.parse(data.appId)) }
        handle(HISTORY_TASK, instance.id, data.orgId, data.appId, report)
    }

    private fun runBackfill(instance: TaskInstance<ReviewHistoryBackfillData>) {
        val data = instance.data
        val report =
            runBlocking { history.run(OrganizationId.parse(data.orgId), AppId.parse(data.appId), data.months) }
        handle(BACKFILL_TASK, instance.id, data.orgId, data.appId, report)
    }

    private fun handle(
        task: String,
        instanceId: String,
        orgId: String,
        appId: String,
        report: HistoryImportReport,
    ) {
        when {
            report.skipped != null -> logger.info { "Import historie appky $appId přeskočen: ${report.skipped}" }

            report.isRetryable -> throw RetryableHistoryException(report.failureSummary())

            // Trvalá chyba (odebraná role na bucketu) se retry nespraví, ale ops o ní vědět musí:
            // klientovi by jinak jen tiše přestala růst historie.
            report.failures.isNotEmpty() -> recordFailure(task, instanceId, orgId, appId, report.failureSummary())

            else -> failedJobs.resolve(task, instanceId, clock.now())
        }
    }

    /** Po vyčerpaných pokusech se import naplánuje na zítřek — jinak by appka vypadla natrvalo. */
    private fun giveUpAndKeepCadence(
        complete: ExecutionComplete,
        operations: ExecutionOperations<ReviewHistoryJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<ReviewHistoryJobData>
        val data = instance.data
        val cause = complete.cause.orElse(null)
        recordFailure(HISTORY_TASK, instance.id, data.orgId, data.appId, cause?.message ?: "neznámá chyba", cause)
        operations.reschedule(complete, data.schedule.getNextExecutionTime(complete))
    }

    @Suppress("LongParameterList")
    private fun recordFailure(
        task: String,
        instanceId: String,
        orgId: String,
        appId: String,
        message: String,
        cause: Throwable? = null,
    ) {
        logger.warn { "Import historie appky $appId selhal, jde do DLQ: $message" }
        failedJobs.record(
            taskName = task,
            taskInstance = instanceId,
            orgId = OrganizationId.parse(orgId),
            payload = "app=$appId",
            errorClass = (cause ?: RetryableHistoryException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val HISTORY_TASK = "reviews-history-app"
        const val SWEEP_TASK = "reviews-history-sweep"
        const val BACKFILL_TASK = "reviews-history-backfill"

        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(5)
        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofMinutes(10)
        const val DEFAULT_RETRIES = 3
        private const val BACKOFF_RATE = 2.0
    }
}
