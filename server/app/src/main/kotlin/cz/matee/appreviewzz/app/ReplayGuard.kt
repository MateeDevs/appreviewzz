package cz.matee.appreviewzz.app

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * „Tohle jsem už jednou viděl" (F5.2).
 *
 * Ověřený podpis říká jen to, že požadavek **někdy** poslal ten, kdo zná tajemství — ne že ho
 * poslal teď a poprvé. Kdo zachytí jeden platný požadavek, může ho v okně tolerance posílat
 * znovu a znovu; u interactivity webhooku by tím publikoval tutéž odpověď, u instalačního
 * odkazu by cizí workspace připojil k organizaci, ke které nepatří.
 *
 * Drží se **otisk**, ne původní hodnota: v paměti procesu (a v případném heap dumpu) tak
 * neleží podpisy ani instalační state.
 *
 * Platí per instance procesu, stejně jako [RateLimiter] — a stejně jako u něj je to při
 * jednom API kontejneru totéž jako per deployment. Restart okno vynuluje; zachycený požadavek
 * má ale krátkou platnost, takže se tím nic dlouhodobého neotvírá.
 */
class ReplayGuard(
    private val name: String,
    private val retention: Duration,
    private val clock: Clock = Clock.System,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seen = ConcurrentHashMap<String, Instant>()

    val trackedEntries: Int get() = seen.size

    /**
     * @return `true`, když je hodnota nová (a od teď se pamatuje); `false` pro opakování.
     */
    fun firstSighting(value: String): Boolean {
        val now = clock.now()
        if (seen.size >= maxEntries) prune(now)
        val fingerprint = fingerprint(value)
        val previous = seen.put(fingerprint, now)
        if (previous == null || previous + retention <= now) return true
        // Vrátit původní čas: jinak by donekonečna opakovaný požadavek okno pořád posouval.
        seen[fingerprint] = previous
        logger.warn { "Ochrana '$name' zachytila opakovaný požadavek" }
        return false
    }

    private fun prune(now: Instant) {
        seen.entries.removeIf { (_, at) -> at + retention <= now }
        if (seen.size >= maxEntries) {
            // Zaplněná paměť by znamenala tiché propouštění replayů, takže je lepší o tom vědět.
            logger.warn { "Ochrana '$name' si pamatuje $maxEntries položek a nemá co uklidit" }
        }
    }

    private fun fingerprint(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 100_000

        /**
         * Podpisy ze Slacku. Dvojnásobek tolerance stáří (5 minut) — kratší okno by pustilo
         * replay, který dorazí těsně před vypršením podpisu.
         */
        val SLACK_RETENTION: Duration = 10.minutes

        /** Aktivity z Teams. Token od Bot Connectoru žije hodinu, okno je stejné. */
        val TEAMS_RETENTION: Duration = 1.hours

        /** Instalační `state`. Stejně dlouho, jako odkaz platí — pak už ho odmítne podpis. */
        val INSTALL_RETENTION: Duration = 2.hours
    }
}

/** Ochrany proti přehrání, které API používá. Pohromadě ze stejného důvodu jako [RateLimits]. */
class ReplayGuards(
    clock: Clock = Clock.System,
) {
    val slack = ReplayGuard("slack-interactivity", ReplayGuard.SLACK_RETENTION, clock)
    val teams = ReplayGuard("teams-activity", ReplayGuard.TEAMS_RETENTION, clock)
    val slackInstall = ReplayGuard("slack-install", ReplayGuard.INSTALL_RETENTION, clock)
}
