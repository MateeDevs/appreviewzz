package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.usecase.DeliverReviewUseCase
import cz.matee.appreviewzz.core.usecase.DeliveryReport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Selhání doručení, u kterého má smysl zkusit to znovu (limit Slacku, výpadek sítě). */
class RetryableDeliveryException(
    message: String,
) : RuntimeException(message)

/**
 * Payload doručení. Nese i **otisk znění**, protože právě ten dělá z úlohy jednoznačnou věc:
 * když autor recenzi mezitím přepíše, je to jiné doručení s vlastní úlohou, ne přeplánování
 * té staré.
 */
@Serializable
data class DeliveryJobData(
    val orgId: String,
    val reviewId: String,
    val contentHash: String,
)

/**
 * Doručení recenze do kanálů jako **samostatná jednorázová úloha** za ingestem.
 *
 * Proč ne rovnou v ingestu: doručení sahá na AI a na Slack, tedy na dvě věci, které umí být
 * pomalé a rozbité nezávisle na storu. Kdyby viselo v ingestu, jeden nedostupný Slack by
 * zdržel stahování recenzí celé appky a retry by opakoval i fetch. Takhle má každá recenze
 * vlastní retry, vlastní backoff a vlastní řádek v DLQ.
 */
class DeliveryJobs(
    private val deliver: DeliverReviewUseCase,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
) {
    val deliverTask: OneTimeTask<DeliveryJobData> =
        Tasks
            .oneTime(DELIVER_TASK, DeliveryJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<DeliveryJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUp),
            ).execute { instance, _ -> runDelivery(instance) }

    /** Naplánuje doručení recenzí, které ingest označil jako notifikovatelné. */
    fun schedule(
        client: SchedulerClient,
        orgId: OrganizationId,
        results: List<ReviewUpsertResult>,
    ) {
        results.forEach { result ->
            val data =
                DeliveryJobData(
                    orgId = orgId.toString(),
                    reviewId = result.review.id.toString(),
                    contentHash = result.review.contentHash,
                )
            // Instance je (recenze, otisk): opakovaný ingest téhož znění nezaloží druhou úlohu,
            // editace ano. Doběhlá úloha z fronty zmizí, takže se stejná instance může
            // naplánovat znovu — duplicitní zprávu ale nepustí rezervace v review_message.
            client.scheduleIfNotExists(
                deliverTask.instance("${data.reviewId}:${data.contentHash}", data),
                Instant.now(),
            )
        }
    }

    private fun runDelivery(instance: TaskInstance<DeliveryJobData>) {
        val data = instance.data
        val report =
            runBlocking {
                deliver.deliver(OrganizationId.parse(data.orgId), ReviewId.parse(data.reviewId))
            }

        when {
            report.skipped != null -> logger.info { "Doručení recenze ${data.reviewId} přeskočeno: ${report.skipped}" }

            report.isRetryable -> throw RetryableDeliveryException(report.failureSummary())

            report.failures.isNotEmpty() ->
                // Trvalá chyba (bot vyhozený z kanálu, odvolaný token): opakovat nemá co pomoct,
                // ale v consoli to musí být vidět, jinak klient jen přestane dostávat zprávy.
                recordFailure(instance, report.failureSummary())

            else -> failedJobs.resolve(DELIVER_TASK, instance.id, clock.now())
        }
    }

    private fun giveUp(
        complete: ExecutionComplete,
        operations: ExecutionOperations<DeliveryJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<DeliveryJobData>
        val cause = complete.cause.orElse(null)
        recordFailure(instance, cause?.message ?: "neznámá chyba", cause)
        // Jednorázová úloha po vyčerpaných pokusech končí; recenze zůstane v NEW a je vidět
        // v konzoli i v DLQ. Znovu ji rozjede až další ingest nebo ruční akce.
        operations.stop()
    }

    private fun recordFailure(
        instance: TaskInstance<DeliveryJobData>,
        message: String,
        cause: Throwable? = null,
    ) {
        val data = instance.data
        logger.warn { "Doručení recenze ${data.reviewId} selhalo, jde do DLQ: $message" }
        failedJobs.record(
            taskName = DELIVER_TASK,
            taskInstance = instance.id,
            orgId = OrganizationId.parse(data.orgId),
            payload = "review=${data.reviewId}",
            errorClass = (cause ?: RetryableDeliveryException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val DELIVER_TASK = "deliver-review"

        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofSeconds(30)
        const val DEFAULT_RETRIES = 5
        private const val BACKOFF_RATE = 3.0
    }
}

private fun DeliveryReport.failureSummary(): String = failures.joinToString { "${it.channelId}: ${it.kind} ${it.message}" }
