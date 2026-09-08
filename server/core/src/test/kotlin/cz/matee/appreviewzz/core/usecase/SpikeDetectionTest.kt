package cz.matee.appreviewzz.core.usecase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private val TODAY = LocalDate(2026, 9, 8)

/** Klidná baseline: [days] dní po [perDay] recenzích, hned před dneškem. */
private fun baseline(
    days: Int,
    perDay: Int,
) = (1..days).map { DayCount(TODAY.minus(it, DateTimeUnit.DAY), perDay) }

/**
 * Detekce výkyvu je čistá funkce — testuje se bez databáze i bez AI, a přesně proto ji
 * dělá statistika. Zajímavé jsou hranice: absolutní minimum, práh z a krátká historie.
 */
class SpikeDetectionTest :
    FunSpec({
        test("pod absolutním minimem se výkyv nehlásí, i když je poměr extrémní") {
            // Nula recenzí denně po měsíci, dnes čtyři: poměrově obrovský skok, v absolutních
            // číslech čtyři recenze. Alert po čtyřech recenzích se za týden začne ignorovat.
            val spike = SpikeDetection.detect(baseline(30, 0), DayCount(TODAY, SpikeDetection.MINIMUM_OBSERVED - 1))

            spike shouldBe null
        }

        test("skok nad práh z se ohlásí i s tím, proti čemu se měří") {
            val spike = SpikeDetection.detect(baseline(30, 1), DayCount(TODAY, 11))

            val found = spike.shouldNotBeNull()
            found.observed shouldBe 11
            found.expected shouldBe 1.0
            found.zScore shouldBeGreaterThanOrEqual SpikeDetection.Z_THRESHOLD
        }

        test("appka bez dvou týdnů historie alert nedostane") {
            val spike = SpikeDetection.detect(baseline(SpikeDetection.MIN_HISTORY_DAYS - 1, 1), DayCount(TODAY, 20))

            spike shouldBe null
        }

        test("dny bez recenzí se do baseline počítají jako nuly") {
            // Recenze obden po dvou. Kdyby se počítaly jen dny se záznamem, průměr by vyšel 2
            // místo 1 a šest recenzí by výkyv nebylo — přitom je to šestinásobek běžného dne.
            val everyOtherDay = (1..30).filter { it % 2 == 0 }.map { DayCount(TODAY.minus(it, DateTimeUnit.DAY), 2) }

            val spike = SpikeDetection.detect(everyOtherDay, DayCount(TODAY, 6))

            spike.shouldNotBeNull().expected shouldBe 1.0
        }

        test("běžný provozní šum výkyv není") {
            val spike = SpikeDetection.detect(baseline(30, 8), DayCount(TODAY, 10))

            spike shouldBe null
        }
    })
