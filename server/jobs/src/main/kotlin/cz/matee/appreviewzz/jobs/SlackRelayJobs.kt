package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.FailedJobRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.time.Clock

/** Pouze normalizovaná odpověď, bez Slack response_url, tokenů a zbytku původního payloadu. */
@Serializable
data class SlackRelayJobData(
    val environment: String,
    val deliveryId: String,
    val payload: String,
) {
    override fun toString(): String = "SlackRelayJobData(environment=$environment, deliveryId=$deliveryId)"
}

/** Slack potvrzujeme po zápisu do DB; dostupnost druhého prostředí ověřuje až worker. */
class SlackRelayJobs(
    private val forward: suspend (SlackRelayJobData) -> Unit,
    private val failedJobs: FailedJobRepository,
    private val clock: Clock = Clock.System,
    retries: Int = 5,
    firstRetryDelay: Duration = Duration.ofSeconds(30),
) {
    val relayTask: OneTimeTask<SlackRelayJobData> =
        Tasks
            .oneTime(TASK, SlackRelayJobData::class.java)
            .onFailure(FailureHandler.maxRetries<SlackRelayJobData>(retries).withBackoff(firstRetryDelay, 3.0).then(::giveUp))
            .execute { instance, _ ->
                try {
                    runBlocking { forward(instance.data) }
                    failedJobs.resolve(TASK, instance.id, clock.now())
                } catch (error: ChannelException) {
                    if (error.isRetryable) throw error
                    recordFailure(instance, error)
                }
            }

    fun enqueue(
        client: SchedulerClient,
        data: SlackRelayJobData,
    ): Boolean = client.scheduleIfNotExists(relayTask.instance("${data.environment}:${data.deliveryId}", data), Instant.now())

    private fun giveUp(
        complete: ExecutionComplete,
        operations: ExecutionOperations<SlackRelayJobData>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val instance = complete.execution.taskInstance as TaskInstance<SlackRelayJobData>
        recordFailure(instance, complete.cause.orElse(null))
        operations.stop()
    }

    private fun recordFailure(
        instance: TaskInstance<SlackRelayJobData>,
        error: Throwable?,
    ) {
        failedJobs.record(
            taskName = TASK,
            taskInstance = instance.id,
            orgId = null,
            payload = "environment=${instance.data.environment} delivery=${instance.data.deliveryId}",
            errorClass = error?.javaClass?.name,
            errorMessage = error?.message ?: "Slack relay selhalo",
            failedAt = clock.now(),
        )
    }

    companion object {
        const val TASK = "slack-relay"
    }
}
