package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.usecase.RefreshStoreRepliesUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Dohledání odpovědí napsaných ve storu mimo náš systém (Google Play `reviews.get`).
 *
 * Na rozdíl od ingestu a přehledů tohle **není instance na appku**. Není co nastavovat —
 * perioda nevychází z konfigurace klienta, ale z okna Google Play API — a práce je malá,
 * takže jedna úloha nad seznamem zapnutých aplikací je levnější než plánovač plný instancí,
 * které dělají skoro nic.
 *
 * Běží řídce schválně: recenze mimo týdenní okno už nikam nespěchá a každá ověřovaná recenze
 * stojí jedno HTTP volání. Selhání jedné appky nesmí zastavit ostatní, proto se chytá
 * u každé zvlášť.
 */
class RefreshRepliesJobs(
    private val refresh: RefreshStoreRepliesUseCase,
    private val apps: AppRepository,
    schedule: Schedule = Schedules.fixedDelay(DEFAULT_INTERVAL),
) {
    val refreshTask: RecurringTask<Void> =
        Tasks
            .recurring(REFRESH_TASK, schedule)
            .execute { _, _ -> refreshAll() }

    fun refreshAll() {
        var answered = 0
        var failed = 0
        runBlocking {
            apps.listEnabled().forEach { app ->
                runCatching { refresh.refresh(app.orgId, app.id) }
                    .onSuccess { answered += it.answered }
                    .onFailure { error ->
                        failed++
                        logger.warn(error) { "Dohledání odpovědí pro appku ${app.id} selhalo" }
                    }
            }
        }
        if (answered > 0 || failed > 0) {
            logger.info { "Dohledání odpovědí: $answered recenzí překlopeno do REPLIED, $failed appek selhalo" }
        }
    }

    companion object {
        const val REFRESH_TASK = "refresh-store-replies"

        /** Čtyřikrát denně. Častěji nemá co objevit, řidčeji by odpověď visela ve frontě dny. */
        val DEFAULT_INTERVAL: Duration = Duration.ofHours(6)
    }
}
