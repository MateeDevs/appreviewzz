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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Hodnocení z veřejné stránky Play Storu — **fallback pro klienty, u kterých nemáme přístup
 * do Play Console** (a tedy ani do reportingového bucketu).
 *
 * Dnešní n8n tuhle cestu má taky, jenže ji nikdo nevolá: workflow je aktivní, ale bez volajícího.
 * Klient bez Play Console tak dneska denní přehled Androidu prostě nedostane. Tady je to
 * regulérní zdroj s nižší prioritou — použije se právě tehdy, když oficiální data nejsou.
 *
 * Na oplátku dává **histogram**, který oficiální overview CSV nemá; z něj se počítají „nová
 * hodnocení dnes" po hvězdách.
 */
class PlayStoreScrapeRatingsSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = PLAY_STORE_BASE_URL,
) : RatingsSource {
    override val platform: Platform = Platform.ANDROID

    override val priority: Int = SCRAPE_PRIORITY

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        val html = listing(context.appIdentifier) ?: return emptyList()
        val aggregate = PlayListingParser.aggregate(html)
        val histogram = PlayListingParser.histogram(html)
        if (aggregate == null && histogram == null) {
            logger.info { "Play listing pro ${context.appIdentifier} nešel přečíst; layout se nejspíš změnil" }
            return emptyList()
        }

        return listOf(
            ObservedRatings(
                platform = Platform.ANDROID,
                territory = ObservedRatings.GLOBAL,
                // Google zaokrouhluje na jedno desetinné místo; přesnější číslo má jen Play Console.
                average = aggregate?.average,
                totalCount = aggregate?.count ?: histogram?.values?.sum(),
                histogram = histogram.orEmpty(),
                source = RatingSource.GP_SCRAPE,
            ),
        )
    }

    private suspend fun listing(packageName: String): String? {
        val response =
            try {
                httpClient.get("$baseUrl/store/apps/details") {
                    parameter("id", packageName)
                    // Jeden jazyk a jedna země: Google jinak vrací čísla podle IP serveru
                    // a průměr by se v grafu měnil podle toho, odkud zrovna běžíme.
                    parameter("hl", "en")
                    parameter("gl", "US")
                    header("Accept-Language", "en-US,en;q=0.9")
                    header("Accept", "text/html,application/xhtml+xml")
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Play Store je nedostupný", error)
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "Play Store omezuje tempo")
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw StoreConnectorException(StoreErrorKind.NOT_FOUND, "Aplikace $packageName v Play Storu není")
        }
        if (!response.status.isSuccess()) {
            logger.info { "Play listing pro $packageName vrátil ${response.status.value}, hodnocení nebude" }
            return null
        }
        return response.bodyAsText()
    }

    companion object {
        const val PLAY_STORE_BASE_URL = "https://play.google.com"
        const val SCRAPE_PRIORITY = 50
    }
}

/** Průměr a počet z `ld+json` bloku stránky. */
internal data class PlayAggregate(
    val average: Double?,
    val count: Long?,
)

/**
 * Čtení čísel z HTML Play Storu. Oddělené od HTTP, aby šlo testovat nad uloženými fixtures —
 * je to ta část, která se rozbije, až Google přestaví stránku.
 */
internal object PlayListingParser {
    /**
     * Strukturovaná data (`schema.org/AggregateRating`) jsou jediná stabilní část stránky:
     * Google je tam drží kvůli vyhledávačům, takže se mění řádově míň než markup.
     */
    fun aggregate(html: String): PlayAggregate? {
        val blocks = LD_JSON.findAll(html).mapNotNull { runCatching { json.parseToJsonElement(it.groupValues[1]) }.getOrNull() }
        val rating =
            blocks
                .mapNotNull { (it as? JsonObject)?.get("aggregateRating")?.jsonObject }
                .firstOrNull()
                ?: return null
        return PlayAggregate(
            average = rating["ratingValue"]?.jsonPrimitive?.doubleOrNull,
            count = (rating["ratingCount"] ?: rating["reviewCount"])?.jsonPrimitive?.longOrNull,
        )
    }

    /**
     * Histogram z `aria-label`ů. Je to jediné místo na stránce, kde jsou počty po hvězdách
     * čitelné bez rozbalování interního JS blobu — a zároveň nejkřehčí věc celé pipeline,
     * takže je celý modul best-effort.
     *
     * Google popisek už jednou přeházel: dřív byl `"5 stars 12,345"`, dnes
     * `"12,345 reviews for star rating 5"`. Umíme oba, protože změna tvaru nesmí znamenat
     * tiše chybějící rozpad — a protože až ho přehází potřetí, bude to vidět na testu.
     */
    fun histogram(html: String): Map<Int, Long>? {
        val counts =
            (parse(html, CURRENT_STAR_LABEL, starsGroup = 2, votesGroup = 1) + parse(html, LEGACY_STAR_LABEL, 1, 2))
                .groupingBy { it.first }
                .fold(0L) { sum, (_, votes) -> sum + votes }
        return counts.takeIf { it.size == STARS && it.values.sum() > 0 }
    }

    private fun parse(
        html: String,
        pattern: Regex,
        starsGroup: Int,
        votesGroup: Int,
    ): List<Pair<Int, Long>> =
        pattern
            .findAll(html)
            .mapNotNull { match ->
                val stars = match.groupValues[starsGroup].toIntOrNull()?.takeIf { it in 1..STARS } ?: return@mapNotNull null
                // Čísla nesou oddělovače tisíců podle locale, včetně pevných a úzkých mezer.
                val votes = match.groupValues[votesGroup].filter(Char::isDigit).toLongOrNull() ?: return@mapNotNull null
                stars to votes
            }.toList()

    private const val STARS = 5
    private val LD_JSON =
        Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Dnešní tvar: „12,345 reviews for star rating 5". */
    private val CURRENT_STAR_LABEL =
        Regex("""aria-label="([\d\s  .,]+) reviews? for star rating (\d)"""")

    /** Starší tvar: „5 stars 12,345". Čísla nesou oddělovače tisíců podle locale. */
    private val LEGACY_STAR_LABEL = Regex("""aria-label="(\d) stars? ([\d\s  .,]+)"""")
}
