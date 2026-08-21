package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

@Serializable
internal data class GcsListing(
    val items: List<GcsObject> = emptyList(),
)

@Serializable
internal data class GcsObject(
    val name: String? = null,
    @SerialName("mediaLink") val mediaLink: String? = null,
)

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
) : RatingsSource {
    override val platform: Platform = Platform.ANDROID

    override val priority: Int = OFFICIAL_PRIORITY

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        val bucket =
            context.reportingBucket
                ?.trim()
                ?.trimEnd('/')
                ?.removePrefix("gs://")
                ?.takeIf { it.isNotEmpty() }
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
        val listing =
            try {
                httpClient.get("$baseUrl/storage/v1/b/$bucket/o") {
                    bearerAuth(token)
                    parameter("prefix", prefix)
                    parameter("maxResults", LIST_LIMIT)
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage je nedostupné", error)
            }
        if (!listing.status.isSuccess()) throw listing.status.toConnectorException(bucket)

        val media =
            listing
                .body<GcsListing>()
                .items
                .firstOrNull()
                ?.mediaLink ?: return emptyList()
        val download =
            try {
                httpClient.get(media) { bearerAuth(token) }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage je nedostupné", error)
            }
        if (!download.status.isSuccess()) throw download.status.toConnectorException(bucket)

        return PlayOverviewCsv.parse(download.readRawBytes())
    }

    private fun HttpStatusCode.toConnectorException(bucket: String): StoreConnectorException =
        when {
            value == HttpStatusCode.Unauthorized.value || value == HttpStatusCode.Forbidden.value ->
                StoreConnectorException(
                    StoreErrorKind.AUTH,
                    "Service account nemá přístup k bucketu $bucket — přidej mu roli Storage Object Viewer",
                )

            value == HttpStatusCode.NotFound.value ->
                StoreConnectorException(StoreErrorKind.NOT_FOUND, "Bucket $bucket neexistuje")

            value == HttpStatusCode.TooManyRequests.value ->
                StoreConnectorException(StoreErrorKind.RATE_LIMITED, "Cloud Storage omezuje tempo")

            value >= HttpStatusCode.InternalServerError.value ->
                StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage vrátilo $value")

            else -> StoreConnectorException(StoreErrorKind.INVALID_REQUEST, "Cloud Storage odmítlo požadavek ($value)")
        }

    companion object {
        const val GCS_BASE_URL = "https://storage.googleapis.com"
        const val STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_only"
        const val OFFICIAL_PRIORITY = 100

        private const val LIST_LIMIT = 10
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
        val text = decode(bytes)
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()

        val header = split(lines.first()).map { it.trim().trim('"').removePrefix("﻿") }
        val dateColumn = header.indexOfFirst { it.equals("Date", ignoreCase = true) }.takeIf { it >= 0 } ?: return emptyList()
        val dailyColumn = header.indexOfFirst { it.equals("Daily Average Rating", ignoreCase = true) }
        val totalColumn = header.indexOfFirst { it.equals("Total Average Rating", ignoreCase = true) }

        return lines.drop(1).mapNotNull { line ->
            val cells = split(line)
            val date =
                cells
                    .getOrNull(dateColumn)
                    ?.trim()
                    ?.trim('"')
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

    /**
     * Play export je UTF-16LE s BOM. Kdo ho přečte jako UTF-8, dostane text prokládaný
     * nulovými bajty a všechny sloupce mu vyjdou prázdné — a průměr pak spadne na nulu.
     */
    private fun decode(bytes: ByteArray): String =
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

            // Bez BOM: sudý počet bajtů s nulou na liché pozici je taky UTF-16LE.
            bytes.size >= 2 && bytes.size % 2 == 0 && bytes[1] == 0.toByte() -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charsets.UTF_8)
        }

    private fun split(line: String): List<String> = line.trim().split(',')

    private fun String.toDoubleOrNullSafe(): Double? = trim().trim('"').replace(',', '.').toDoubleOrNull()
}
