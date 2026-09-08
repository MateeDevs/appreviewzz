package cz.matee.appreviewzz.core.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlin.math.sqrt

/** Jeden den baseline: kolik toho ten den bylo. */
data class DayCount(
    val date: LocalDate,
    val count: Int,
)

/**
 * Výkyv i s tím, proti čemu se měří. `expected` a `zScore` jdou do zprávy schválně:
 * alert, u kterého nejde říct „obvykle bývá jedna až dvě", se čte jako náhodný poplach.
 */
data class Spike(
    val date: LocalDate,
    val observed: Int,
    val expected: Double,
    val zScore: Double,
)

/**
 * Detekce výkyvu (B4). **Statistika, ne AI.**
 *
 * Pravidlo má dvě podmínky a obě musí platit:
 *  - `z ≥ 3` proti průměru posledních [BASELINE_DAYS] dní (bez dneška),
 *  - a zároveň aspoň [MINIMUM_OBSERVED] recenzí v absolutních číslech.
 *
 * Ta druhá podmínka je tam kvůli malým číslům: appka, které chodí nula až jedna záporná
 * recenze denně, má směrodatnou odchylku skoro nulovou a *každé* dvě recenze by pak byly
 * „z = 8". Alert po dvou recenzích je šum, který se za týden začne ignorovat — a tím
 * přestane fungovat i ten, který přijde právem.
 *
 * Appka bez [MIN_HISTORY_DAYS] dní historie nedostane alert vůbec: baseline z pěti dní
 * neříká nic o tom, co je pro ni normální.
 */
object SpikeDetection {
    const val BASELINE_DAYS = 28
    const val MIN_HISTORY_DAYS = 14
    const val MINIMUM_OBSERVED = 5
    const val Z_THRESHOLD = 3.0

    /**
     * @param history dny **před** [today], v libovolném pořadí; starší než baseline se zahodí
     * @param today den, na který se ptáme
     */
    fun detect(
        history: List<DayCount>,
        today: DayCount,
    ): Spike? {
        if (today.count < MINIMUM_OBSERVED) return null

        // Baseline jsou souvislé kalendářní dny, ne jen ty s recenzemi: den bez jediné
        // stížnosti je informace o tom, co je normální, a vynechat ho znamená baseline
        // nadhodnotit — u appky, která má recenze obden, dvojnásobně.
        val start = today.date.minusDays(BASELINE_DAYS)
        val byDate = history.filter { it.date >= start && it.date < today.date }.associate { it.date to it.count }
        val oldest = byDate.keys.minOrNull() ?: return null
        val historyDays = oldest.daysUntilExclusive(today.date)
        if (historyDays < MIN_HISTORY_DAYS) return null

        val counts = (0 until historyDays).map { offset -> byDate[today.date.minusDays(historyDays - offset)] ?: 0 }
        val mean = counts.average()
        val variance = counts.sumOf { (it - mean) * (it - mean) } / counts.size
        // Dělitel aspoň 1: u appky s odchylkou 0,2 by jinak vyšlo z = 40 a práh by
        // nerozlišoval mezi „trochu víc" a „hoří".
        val deviation = maxOf(sqrt(variance), 1.0)
        val z = (today.count - mean) / deviation
        if (z < Z_THRESHOLD) return null

        return Spike(date = today.date, observed = today.count, expected = mean, zScore = z)
    }

    private fun LocalDate.minusDays(days: Int): LocalDate = minus(days, DateTimeUnit.DAY)

    private fun LocalDate.daysUntilExclusive(other: LocalDate): Int = daysUntil(other)
}
