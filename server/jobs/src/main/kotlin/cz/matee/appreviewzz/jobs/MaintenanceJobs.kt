package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import cz.matee.appreviewzz.core.port.SessionRepository
import cz.matee.appreviewzz.core.port.UserTokenRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}

/**
 * Noční úklid dat, která už k ničemu neslouží (F5.6).
 *
 * Není to jen kosmetika. Prošlá relace a uplatněný token jsou pořád **řádky navázané na
 * uživatele**: v dumpu databáze ukazují, odkud se kdo přihlašoval a kdy si měnil heslo, a
 * `deleteExpired` v repozitáři byl od F3 napsaný, ale nikdo ho nevolal — tabulky tedy jen
 * rostly. Nejlevnější ochrana dat je ta, která je nemá.
 *
 * Odklad [grace] je schválně: mazat řádek ve chvíli, kdy vyprší, znamená přijít o stopu
 * přesně toho, co se při vyšetřování incidentu hledá jako první.
 */
class MaintenanceJobs(
    private val sessions: SessionRepository,
    private val tokens: UserTokenRepository,
    schedule: Schedule = dailyAt(DEFAULT_TIME),
    private val grace: Duration = DEFAULT_GRACE,
    private val clock: Clock = Clock.System,
) {
    val cleanupTask: RecurringTask<Void> =
        Tasks
            .recurring(CLEANUP_TASK, schedule)
            .execute { _, _ -> cleanUp() }

    fun cleanUp() {
        val before = clock.now() - grace
        val removedSessions = sessions.deleteExpired(before)
        val removedTokens = tokens.deleteSpent(before)
        if (removedSessions > 0 || removedTokens > 0) {
            logger.info { "Úklid: smazáno $removedSessions relací a $removedTokens jednorázových tokenů" }
        }
    }

    companion object {
        const val CLEANUP_TASK = "cleanup-expired-auth"

        /** Hodinu po záloze: ať se maže z databáze, která je už uložená. */
        val DEFAULT_TIME: LocalTime = LocalTime.of(3, 30)

        /** Kolik dní po vypršení se řádek ještě drží kvůli případnému dohledávání. */
        val DEFAULT_GRACE: Duration = 30.days

        fun dailyAt(
            time: LocalTime,
            zone: ZoneId = ZoneId.of("UTC"),
        ): Schedule = Schedules.daily(zone, time)
    }
}
