package cz.matee.appreviewzz.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes

class ReplayGuardTest :
    StringSpec({

        "poprvé ano, podruhé ne" {
            val guard = ReplayGuard("test", 10.minutes, TestClock())

            guard.firstSighting("v0=abc") shouldBe true
            guard.firstSighting("v0=abc") shouldBe false
        }

        "různé hodnoty se navzájem neblokují" {
            val guard = ReplayGuard("test", 10.minutes, TestClock())

            guard.firstSighting("v0=abc") shouldBe true
            guard.firstSighting("v0=def") shouldBe true
        }

        "po vypršení okna je hodnota zase nová" {
            val clock = TestClock()
            val guard = ReplayGuard("test", 10.minutes, clock)
            guard.firstSighting("v0=abc")

            clock.advance(10.minutes)

            guard.firstSighting("v0=abc") shouldBe true
        }

        "opakované pokusy okno neposouvají" {
            val clock = TestClock()
            val guard = ReplayGuard("test", 10.minutes, clock)
            guard.firstSighting("v0=abc")

            // Útočník posílá tentýž požadavek každou minutu; kdyby každý pokus okno prodloužil,
            // ochrana by si hodnotu pamatovala navěky a mezitím rostla.
            repeat(9) {
                clock.advance(1.minutes)
                guard.firstSighting("v0=abc") shouldBe false
            }
            clock.advance(1.minutes)

            guard.firstSighting("v0=abc") shouldBe true
        }

        "prošlé položky se uklidí, aby paměť nerostla donekonečna" {
            val clock = TestClock()
            val guard = ReplayGuard("test", 10.minutes, clock, maxEntries = 2)

            guard.firstSighting("a")
            guard.firstSighting("b")
            clock.advance(11.minutes)
            guard.firstSighting("c")

            guard.trackedEntries shouldBe 1
        }
    })
