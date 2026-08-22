package cz.matee.appreviewzz.app

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Ručně posouvaný čas. Limity i ochrana proti přehrání stojí na oknech — bez tohohle by se
 * daly ověřit jen čekáním, tedy testem, který občas spadne a nikdo neví proč.
 */
class TestClock(
    var current: Instant = Instant.parse("2026-08-22T20:00:00Z"),
) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}
