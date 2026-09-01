package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.usecase.RevalidateCredentialsUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Hlídání klíčů, které ještě nefungují — hlavně čerstvě pozvaného service accountu, na jehož
 * práva se v Play Console čeká.
 *
 * Jedna úloha nad všemi tenanty, ne instance na appku: práce je malá, perioda nevychází
 * z konfigurace klienta a klíčů čekajících na ověření je v každou chvíli hrstka.
 */
class RevalidateCredentialsJobs(
    private val revalidate: RevalidateCredentialsUseCase,
    schedule: Schedule = Schedules.fixedDelay(DEFAULT_INTERVAL),
) {
    val revalidateTask: RecurringTask<Void> =
        Tasks
            .recurring(REVALIDATE_TASK, schedule)
            .execute { _, _ -> revalidateAll() }

    fun revalidateAll() {
        runBlocking {
            runCatching { revalidate.revalidate() }
                .onFailure { error -> logger.warn(error) { "Revalidace klíčů selhala" } }
        }
    }

    companion object {
        const val REVALIDATE_TASK = "revalidate-credentials"

        /**
         * Čtvrthodina. Klient u dialogu čeká na to, až se pozvánka propíše — hodina by
         * z „obvykle minuty" udělala „obvykle hodinu".
         */
        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
