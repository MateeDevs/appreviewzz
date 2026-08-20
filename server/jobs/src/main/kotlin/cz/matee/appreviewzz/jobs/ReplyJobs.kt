package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import cz.matee.appreviewzz.core.model.ChannelId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.sha256Hex
import cz.matee.appreviewzz.core.port.FailedJobRepository
import cz.matee.appreviewzz.core.usecase.PublishReplyUseCase
import cz.matee.appreviewzz.core.usecase.ReplyCommand
import cz.matee.appreviewzz.core.usecase.ReplyOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Store odmítl odpověď kvůli limitu nebo výpadku — zkusit znovu má smysl. */
class RetryableReplyException(
    message: String,
) : RuntimeException(message)

/**
 * Payload publikace. Text odpovědi je jediné místo, kde v `task_data` leží uživatelský obsah —
 * není to tajemství (za chvíli bude veřejně ve storu), ale do logu ho nepouštíme.
 */
@Serializable
data class ReplyJobData(
    val orgId: String,
    val reviewId: String,
    val channelId: String?,
    val body: String,
    val source: String,
    val authorExternalId: String?,
    val authorDisplayName: String?,
    /** Přihlášený uživatel console. U odpovědí z chatu zůstává prázdné. */
    val authorUserId: String? = null,
)

/**
 * Publikace odpovědi jako úloha, ne jako součást webhooku. Slack čeká na potvrzení **do tří
 * sekund** — publikace do storu se do toho okna nevejde, takže webhook úlohu jen založí
 * a hned odpovídá. Nasazení nové verze uprostřed klikání tím pádem odpověď neztratí:
 * leží ve frontě v databázi.
 */
class ReplyJobs(
    private val publish: PublishReplyUseCase,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    private val retries: Int = DEFAULT_RETRIES,
    private val firstRetryDelay: Duration = DEFAULT_FIRST_RETRY_DELAY,
) {
    val publishTask: OneTimeTask<ReplyJobData> =
        Tasks
            .oneTime(PUBLISH_TASK, ReplyJobData::class.java)
            .onFailure(
                FailureHandler
                    .maxRetries<ReplyJobData>(retries)
                    .withBackoff(firstRetryDelay, BACKOFF_RATE)
                    .then(::giveUp),
            ).execute { instance, _ -> runPublish(instance) }

    /**
     * Zařadí odpověď k publikaci. Instance je (recenze, otisk textu), takže dvojklik na
     * „Odeslat" založí jednu úlohu — druhou pojistkou je unikátní otisk v tabulce `reply`.
     */
    fun enqueue(
        client: SchedulerClient,
        data: ReplyJobData,
    ): Boolean =
        client.scheduleIfNotExists(
            publishTask.instance("${data.reviewId}:${sha256Hex(data.body).take(INSTANCE_HASH_LENGTH)}", data),
            Instant.now(),
        )

    private fun runPublish(instance: TaskInstance<ReplyJobData>) {
        val data = instance.data
        val outcome = runBlocking { publish.publish(data.toCommand()) }

        when (outcome) {
            is ReplyOutcome.Published, is ReplyOutcome.AlreadyPublished ->
                failedJobs.resolve(PUBLISH_TASK, instance.id, clock.now())

            is ReplyOutcome.Rejected -> {
                // Chybějící klíč ani smazaná recenze se opakováním nespraví; člověk to musí
                // vidět v consoli, protože odpověď zůstala neodeslaná.
                logger.warn { "Odpověď na recenzi ${data.reviewId} odmítnuta: ${outcome.reason}" }
                recordFailure(instance, "odpověď nešla publikovat: ${outcome.reason}")
            }

            is ReplyOutcome.Failed ->
                if (outcome.isRetryable) {
                    throw RetryableReplyException(outcome.message)
                } else {
                    recordFailure(instance, "${outcome.kind}: ${outcome.message}")
                }
        }
    }

    private fun giveUp(
        complete: ExecutionComplete,
        operations: ExecutionOperations<ReplyJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<ReplyJobData>
        val cause = complete.cause.orElse(null)
        recordFailure(instance, cause?.message ?: "neznámá chyba", cause)
        operations.stop()
    }

    private fun recordFailure(
        instance: TaskInstance<ReplyJobData>,
        message: String,
        cause: Throwable? = null,
    ) {
        val data = instance.data
        logger.warn { "Publikace odpovědi na recenzi ${data.reviewId} skončila v DLQ: $message" }
        failedJobs.record(
            taskName = PUBLISH_TASK,
            taskInstance = instance.id,
            orgId = OrganizationId.parse(data.orgId),
            // Text odpovědi do DLQ nepatří — stačí, u které recenze se to stalo.
            payload = "review=${data.reviewId}",
            errorClass = (cause ?: RetryableReplyException(message))::class.qualifiedName,
            errorMessage = message,
            failedAt = clock.now(),
        )
    }

    companion object {
        const val PUBLISH_TASK = "publish-reply"

        val DEFAULT_FIRST_RETRY_DELAY: Duration = Duration.ofSeconds(30)
        const val DEFAULT_RETRIES = 5
        private const val BACKOFF_RATE = 3.0
        private const val INSTANCE_HASH_LENGTH = 16
    }
}

private fun ReplyJobData.toCommand(): ReplyCommand =
    ReplyCommand(
        orgId = OrganizationId.parse(orgId),
        reviewId = ReviewId.parse(reviewId),
        body = body,
        source = ReplySource.valueOf(source),
        channelId = channelId?.let { ChannelId(Uuid.parse(it)) },
        authorExternalId = authorExternalId,
        authorDisplayName = authorDisplayName,
        authorUserId = authorUserId?.let { UserId(Uuid.parse(it)) },
    )
