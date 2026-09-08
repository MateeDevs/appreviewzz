package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.ReportingBucketCheck
import cz.matee.appreviewzz.core.port.ReportingBucketProbe
import cz.matee.appreviewzz.core.port.ReportingBucketStatus
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Oficiální hodnocení Androidu z reportingu Play Console.
 *
 * Play Console sype do bucketu `pubsite_prod_…` měsíční CSV `stats/ratings/…_overview.csv`
 * s denním a celkovým průměrem. Je to jediný **oficiální** zdroj Android průměru — veřejná
 * stránka storu ukazuje zaokrouhlené číslo.
 *
 * Tři věci oproti dnešnímu n8n:
 *
 * - **Bucket je nastavení aplikace**, ne natvrdo v uzlu jednoho workflow pro jednoho klienta.
 * - **Datum z CSV se nese dál** (`asOf`). Export je den až dva pozadu; dnes se to zahodí
 *   a v kartě se předevčerejší průměr tváří jako dnešní.
 * - **Chybějící bucket není chyba, ale prázdný výsledek** — volající pak sáhne po scrapu.
 */
class PlayReportingRatingsSource(
    private val httpClient: HttpClient,
    private val oauth: GoogleOAuth = GoogleOAuth(httpClient),
    private val clock: Clock = Clock.System,
    private val baseUrl: String = GCS_BASE_URL,
) : RatingsSource,
    ReportingBucketProbe {
    private val bucketReader = ReportingBucketReader(httpClient, baseUrl)

    override val platform: Platform = Platform.ANDROID

    override val priority: Int = OFFICIAL_PRIORITY

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        val bucket = normalizeBucket(context.reportingBucket)
        val credential = context.credential
        if (bucket == null || credential == null) {
            logger.debug { "Play reporting pro ${context.appIdentifier} přeskočen: chybí bucket nebo klíč" }
            return emptyList()
        }

        val account = GoogleServiceAccount.parse(credential)
        val token = oauth.accessToken(account, STORAGE_SCOPE)
        // Aktuální i předchozí měsíc: první dny v měsíci ještě aktuální soubor neexistuje
        // a export je pozadu, takže poslední den bývá v tom minulém.
        val months = currentAndPreviousMonth()
        val rows =
            months.flatMap { month ->
                val prefix = "stats/ratings/ratings_${context.appIdentifier}_${month}_overview"
                downloadOverview(bucket, prefix, token)
            }

        val latest = rows.maxByOrNull { it.date } ?: return emptyList()
        logger.info { "Play reporting: ${context.appIdentifier} k ${latest.date}, průměr ${latest.totalAverage}" }
        return listOf(
            ObservedRatings(
                platform = Platform.ANDROID,
                territory = ObservedRatings.GLOBAL,
                average = latest.totalAverage,
                totalCount = null,
                // Overview CSV histogram nemá; rozpad po hvězdách umí až scrape.
                histogram = emptyMap(),
                source = RatingSource.GP_CSV,
                asOf = latest.date,
            ),
        )
    }

    /**
     * Ověření bucketu při onboardingu: dosáhne náš účet na export hodnocení téhle appky?
     *
     * Ptáme se na dva prefixy, protože „prázdný výpis" má dvě různé příčiny a klient s nimi
     * dělá něco jiného: **bucket jiného účtu** (exporty tam jsou, jen ne pro tuhle appku)
     * versus **appka bez hodnocení nebo čerstvý export** (v bucketu není nic). Jedním výpisem
     * by z obojího vyšla tatáž nicneříkající věta.
     */
    override suspend fun checkAccess(
        bucket: String,
        appIdentifier: String,
        credential: SecretPayload,
    ): ReportingBucketCheck {
        val name =
            normalizeBucket(bucket)
                ?: return ReportingBucketCheck(ReportingBucketStatus.MISSING, "Adresa bucketu je prázdná.")
        val account = GoogleServiceAccount.parse(credential)

        return try {
            val token = oauth.accessToken(account, STORAGE_SCOPE)
            val forApp = bucketReader.list(name, "stats/ratings/ratings_${appIdentifier}_", token)
            if (forApp.isNotEmpty()) {
                return ReportingBucketCheck(
                    ReportingBucketStatus.OK,
                    "Export hodnocení pro $appIdentifier v bucketu vidíme — oficiální hvězdičky budou chodit.",
                )
            }

            val anything = bucketReader.list(name, "stats/ratings/", token)
            if (anything.isNotEmpty()) {
                ReportingBucketCheck(
                    ReportingBucketStatus.NO_EXPORT,
                    "Do bucketu se dostaneme, ale export pro $appIdentifier v něm není. " +
                        "Nejspíš patří jinému vývojářskému účtu, než pod kterým je aplikace vydaná.",
                )
            } else {
                ReportingBucketCheck(
                    ReportingBucketStatus.NO_EXPORT,
                    "Do bucketu se dostaneme, ale žádný export hodnocení v něm zatím není. " +
                        "Play Console ho doplňuje jednou denně a u aplikace bez hodnocení nevznikne vůbec.",
                )
            }
        } catch (error: StoreConnectorException) {
            val status =
                when (error.kind) {
                    StoreErrorKind.AUTH -> ReportingBucketStatus.DENIED
                    StoreErrorKind.NOT_FOUND -> ReportingBucketStatus.MISSING
                    else -> ReportingBucketStatus.UNAVAILABLE
                }
            val detail =
                when (status) {
                    // K bucketu se práva z Play Console nepropíšou: roli tam přidává člověk
                    // zvlášť a chvíli trvá, než ji Cloud Storage vidí.
                    ReportingBucketStatus.DENIED ->
                        "Účet ${account.clientEmail} k bucketu $name zatím nemá přístup — přidej mu roli " +
                            "Storage Object Viewer. Po přidání se právo propisuje pár minut."

                    ReportingBucketStatus.MISSING ->
                        "Bucket $name neexistuje — zkontroluj, že je adresa zkopírovaná celá."

                    else -> "Cloud Storage teď neodpovídá, o nastavení to nic neříká: ${error.message}"
                }
            ReportingBucketCheck(status, detail)
        }
    }

    /**
     * `YYYYMM` aktuálního a předchozího měsíce v UTC. Na zóně tu nezáleží: bereme oba měsíce
     * právě proto, aby na přelomu nebylo co pokazit.
     */
    private fun currentAndPreviousMonth(): List<String> {
        val today = clock.now().toLocalDateTime(TimeZone.UTC).date
        val previous = if (today.month.number == 1) today.year - 1 to 12 else today.year to today.month.number - 1
        return listOf(previous, today.year to today.month.number).map { (year, month) -> "%04d%02d".format(year, month) }
    }

    private suspend fun downloadOverview(
        bucket: String,
        prefix: String,
        token: String,
    ): List<PlayOverviewRow> {
        val media = bucketReader.list(bucket, prefix, token).firstOrNull()?.mediaLink ?: return emptyList()
        return PlayOverviewCsv.parse(bucketReader.download(bucket, media, token))
    }

    companion object {
        const val GCS_BASE_URL = "https://storage.googleapis.com"
        const val STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_only"
        const val OFFICIAL_PRIORITY = 100
    }
}

