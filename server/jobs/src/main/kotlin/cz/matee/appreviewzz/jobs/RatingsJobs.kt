package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_RESCHEDULE
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
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
import cz.matee.appreviewzz.core.usecase.DailyRatingsUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZoneId
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Selhání storu, u kterého má smysl zkusit přehled znovu ještě dnes. */
class RetryableRatingsException(
    message: String,
) : RuntimeException(message)

/**
 * Payload denního přehledu. Nese **čas i zónu aplikace**, protože právě z nich se skládá
 * cron výraz instance: klient v Praze má digest v 8:30 pražského času, ne serverového.
 * Dnešní n8n má čas natvrdo v dispatcheru a zónu podle instance — pro klienta mimo Prahu
 * to znamená přehled v nesmyslnou hodinu.
 */
@Serializable
data class RatingsJobData(
    val orgId: String,
    val appId: String,
    /** `HH:MM` v zóně aplikace. */
    val at: String,
    val timezone: String,
) : ScheduleAndData {
    override fun getSchedule(): Schedule {
        val (hour, minute) = at.split(':').let { it[0].toInt() to it[1].toInt() }
        // db-scheduler používá šestidílný cron se sekundami; pětidílný odmítne za běhu.
        return Schedules.cron("0 $minute $hour * * *", zoneId())
    }

    override fun getData(): Any = this

    /** Neznámá zóna nesmí shodit plánovač — v nejhorším chodí přehled v UTC. */
    private fun zoneId(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    companion object {
        fun of(app: App): RatingsJobData =
            RatingsJobData(
                orgId = app.orgId.toString(),
                appId = app.id.toString(),
                at = "%02d:%02d".format(app.dailyDigestAt.hour, app.dailyDigestAt.minute),
                timezone = app.timezone,
            )
    }
}

/**
 * Denní přehled hodnocení jako naplánovaná úloha ([ADR 0004](../../../../../../../docs/adr/0004-db-scheduler.md)):
 *
 * - **`ratings-app`** — jedna instance na appku, cron podle jejího času a zóny.
 * - **`ratings-sweep`** — sesouhlasí naplánované instance se seznamem zapnutých aplikací,
 *   stejně jako u ingestu. Změna času digestu v consoli se propíše bez restartu.
 *
 * Opakované spuštění je bezpečné: snapshot je idempotentní upsert a druhý přehled téhož dne
 * zastaví rezervace v `ratings_digest`.
 */
class RatingsJobs(
    private val ratings: DailyRatingsUseCase,
    private val apps: AppRepository,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
) {
    val ratingsTask: RecurringTaskWithPersistentSchedule<RatingsJobData> =
        Tasks
            .recurringWithPersistentSchedule(RATINGS_TASK, RatingsJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<RatingsJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUpAndKeepCadence),
            ).execute { instance, _ -> runRatings(instance) }

    val sweepTask: RecurringTask<Void> =
        Tasks
            .recurring(SWEEP_TASK, Schedules.fixedDelay(sweepInterval))
            .execute { _, context -> sweep(context.schedulerClient) }

    /** Naschvál nesahá na `execution_time` u instancí, které jsou v pořádku — jinak by se digest odsouval. */
    fun sweep(client: SchedulerClient) {
        val wanted = apps.listEnabled().associate { it.id.toString() to RatingsJobData.of(it) }
        val scheduled =
            client
                .getScheduledExecutionsForTask(RATINGS_TASK, RatingsJobData::class.java)
                .associateBy { it.taskInstance.id }

        wanted.forEach { (instanceId, data) ->
            val existing = scheduled[instanceId]
            when {
                existing == null -> {
                    logger.info { "Plánuji přehled hodnocení appky $instanceId na ${data.at} (${data.timezone})" }
                    client.schedule(ratingsTask.schedulableInstance(instanceId, data), WHEN_EXISTS_DO_NOTHING)
                }

                existing.data != data -> {
                    logger.info { "Appka $instanceId má nový čas přehledu, přeplánovávám" }
                    client.schedule(ratingsTask.schedulableInstance(instanceId, data), WHEN_EXISTS_RESCHEDULE)
                }

                else -> Unit
            }
        }

        scheduled.values
            .filter { it.taskInstance.id !in wanted.keys && !it.isPicked }
            .forEach { execution ->
                logger.info { "Ruším naplánovaný přehled hodnocení appky ${execution.taskInstance.id}" }
                client.cancel(execution.taskInstance)
            }
    }

    private fun runRatings(instance: TaskInstance<RatingsJobData>) {
        val data = instance.data
        val report = runBlocking { ratings.run(OrganizationId.parse(data.orgId), AppId.parse(data.appId)) }

        when {
            report.skipped != null ->
                logger.info { "Přehled hodnocení appky ${data.appId} přeskočen: ${report.skipped}" }

            report.isRetryable -> throw RetryableRatingsException(report.failureSummary())

            report.failures.isNotEmpty() ->
                // Trvalá chyba (odvolaný přístup k bucketu): retry by tloukl do storu, ale
                // ops o tom vědět musí, jinak klientovi jen tiše zmizí polovina přehledu.
                recordFailure(instance, report.failureSummary())

            report.deliveries.any { it.error != null } ->
                recordFailure(instance, report.deliveries.mapNotNull { it.error }.joinToString())

            else -> failedJobs.resolve(RATINGS_TASK, instance.id, clock.now())
        }
    }

    /**
     * Po vyčerpaných pokusech se úloha **znovu naplánuje** na zítřek. Výchozí `thenRemove()`
     * by appku odstřihl od přehledů natrvalo a nikdo by si toho nemusel všimnout týdny.
     */
    private fun giveUpAndKeepCadence(
        complete: ExecutionComplete,
        operations: ExecutionOperations<RatingsJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<RatingsJobData>
        val cause = complete.cause.orElse(null)
        recordFailure(instance, cause?.message ?: "neznámá chyba", cause)
        operations.reschedule(complete, instance.data.schedule.getNextExecutionTime(complete))
    }

    private fun recordFailure(
        instance: TaskInstance<RatingsJobData>,
        message: String,
        cause: Throwable? = null,
    ) {
        val data = instance.data
        logger.warn { "Přehled hodnocení appky ${data.appId} selhal, jde do DLQ: $message" }
        failedJobs.record(
            taskName = RATINGS_TASK,
            taskInstance = instance.id,
            orgId = OrganizationId.parse(data.orgId),
            payload = "app=${data.appId}",
            errorClass = (cause ?: RetryableRatingsException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val RATINGS_TASK = "ratings-app"
        const val SWEEP_TASK = "ratings-sweep"

        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(5)
        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofMinutes(10)
        const val DEFAULT_RETRIES = 3
        private const val BACKOFF_RATE = 2.0
    }
}
