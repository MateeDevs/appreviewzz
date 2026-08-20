package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.MessageLocale
import kotlinx.datetime.LocalTime
import java.time.DateTimeException
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Kontroly hodnot, které o appce zadává člověk — v consoli i v CLI.
 *
 * Stejná pravidla má i databáze (`CHECK`), ale ta umí jen odmítnout. Tohle je vrstva,
 * která místo constraint violation vrátí větu. Proto sem patří jen to, co má **jedno**
 * znění pro obě rozhraní; `field` je jméno, pod kterým hodnotu zná volající
 * (`--timezone` v CLI, `timezone` v API).
 */
object AppInputs {
    const val MIN_INGEST_INTERVAL = 5
    const val MAX_INGEST_INTERVAL = 1440

    fun locale(
        raw: String,
        field: String,
    ): MessageLocale =
        MessageLocale.entries.firstOrNull { it.code == raw.lowercase() }
            ?: invalid(field, "zná ${MessageLocale.entries.joinToString { it.code }}, dostalo '$raw'")

    fun timezone(
        raw: String,
        field: String,
    ): String {
        try {
            ZoneId.of(raw)
        } catch (_: DateTimeException) {
            invalid(field, "'$raw' není známá zóna (čekám např. Europe/Prague)")
        }
        return raw
    }

    fun ingestInterval(
        minutes: Int,
        field: String,
    ): Int {
        if (minutes !in MIN_INGEST_INTERVAL..MAX_INGEST_INTERVAL) {
            invalid(field, "musí být mezi $MIN_INGEST_INTERVAL a $MAX_INGEST_INTERVAL minutami")
        }
        return minutes
    }

    fun digestAt(
        raw: String,
        field: String,
    ): LocalTime = runCatching { LocalTime.parse(raw) }.getOrElse { invalid(field, "čeká čas ve tvaru HH:MM, dostalo '$raw'") }

    /**
     * Watermark, od kterého se recenze notifikují. `now` je to, co se použije při onboardingu
     * existující appky: historie se doimportuje, ale kanál nezaplaví.
     */
    fun notifyFrom(
        raw: String?,
        field: String,
        clock: Clock,
    ): Instant? =
        when {
            raw == null -> null
            raw.equals("now", ignoreCase = true) -> clock.now()
            else ->
                runCatching { Instant.parse(raw) }.getOrElse {
                    invalid(field, "čeká 'now' nebo čas v ISO-8601 (2026-08-19T00:00:00Z), dostalo '$raw'")
                }
        }

    private fun invalid(
        field: String,
        detail: String,
    ): Nothing = throw ConsoleException(ConsoleFailure.INVALID_INPUT, "$field $detail")
}
