package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.Scheduler
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private fun relayScheduler(jobs: SlackRelayJobs): Scheduler =
    Scheduler
        .create(TestDatabase.database.asDataSource(), listOf(jobs.relayTask))
        .serializer(JsonTaskSerializer)
        .commitWhenAutocommitDisabled(true)
        .threads(1)
        .pollingInterval(Duration.ofMillis(50))
        .build()

class SlackRelayJobsTest :
    FunSpec({
        beforeTest { TestDatabase.reset() }

        test("potvrzená relay úloha přežije nový worker, deduplikuje příjem a zopakuje výpadek cíle") {
            val failedJobs = ExposedFailedJobRepository(TestDatabase.database.exposed)
            val attempts = AtomicInteger()
            val data = SlackRelayJobData("staging", "a".repeat(64), "private reply body")
            val jobs =
                SlackRelayJobs(forward = {
                    it shouldBe data
                    if (attempts.incrementAndGet() == 1) throw ChannelException(ChannelErrorKind.TRANSIENT, "Target unavailable")
                }, failedJobs = failedJobs, retries = 2, firstRetryDelay = Duration.ofMillis(50))
            val original = relayScheduler(jobs)
            try {
                jobs.enqueue(original, data) shouldBe true
                jobs.enqueue(original, data) shouldBe false
            } finally {
                original.stop()
            }
            // Nová instance čte původní úlohu ze skutečného Postgresu.
            val restarted = relayScheduler(jobs)
            try {
                restarted.start()
                eventually(10.seconds) {
                    attempts.get() shouldBe 2
                    restarted.getScheduledExecutionsForTask(SlackRelayJobs.TASK, SlackRelayJobData::class.java).size shouldBe 0
                }
                failedJobs.listOpen().size shouldBe 0
            } finally {
                restarted.stop()
            }
        }

        test("trvalá chyba i vyčerpané retry končí v DLQ bez obsahu odpovědi") {
            for (kind in listOf(ChannelErrorKind.INVALID_REQUEST, ChannelErrorKind.TRANSIENT)) {
                TestDatabase.reset()
                val failedJobs = ExposedFailedJobRepository(TestDatabase.database.exposed)
                val attempts = AtomicInteger()
                val jobs =
                    SlackRelayJobs(forward = {
                        attempts.incrementAndGet()
                        throw ChannelException(kind, "Relay failed")
                    }, failedJobs = failedJobs, retries = 1, firstRetryDelay = Duration.ofMillis(50))
                val scheduler = relayScheduler(jobs)
                try {
                    jobs.enqueue(scheduler, SlackRelayJobData("staging", "b".repeat(64), "secret reply body"))
                    scheduler.start()
                    eventually(10.seconds) { failedJobs.listOpen().size shouldBe 1 }
                    failedJobs.listOpen().single().toString() shouldNotContain "secret reply body"
                    (attempts.get() >= 1) shouldBe true
                    if (kind == ChannelErrorKind.INVALID_REQUEST) attempts.get() shouldBe 1
                } finally {
                    scheduler.stop()
                }
            }
        }
    })
