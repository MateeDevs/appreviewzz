package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AnalysisCadence
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.PlatformSettings
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
    /** Krajní meze zrcadlí `CHECK` v databázi; provozní podlaha je platformní nastavení. */
    const val MIN_INGEST_INTERVAL = PlatformSettings.MIN_ALLOWED_INTERVAL
    const val MAX_INGEST_INTERVAL = PlatformSettings.MAX_ALLOWED_INTERVAL

    /** Zrcadlí `CHECK` na `app.analysis_min_reviews` a `app.analysis_min_topic_count`. */
    const val MAX_ANALYSIS_MIN_REVIEWS = 1000
    const val MAX_ANALYSIS_MIN_TOPIC_COUNT = 100

    /** Zrcadlí `CHECK` na `app.history_months`. */
    const val MIN_HISTORY_MONTHS = 1
    const val MAX_HISTORY_MONTHS = 24

    /** Limit odpovědi v Google Play; App Store má 5 000, ale text musí projít oběma. */
    const val MAX_AUTO_THANKS = 350

    /** ISO dny v týdnu; zrcadlí `CHECK` na `app.weekly_digest_day`. */
    const val MIN_WEEKLY_DAY = 1
    const val MAX_WEEKLY_DAY = 7

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

    /**
     * Záložní text automatického poděkování (C2). Prázdný řetězec ho ruší; delší než
     * [MAX_AUTO_THANKS] se do Google Play stejně nevejde a publikace by spadla až ve storu.
     */
    fun autoThanksTemplate(
        raw: String,
        field: String,
    ): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_AUTO_THANKS) invalid(field, "je delší než $MAX_AUTO_THANKS znaků, což Google Play nepřijme")
        return text
    }

    /**
     * Den týdenního rozboru jako ISO číslo dne (1 = pondělí). Číslo, ne název dne, protože
     * stejnou hodnotu bere cron i databáze a překlad na jméno patří až do konzole.
     */
    fun weeklyDigestDay(
        day: Int,
        field: String,
    ): Int {
        if (day !in MIN_WEEKLY_DAY..MAX_WEEKLY_DAY) {
            invalid(field, "musí být mezi $MIN_WEEKLY_DAY (pondělí) a $MAX_WEEKLY_DAY (neděle)")
        }
        return day
    }

    /**
     * Kolik měsíců historie recenzí se pro appku stahuje z reportingu Play Console. Meze
     * zrcadlí `CHECK` v databázi; nula mezi nimi není schválně — průběžný běh archivu se
     * nevypíná, protože jinou cestou k hodnocením bez textu se nedostaneme.
     */
    fun historyMonths(
        months: Int,
        field: String,
    ): Int {
        if (months !in MIN_HISTORY_MONTHS..MAX_HISTORY_MONTHS) {
            invalid(field, "musí být mezi $MIN_HISTORY_MONTHS a $MAX_HISTORY_MONTHS měsíci")
        }
        return months
    }

    /**
     * Kadence rozborů. Jméno hodnoty, ne číslo: `WEEKLY`/`MONTHLY` čte v API i v CLI člověk
     * a překlad na „týdně" patří až do konzole.
     */
    fun analysisCadence(
        raw: String,
        field: String,
    ): AnalysisCadence =
        AnalysisCadence.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: invalid(field, "zná ${AnalysisCadence.entries.joinToString { it.name.lowercase() }}, dostalo '$raw'")

    /**
     * Výjimka od platformního prahu rozboru. Nula mezi hodnotami není: rozbor „z ničeho"
     * nedává smysl a vypnout ho jde tím, že kanál rozbory nedostává.
     */
    fun analysisThreshold(
        value: Int,
        field: String,
        max: Int,
    ): Int {
        if (value !in PlatformSettings.MIN_ANALYSIS_THRESHOLD..max) {
            invalid(field, "musí být mezi ${PlatformSettings.MIN_ANALYSIS_THRESHOLD} a $max")
        }
        return value
    }

    fun digestAt(
        raw: String,
        field: String,
    ): LocalTime = runCatching { LocalTime.parse(raw) }.getOrElse { invalid(field, "čeká čas ve tvaru HH:MM, dostalo '$raw'") }

    /**
     * Reportingový bucket Play Console. Klient ho kopíruje z Play Console, kde je i s prefixem
     * `gs://` — přijmeme obojí a uložíme holé jméno, ať se to nemusí řešit u každého volání.
     */
    fun reportingBucket(
        raw: String,
        field: String,
    ): String {
        val value = raw.trim().removePrefix("gs://").trimEnd('/')
        if (value.isEmpty()) invalid(field, "je prázdný")
        if (!BUCKET_NAME.matches(value)) {
            invalid(field, "'$raw' nevypadá jako jméno bucketu (čekám např. pubsite_prod_rev_01234567890123456789)")
        }
        return value
    }

    /**
     * Watermark nově zakládané appky. Bez zadané hodnoty je to **teď**: appka přidaná dnes
     * nesmí do kanálu vysypat recenze, které ve storu ležely měsíce. Kdo historii do kanálu
     * opravdu chce, pošle konkrétní datum v minulosti.
     */
    fun newAppNotifyFrom(
        raw: String?,
        field: String,
        clock: Clock,
    ): Instant = notifyFrom(raw, field, clock) ?: clock.now()

    /**
     * Watermark, od kterého se recenze notifikují. `now` je to, co se použije při onboardingu
     * existující appky: historie se doimportuje, ale kanál nezaplaví. `null` znamená
     * „hodnotu neměň" — výchozí hodnotu pro novou appku dává [newAppNotifyFrom].
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

    /**
     * Package name aplikace v Google Play. Bere i **celý odkaz na store** — do console se
     * appka přidává tak, že se odkaz zkopíruje z prohlížeče, a nutit člověka, aby si z něj
     * package name vypsal sám, je zbytečný krok navíc.
     */
    fun playPackage(
        raw: String,
        field: String,
    ): String {
        val value = raw.trim()
        val candidate =
            PLAY_LINK_ID
                .find(value)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: value
        if (!PACKAGE_NAME.matches(candidate)) {
            invalid(field, "'$raw' není package name ani odkaz na Google Play (čekám např. cz.matee.islegrow)")
        }
        return candidate
    }

    /**
     * Číselné App ID. Stejně jako u Play bere celý odkaz z App Storu; `id1490577875`
     * i holé číslo znamenají totéž.
     */
    fun appStoreId(
        raw: String,
        field: String,
    ): String {
        val value = raw.trim()
        val candidate = APP_STORE_LINK_ID.find(value)?.groupValues?.get(1) ?: value.removePrefix("id")
        if (!APP_STORE_ID.matches(candidate)) {
            invalid(field, "'$raw' není číselné App ID ani odkaz na App Store (čekám např. 1490577875)")
        }
        return candidate
    }

    /** Pravidla Cloud Storage: malá písmena, číslice, pomlčky, podtržítka a tečky. */
    private val BUCKET_NAME = Regex("[a-z0-9][a-z0-9._-]{2,221}")

    /** `…/store/apps/details?id=cz.matee.islegrow&hl=cs`, ale i `market://details?id=…`. */
    private val PLAY_LINK_ID = Regex("""[?&]id=([^&#]+)""")

    /** Java package: aspoň dva segmenty, každý začíná písmenem. */
    private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    /** `…/cz/app/islegrow/id1490577875?l=cs` na apps.apple.com i na starém itunes.apple.com. */
    private val APP_STORE_LINK_ID = Regex("""/id(\d{3,})""")

    private val APP_STORE_ID = Regex("""\d{3,}""")

    private fun invalid(
        field: String,
        detail: String,
    ): Nothing = throw ConsoleException(ConsoleFailure.INVALID_INPUT, "$field $detail")
}