/** Jeden denní řádek z `…_overview.csv`. */
internal data class PlayOverviewRow(
    val date: LocalDate,
    val dailyAverage: Double?,
    val totalAverage: Double?,
)

/**
 * Parsování Play Console overview CSV.
 *
 * Dvě pasti, na kterých dnešní řešení stojí a padá: soubor je v **UTF-16LE** a název prvního
 * sloupce nese **BOM** (`Date`). n8n si s tím poradilo tím, že hledá klíč obsahující
 * „Date"; tady se BOM odstraní rovnou při dekódování.
 */
internal object PlayOverviewCsv {
    fun parse(bytes: ByteArray): List<PlayOverviewRow> {
        val rows = PlayCsv.rows(PlayCsv.decode(bytes))
        if (rows.size < 2) return emptyList()

        val header = rows.first()
        val dateColumn = PlayCsv.column(header, "Date").takeIf { it >= 0 } ?: return emptyList()
        val dailyColumn = PlayCsv.column(header, "Daily Average Rating")
        val totalColumn = PlayCsv.column(header, "Total Average Rating")

        return rows.drop(1).mapNotNull { cells ->
            val date =
                PlayCsv
                    .cell(cells, dateColumn)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            date?.let {
                PlayOverviewRow(
                    date = it,
                    dailyAverage = cells.getOrNull(dailyColumn)?.toDoubleOrNullSafe(),
                    totalAverage = cells.getOrNull(totalColumn)?.toDoubleOrNullSafe(),
                )
            }
        }
    }

    private fun String.toDoubleOrNullSafe(): Double? = trim().trim('"').replace(',', '.').toDoubleOrNull()
}
