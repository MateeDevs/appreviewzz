package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

/**
 * Cron týdenního rozboru. Den v týdnu má v Quartzu jiné číslování než ISO (1 = neděle),
 * takže právě tady se dá snadno poslat rozbor o den vedle — a nikdo si toho nevšimne,
 * dokud si klient nestěžuje, že mu chodí v neděli.
 */
class WeeklyAnalysisScheduleTest :
    FunSpec({
        fun data(
            day: Int,
            at: String = "08:30",
            timezone: String = "Europe/Prague",
        ) = WeeklyAnalysisJobData(
            orgId = Uuid.random().toString(),
            appId = Uuid.random().toString(),
            day = day,
            at = at,
            timezone = timezone,
        )

        fun next(schedule: Schedule): ZonedDateTime {
            // Čtvrtek 3. 9. 2026 v poledne pražského času.
            val from = Instant.parse("2026-09-03T10:00:00Z")
            return ZonedDateTime.ofInstant(
                schedule.getNextExecutionTime(ExecutionComplete.simulatedSuccess(from)),
                ZoneId.of("Europe/Prague"),
            )
        }

        test("pondělí je pondělí, ne neděle") {
            val run = next(data(day = 1).schedule)

            run.dayOfWeek shouldBe java.time.DayOfWeek.MONDAY
            run.hour shouldBe 8
            run.minute shouldBe 30
        }

        test("neděle jako sedmý den ISO nepřeteče na sobotu") {
            next(data(day = 7).schedule).dayOfWeek shouldBe java.time.DayOfWeek.SUNDAY
        }

        test("čas platí v zóně aplikace, ne serveru") {
            val run =
                ZonedDateTime.ofInstant(
                    data(day = 1, at = "08:30", timezone = "America/New_York")
                        .schedule
                        .getNextExecutionTime(ExecutionComplete.simulatedSuccess(Instant.parse("2026-09-03T10:00:00Z"))),
                    ZoneId.of("America/New_York"),
                )

            run.hour shouldBe 8
            run.minute shouldBe 30
        }

        test("neznámá zóna rozbor nezastaví, jen ho posune do UTC") {
            val run =
                ZonedDateTime.ofInstant(
                    data(day = 1, timezone = "Nekde/Nikde")
                        .schedule
                        .getNextExecutionTime(ExecutionComplete.simulatedSuccess(Instant.parse("2026-09-03T10:00:00Z"))),
                    ZoneId.of("UTC"),
                )

            run.hour shouldBe 8
        }
    })
