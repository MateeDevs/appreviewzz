package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.usecase.AnalyzeReviewsUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Serializable
data class AnalysisJobData(
    val orgId: String,
    val appId: String,
)

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
    private val clock: Clock = Clock.System,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
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

    /**
     * Zařadí dotagování appky. Instance je `appId`, takže se běhy neduplikují: když už jedna
     * dávka čeká, druhý ingest jen potvrdí, že se na ni čeká.
     */
    fun schedule(
        client: SchedulerClient,
        orgId: OrganizationId,
        appId: AppId,
    ) {
        client.scheduleIfNotExists(
            analyzeTask.instance(appId.toString(), AnalysisJobData(orgId.toString(), appId.toString())),
            Instant.now(),
        )
    }

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

        return report.hasMore
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
    ) {
        val data = instance.data
        logger.warn { "Rozbor appky ${data.appId} selhal, jde do DLQ: $message" }
        failedJobs.record(
            taskName = ANALYZE_TASK,
            taskInstance = instance.id,
            orgId = OrganizationId.parse(data.orgId),
            payload = "app=${data.appId}",
            errorClass = (cause ?: IllegalStateException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val ANALYZE_TASK = "analyze-app"

        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofMinutes(1)
        val NEXT_BATCH_DELAY: Duration = Duration.ofSeconds(10)
        const val DEFAULT_RETRIES = 3
        private const val BACKOFF_RATE = 3.0
    }
}
