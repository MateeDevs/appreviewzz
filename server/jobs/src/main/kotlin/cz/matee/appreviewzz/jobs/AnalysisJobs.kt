package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions.WHEN_EXISTS_RESCHEDULE
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule
import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.model.AnalysisCadence
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.usecase.AnalyzeReviewsUseCase
import cz.matee.appreviewzz.core.usecase.ScheduledAnalysisUseCase
import cz.matee.appreviewzz.core.usecase.SpikeAlertUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Serializable
data class AnalysisJobData(
    val orgId: String,
    val appId: String,
)

/**
 * Payload rozboru. Nese kadenci, den, čas i zónu aplikace, protože právě z nich se skládá
 * cron instance — stejně jako u denního přehledu hodnocení. Změna kteréhokoli z nich změní
 * payload a sweep instanci přeplánuje.
 */
@Serializable
data class WeeklyAnalysisJobData(
    val orgId: String,
    val appId: String,
    /** ISO den v týdnu, 1 = pondělí. U měsíční kadence se neuplatní. */
    val day: Int,
    /** `HH:MM` v zóně aplikace. */
    val at: String,
    val timezone: String,
    /** Výchozí hodnota drží zpětnou kompatibilitu payloadů zapsaných před kadencí. */
    val cadence: AnalysisCadence = AnalysisCadence.WEEKLY,
) : ScheduleAndData {
    override fun getSchedule(): Schedule {
        val (hour, minute) = at.split(':').let { it[0].toInt() to it[1].toInt() }
        // Šestidílný cron se sekundami. Den se píše **jménem**, ne číslem: číslování dne
        // v týdnu se mezi dialekty cronu liší (Quartz má 1 = neděle, jiné 1 = pondělí)
        // a rozbor o den vedle by si nikdo nevšiml, dokud si klient nestěžuje.
        return when (cadence) {
            AnalysisCadence.WEEKLY -> {
                val cronDay = DAY_NAMES[(day - 1).coerceIn(0, WEEK_DAYS - 1)]
                Schedules.cron("0 $minute $hour ? * $cronDay", zoneId())
            }

            // Prvního v měsíci: minulý měsíc je tím pádem celý a rozbor za něj sedí.
            AnalysisCadence.MONTHLY -> Schedules.cron("0 $minute $hour 1 * ?", zoneId())
        }
    }

    override fun getData(): Any = this

    /** Neznámá zóna nesmí shodit plánovač — v nejhorším chodí rozbor v UTC. */
    private fun zoneId(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    companion object {
        private const val WEEK_DAYS = 7

        /** ISO pořadí: 1 = pondělí. */
        private val DAY_NAMES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        fun of(app: App): WeeklyAnalysisJobData =
            WeeklyAnalysisJobData(
                orgId = app.orgId.toString(),
                appId = app.id.toString(),
                day = app.weeklyDigestDay,
                at = "%02d:%02d".format(app.dailyDigestAt.hour, app.dailyDigestAt.minute),
                timezone = app.timezone,
                cadence = app.analysisCadence,
            )
    }
}

/**
 * Dotagování recenzí jako samostatná úloha (F8).
 *
 * Odděleně od ingestu i od doručení, ze stejného důvodu jako `deliver-review`: rozbor sahá
 * na AI, která umí být pomalá nezávisle na storu i na Slacku. Instance je **appka**, ne
 * recenze — tagování jede v dávkách po patnácti a jedna instance na recenzi by tu úsporu
 * zahodila.
 *
 * Dlouhý backfill se nedělá v jednom běhu: úloha zpracuje jednu dávku a když zbývá další
 * práce, naplánuje se za deset vteřin znovu. Historie se tak doplní, aniž by jedna appka
 * obsadila vlákno na hodinu.
 */
class AnalysisJobs(
    private val analyze: AnalyzeReviewsUseCase,
    private val failedJobs: FailedJobRepository,
    /** Týdenní rozbor do kanálu; `null` u procesů, které kanály neobsluhují. */
    private val scheduled: ScheduledAnalysisUseCase? = null,
    /**
     * Alert na výkyv (B4). Běží na konci dotagování, ne z vlastního plánovače: výkyv se
     * pozná až z výkladů a ty vznikají právě tady. `null` u procesů bez kanálů.
     */
    private val spikes: SpikeAlertUseCase? = null,
    private val apps: AppRepository? = null,
    private val clock: Clock = Clock.System,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
    private val sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    /** Kolik recenzí spolkne jeden běh, než se úloha přeplánuje. */
    private val batchLimit: Int = AnalyzeReviewsUseCase.DEFAULT_LIMIT,
) {
    /**
     * Vlastní task, ne `oneTime`: po doběhnutí dávky se úloha buď smaže, nebo **přeplánuje
     * na další dávku**. Přeplánovat běžící instanci zevnitř jejího vlastního běhu nejde —
     * o pokračování rozhoduje až completion handler, kterému plánovač předá řízení potom.
     */
    val analyzeTask: CustomTask<AnalysisJobData> =
        Tasks
            .custom(ANALYZE_TASK, AnalysisJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<AnalysisJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUp),
            ).execute { instance, _ ->
                val hasMore = runAnalyze(instance)
                CompletionHandler { complete, operations ->
                    if (hasMore) {
                        logger.info { "Rozbor appky ${instance.data.appId} pokračuje další dávkou" }
                        operations.reschedule(complete, Instant.now().plus(NEXT_BATCH_DELAY))
                    } else {
                        operations.stop()
                    }
                }
            }

    val weeklyTask: RecurringTaskWithPersistentSchedule<WeeklyAnalysisJobData> =
        Tasks
            .recurringWithPersistentSchedule(WEEKLY_TASK, WeeklyAnalysisJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<WeeklyAnalysisJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUpAndKeepCadence),
            ).execute { instance, _ -> runWeekly(instance) }

    val weeklySweepTask: RecurringTask<Void> =
        Tasks
            .recurring(WEEKLY_SWEEP_TASK, Schedules.fixedDelay(sweepInterval))
            .execute { _, context -> sweepWeekly(context.schedulerClient) }

    /**
     * Sesouhlasí naplánované rozbory se seznamem zapnutých aplikací — nová appka se rozjede
     * bez restartu a změna dne či času se propíše. Instance v pořádku se nepřeplánovávají,
     * jinak by se rozbor odsouval donekonečna.
     */
    fun sweepWeekly(client: SchedulerClient) {
        val repository = apps ?: return
        val wanted = repository.listEnabled().associate { it.id.toString() to WeeklyAnalysisJobData.of(it) }
        val scheduled =
            client
                .getScheduledExecutionsForTask(WEEKLY_TASK, WeeklyAnalysisJobData::class.java)
                .associateBy { it.taskInstance.id }

        wanted.forEach { (instanceId, data) ->
            val existing = scheduled[instanceId]
            when {
                existing == null -> {
                    logger.info { "Plánuji týdenní rozbor appky $instanceId na den ${data.day} v ${data.at}" }
                    client.schedule(weeklyTask.schedulableInstance(instanceId, data), WHEN_EXISTS_DO_NOTHING)
                }

                existing.data != data -> {
                    logger.info { "Appka $instanceId má nový čas rozboru, přeplánovávám" }
                    client.schedule(weeklyTask.schedulableInstance(instanceId, data), WHEN_EXISTS_RESCHEDULE)
                }

                else -> Unit
            }
        }

        scheduled.values
            .filter { it.taskInstance.id !in wanted.keys && !it.isPicked }
            .forEach { execution ->
                logger.info { "Ruším naplánovaný rozbor appky ${execution.taskInstance.id}" }
                client.cancel(execution.taskInstance)
            }
    }

    private fun runWeekly(instance: TaskInstance<WeeklyAnalysisJobData>) {
        val data = instance.data
        val useCase = scheduled ?: return
        val report = runBlocking { useCase.run(OrganizationId.parse(data.orgId), AppId.parse(data.appId)) }

        when {
            report.skipped != null -> logger.info { "Týdenní rozbor appky ${data.appId} přeskočen: ${report.skipped}" }

            report.deliveries.any { it.error != null } ->
                recordFailure(WEEKLY_TASK, instance.id, data.orgId, data.appId, report.failureSummary())

            else -> failedJobs.resolve(WEEKLY_TASK, instance.id, clock.now())
        }
    }

    /**
     * Po vyčerpaných pokusech se rozbor **znovu naplánuje** na příští týden. Výchozí
     * `thenRemove()` by appku od rozborů odstřihl natrvalo a nikdo by si toho nevšiml.
     */
    private fun giveUpAndKeepCadence(
        complete: ExecutionComplete,
        operations: ExecutionOperations<WeeklyAnalysisJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<WeeklyAnalysisJobData>
        val data = instance.data
        val cause = complete.cause.orElse(null)
        recordFailure(WEEKLY_TASK, instance.id, data.orgId, data.appId, cause?.message ?: "neznámá chyba", cause)
        operations.reschedule(complete, data.schedule.getNextExecutionTime(complete))
    }

    /**
     * Zařadí dotagování appky. Instance je `appId`, takže se běhy neduplikují: když už jedna
     * dávka čeká, druhý ingest jen potvrdí, že se na ni čeká. Vrací `false` právě v tom
     * případě — konzole to říká větou, ne chybou.
     */
    fun schedule(
        client: SchedulerClient,
        orgId: OrganizationId,
        appId: AppId,
    ): Boolean =
        client.scheduleIfNotExists(
            analyzeTask.instance(appId.toString(), AnalysisJobData(orgId.toString(), appId.toString())),
            Instant.now(),
        )

    /** Vrací `true`, když zbývá další dávka — pokračování zařídí completion handler. */
    private fun runAnalyze(instance: TaskInstance<AnalysisJobData>): Boolean {
        val data = instance.data
        val report =
            runBlocking {
                analyze.analyzeMissing(OrganizationId.parse(data.orgId), AppId.parse(data.appId), batchLimit)
            }

        when {
            report.unavailable ->
                // Instalace bez AI. Není to chyba a nemá smysl to opakovat — až se klíč
                // nastaví, rozbory rozjede další ingest.
                logger.info { "Rozbor appky ${data.appId} přeskočen: AI není nastavená" }

            report.error != null && report.analyzed == 0 -> recordFailure(instance, report.error!!)

            else -> failedJobs.resolve(ANALYZE_TASK, instance.id, clock.now())
        }

        // Alert až po poslední dávce: uprostřed backfillu by se „dnešek" počítal z půlky
        // dotagovaných recenzí a příště by už dedup na den druhou zprávu nepustil.
        if (!report.hasMore && report.analyzed > 0) checkSpikes(data)
        return report.hasMore
    }

    /** Selhání alertu nesmí shodit dotagování — recenze mají výklad, o to jde především. */
    private fun checkSpikes(data: AnalysisJobData) {
        val useCase = spikes ?: return
        runCatching {
            runBlocking { useCase.run(OrganizationId.parse(data.orgId), AppId.parse(data.appId)) }
        }.onFailure { error ->
            logger.warn(error) { "Kontrola výkyvu u appky ${data.appId} selhala" }
        }
    }

    private fun giveUp(
        complete: ExecutionComplete,
        operations: ExecutionOperations<AnalysisJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<AnalysisJobData>
        val cause = complete.cause.orElse(null)
        recordFailure(instance, cause?.message ?: "neznámá chyba", cause)
        // Recenze zůstanou bez výkladu a doplní je další ingest — nic se neztratí.
        operations.stop()
    }

    private fun recordFailure(
        instance: TaskInstance<AnalysisJobData>,
        message: String,
        cause: Throwable? = null,
    ) = recordFailure(ANALYZE_TASK, instance.id, instance.data.orgId, instance.data.appId, message, cause)

    @Suppress("LongParameterList")
    private fun recordFailure(
        task: String,
        instanceId: String,
        orgId: String,
        appId: String,
        message: String,
        cause: Throwable? = null,
    ) {
        logger.warn { "Úloha $task appky $appId selhala, jde do DLQ: $message" }
        failedJobs.record(
            taskName = task,
            taskInstance = instanceId,
            orgId = OrganizationId.parse(orgId),
            payload = "app=$appId",
            errorClass = (cause ?: IllegalStateException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val ANALYZE_TASK = "analyze-app"
        const val WEEKLY_TASK = "weekly-analysis-app"
        const val WEEKLY_SWEEP_TASK = "weekly-analysis-sweep"

        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(5)

        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofMinutes(1)
        val NEXT_BATCH_DELAY: Duration = Duration.ofSeconds(10)
        const val DEFAULT_RETRIES = 3
        private const val BACKOFF_RATE = 3.0
    }
}
