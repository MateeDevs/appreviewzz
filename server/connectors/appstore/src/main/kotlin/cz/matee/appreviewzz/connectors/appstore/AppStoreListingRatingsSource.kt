package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.RatingsSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Histogram hvězdiček z veřejného listingu App Storu.
 *
 * Je to **záměrně izolovaný a best-effort modul**: Apple histogram žádným API nedává, takže
 * jediná cesta je stránka storu, a ta se mění. Průměr a počet proto bere
 * [ITunesRatingsSource] z oficiálního endpointu a odsud se dopisuje jen rozpad po hvězdách,
 * ze kterého se počítají „nová hodnocení dnes".
 *
 * Když se parsování nepovede, vrátí prázdno a digest odejde bez rozpadu. To je celý rozdíl
 * proti dnešku, kde na tomhle parsování visí i samotný průměr — a s ním celý denní přehled.
 */
class AppStoreListingRatingsSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = APP_STORE_WEB_BASE_URL,
    private val defaultTerritories: List<String> = ITunesRatingsSource.DEFAULT_TERRITORIES,
) : RatingsSource {
    override val platform: Platform = Platform.IOS

    /** Doplněk k oficiálním datům, ne jejich náhrada. */
    override val priority: Int = SCRAPE_PRIORITY

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        val appId = ITunesRatingsSource.numericAppId(context.appIdentifier)
        val territories = context.territories.ifEmpty { defaultTerritories }

        return territories.mapNotNull { territory ->
            val histogram = histogram(appId, territory) ?: return@mapNotNull null
            ObservedRatings(
                platform = Platform.IOS,
                territory = territory.uppercase(),
                // Průměr z histogramu je odvozený; autoritativní je ten z lookupu.
                average = null,
                totalCount = null,
                histogram = histogram,
                source = RatingSource.ASC_LISTING,
            )
        }
    }

    private suspend fun histogram(
        appId: String,
        territory: String,
    ): Map<Int, Long>? {
        val response =
            try {
                httpClient.get("$baseUrl/${territory.lowercase()}/app/id$appId") {
                    // Bez běžné hlavičky vrací Apple jinou (osekanou) variantu stránky.
                    header("Accept-Language", "en-US,en;q=0.9")
                    header("Accept", "text/html,application/xhtml+xml")
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Listing App Storu je nedostupný", error)
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "Listing App Storu omezuje tempo")
        }
        if (!response.status.isSuccess()) {
            logger.info { "Listing App Storu pro $appId/$territory vrátil ${response.status.value}, histogram nebude" }
            return null
        }

        val parsed = AppStoreHistogramParser.parse(response.bodyAsText())
        if (parsed == null) {
            logger.info { "Histogram App Storu pro $appId/$territory se nepodařilo přečíst; layout se nejspíš změnil" }
        }
        return parsed
    }

    companion object {
        const val APP_STORE_WEB_BASE_URL = "https://apps.apple.com"
        const val SCRAPE_PRIORITY = 50
    }
}

/**
 * Vytažení `ratingCounts` ze stránky App Storu.
 *
 * Oddělené od HTTP schválně, aby šlo testovat nad uloženými fixtures — přesně tohle je ta
 * část, která se rozbije, až Apple přestaví stránku, a kontraktní test to má poznat dřív
 * než produkce.
 */
internal object AppStoreHistogramParser {
    /** Vrací počty po hvězdách 1..5, nebo `null`, když se v HTML nic použitelného nenašlo. */
    fun parse(html: String): Map<Int, Long>? {
        val blob = serializedServerData(html) ?: return regexFallback(html)
        val candidate = findRatingCounts(blob) ?: return regexFallback(html)
        return candidate.toHistogram()
    }

    /** `<script id="serialized-server-data" type="application/json">…</script>` — dnešní layout. */
    private fun serializedServerData(html: String): JsonElement? {
        val script = SERIALIZED_SERVER_DATA.find(html)?.groupValues?.get(1) ?: return null
        val cleaned =
            script
                .trim()
                // BOM a oddělovače řádků U+2028/2029 se v blobu občas objeví a JSON parser
                // je nemá rád; Apple je tam nechává ze serializace na straně webu.
                .removePrefix("\uFEFF")
                .replace("\u2028", "")
                .replace("\u2029", "")
                .unescapeHtml()
        return runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
    }

    /**
     * Prohledá strom a najde první objekt s polem `ratingCounts` o pěti číslech. Rekurze
     * místo hledání v konkrétní cestě je tady levnější: Apple už dvakrát přesunul, kde
     * ten objekt v blobu leží, ale jméno pole zůstává.
     */
    private fun findRatingCounts(element: JsonElement): Candidate? =
        when (element) {
            is JsonObject -> {
                val counts = element["ratingCounts"]?.let { numbers(it) }?.takeIf { it.size == STARS }
                if (counts != null) {
                    Candidate(counts, element["ratingAverage"]?.jsonPrimitive?.doubleOrNull)
                } else {
                    element.values.firstNotNullOfOrNull { findRatingCounts(it) }
                }
            }

            is JsonArray -> element.firstNotNullOfOrNull { findRatingCounts(it) }
            else -> null
        }

    private fun regexFallback(html: String): Map<Int, Long>? {
        val raw = RATING_COUNTS.find(html)?.groupValues?.get(1) ?: return null
        val counts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull()?.let(::round) }.takeIf { it.size == STARS } ?: return null
        // Bez sousedního průměru se orientace neověří; nový blob je 5★→1★, takže se otáčí.
        return Candidate(counts, null).toHistogram()
    }

    /**
     * Počty se čtou jako desetinná čísla a zaokrouhlují. Apple je občas pošle takhle
     * (`887628.0000000001`) — jako celé číslo je nepřečteš a histogram tiše zmizí, přestože
     * na stránce je. Zjištěno až na reálném listingu, ne na fixture.
     */
    private fun numbers(element: JsonElement): List<Long>? =
        (element as? JsonArray)?.map { round(it.jsonPrimitive.doubleOrNull ?: return null) }

    private fun round(value: Double): Long = Math.round(value)

    /**
     * Počty tak, jak přišly, plus průměr od Applu, když je po ruce.
     *
     * Orientace je jediná past celého parsování: pole je v dnešním blobu **od pěti hvězd
     * k jedné**. Když je vedle něj průměr, ověří se obojí pořadí a vybere to, které mu sedí;
     * bez průměru se otáčí, protože to je současné chování stránky.
     */
    private class Candidate(
        val counts: List<Long>,
        val average: Double?,
    ) {
        fun toHistogram(): Map<Int, Long>? {
            val ascending = counts.reversed()
            val ordered =
                when {
                    average == null -> ascending
                    distance(ascending, average) <= distance(counts, average) -> ascending
                    else -> counts
                }
            return ordered
                .mapIndexed { index, votes -> (index + 1) to votes }
                .toMap()
                .takeIf { it.values.sum() > 0 }
        }

        private fun distance(
            ordered: List<Long>,
            target: Double,
        ): Double {
            val total = ordered.sum().takeIf { it > 0 } ?: return Double.MAX_VALUE
            val weighted = ordered.mapIndexed { index, votes -> (index + 1).toLong() * votes }.sum()
            return kotlin.math.abs(weighted.toDouble() / total - target)
        }
    }

    private fun String.unescapeHtml(): String =
        replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private const val STARS = 5
    private val SERIALIZED_SERVER_DATA =
        Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val RATING_COUNTS = Regex(""""ratingCounts"\s*:\s*\[\s*([\d\s,.]+?)\s*]""")
}
