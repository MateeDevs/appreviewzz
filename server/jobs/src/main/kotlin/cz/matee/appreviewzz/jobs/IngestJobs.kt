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
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.usecase.IngestReport
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Chyba storu, u které má smysl zkusit to dřív než za celý interval appky. */
class RetryableIngestException(
    message: String,
) : RuntimeException(message)

/**
 * Payload úlohy ingestu. Nese i **vlastní periodu** — db-scheduler umí per-instance schedule,
 * takže si každá appka drží svůj `ingest_interval_minutes` bez toho, aby se pro každou zakládal
 * vlastní task (přesně to, co je dnes v n8n rozkopírované do dispatcher workflows).
 */
@Serializable
data class IngestJobData(
    val orgId: String,
    val appId: String,
    val intervalMinutes: Int,
) : ScheduleAndData {
    override fun getSchedule(): Schedule = FixedDelay.of(Duration.ofMinutes(intervalMinutes.toLong()))

    override fun getData(): Any = this

    companion object {
        fun of(app: App): IngestJobData =
            IngestJobData(
                orgId = app.orgId.toString(),
                appId = app.id.toString(),
                intervalMinutes = app.ingestIntervalMinutes,
            )
    }
}

/**
 * Naplánovaný ingest ([ADR 0004](../../../../../../../docs/adr/0004-db-scheduler.md)):
 *
 * - **`ingest-app`** — jedna instance na appku, perioda z payloadu. Zamykání řeší db-scheduler,
 *   takže víc workerů si úlohy rozebere a jedna appka neběží dvakrát naráz.
 * - **`ingest-sweep`** — sesouhlasí naplánované instance se seznamem zapnutých aplikací:
 *   nová appka se rozjede bez restartu, vypnutá se odplánuje, změna periody se propíše.
 *   Onboarding klienta tak nepotřebuje žádný zásah do provozu.
 *
 * Zpracování je „at least once" a ingest je idempotentní upsert, takže opakovaný běh nevadí.
 */
class IngestJobs(
    private val ingest: IngestReviewsUseCase,
    private val apps: AppRepository,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
) {
    val ingestTask: RecurringTaskWithPersistentSchedule<IngestJobData> =
        Tasks
            .recurringWithPersistentSchedule(INGEST_TASK, IngestJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<IngestJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUpAndKeepCadence),
            ).execute { instance, _ -> runIngest(instance) }

    /** Sweep běží i na prázdné databázi — je to jediná úloha, která se plánuje sama při startu. */
    val sweepTask: RecurringTask<Void> =
        Tasks
            .recurring(SWEEP_TASK, Schedules.fixedDelay(sweepInterval))
            .execute { _, context -> sweep(context.schedulerClient) }

    /**
     * Sesouhlasí frontu se stavem v databázi. Naschvál nesahá na `execution_time` u instancí,
     * které jsou v pořádku — jinak by každý sweep odsunul ingest a ten by se nikdy nespustil.
     */
    fun sweep(client: SchedulerClient) {
        val wanted = apps.listEnabled().associate { it.id.toString() to IngestJobData.of(it) }
        val scheduled =
            client
                .getScheduledExecutionsForTask(INGEST_TASK, IngestJobData::class.java)
                .associateBy { it.taskInstance.id }

        wanted.forEach { (instanceId, data) ->
            val existing = scheduled[instanceId]
            when {
                existing == null -> {
                    logger.info { "Plánuji ingest appky $instanceId po ${data.intervalMinutes} min" }
                    client.schedule(ingestTask.schedulableInstance(instanceId, data), WHEN_EXISTS_DO_NOTHING)
                }

                existing.data != data -> {
                    logger.info { "Appka $instanceId má nové nastavení ingestu, přeplánovávám" }
                    client.schedule(ingestTask.schedulableInstance(instanceId, data), WHEN_EXISTS_RESCHEDULE)
                }

                else -> Unit
            }
        }

        // Vypnutá nebo smazaná appka: běžící instanci nechává doběhnout, zruší ji další sweep.
        scheduled.values
            .filter { it.taskInstance.id !in wanted.keys && !it.isPicked }
            .forEach { execution ->
                logger.info { "Ruším naplánovaný ingest appky ${execution.taskInstance.id}" }
                client.cancel(execution.taskInstance)
            }
    }

    private fun runIngest(instance: TaskInstance<IngestJobData>) {
        val data = instance.data
        val report =
            runBlocking {
                ingest.ingest(OrganizationId.parse(data.orgId), AppId.parse(data.appId))
            }

        when {
            report.appSkipped != null ->
                // Sweep instanci odplánuje; do příštího běhu ať to aspoň nepůsobí jako chyba.
                logger.info { "Ingest appky ${data.appId} přeskočen: ${report.appSkipped}" }

            report.isRetryable ->
                throw RetryableIngestException(report.failureSummary())

            report.failures.isNotEmpty() ->
                // Trvalá chyba (typicky neplatný klíč): retry by jen tloukl do storu, ale ops
                // o tom vědět musí. Kadence zůstává — až klient klíč opraví, ingest se rozjede sám.
                recordFailure(instance, report.failureSummary())

            else -> failedJobs.resolve(INGEST_TASK, instance.id, clock.now())
        }
    }

    /**
     * Poslední instance po vyčerpaných pokusech: úloha jde do DLQ, ale **znovu se naplánuje**
     * podle vlastní periody. Výchozí `maxRetries(…).thenRemove()` by appku odstřihl od ingestu
     * natrvalo — a nikdo by si toho nemusel všimnout týdny.
     */
    private fun giveUpAndKeepCadence(
        complete: ExecutionComplete,
        operations: ExecutionOperations<IngestJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<IngestJobData>
        val cause = complete.cause.orElse(null)
        recordFailure(instance, cause?.message ?: "neznámá chyba", cause)
        operations.reschedule(complete, instance.data.schedule.getNextExecutionTime(complete))
    }

    private fun recordFailure(
        instance: TaskInstance<IngestJobData>,
        message: String,
        cause: Throwable? = null,
    ) {
        val data = instance.data
        logger.warn { "Ingest appky ${data.appId} selhal, jde do DLQ: $message" }
        failedJobs.record(
            taskName = INGEST_TASK,
            taskInstance = instance.id,
            orgId = OrganizationId.parse(data.orgId),
            payload = "app=${data.appId}",
            errorClass = (cause ?: RetryableIngestException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val INGEST_TASK = "ingest-app"
        const val SWEEP_TASK = "ingest-sweep"

        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)
        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofMinutes(1)
        const val DEFAULT_RETRIES = 3
        private const val BACKOFF_RATE = 3.0
    }
}

private fun IngestReport.failureSummary(): String = failures.joinToString { "${it.platform}: ${it.kind} ${it.message}" }
