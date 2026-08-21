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
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Odpověď se parsuje ručně, ne přes content negotiation: Apple ji posílá s hlavičkou
 * `text/javascript` (dědictví po JSONP), takže by ji klient odmítl deserializovat — a zdroj
 * by tiše nevracel nic, zatímco by ho zastupoval scrape.
 */
private val lookupJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class LookupResponse(
    val resultCount: Int = 0,
    val results: List<LookupResult> = emptyList(),
)

@Serializable
internal data class LookupResult(
    val trackId: Long? = null,
    @SerialName("averageUserRating") val averageUserRating: Double? = null,
    @SerialName("userRatingCount") val userRatingCount: Long? = null,
)

/**
 * Průměr a počet hodnocení z **oficiálního iTunes lookup endpointu** (plán §5.4).
 *
 * Tohle je zásadní změna proti dnešku: n8n čísla dolovalo scrapem HTML stránky App Storu ve
 * dvaceti zemích a mělo na to tři fallback parsery, protože se layout mění. Lookup vrací
 * stabilní JSON, nepotřebuje žádný klíč a Apple ho udržuje — histogram po hvězdách sice nedává,
 * ale ten je jen doplněk ([AppStoreListingRatingsSource]).
 *
 * Vrací **řádek na storefront**. Součet přes ně dělá až volající, takže je v databázi vidět,
 * z čeho se globální číslo složilo — dnešní pipeline uloží jen sumu a rozpad je nenávratně pryč.
 */
class ITunesRatingsSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = ITUNES_BASE_URL,
    private val defaultTerritories: List<String> = DEFAULT_TERRITORIES,
) : RatingsSource {
    override val platform: Platform = Platform.IOS

    /** Oficiální data mají přednost před čímkoli vyscrapovaným. */
    override val priority: Int = OFFICIAL_PRIORITY

    override suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings> {
        val appId = numericAppId(context.appIdentifier)
        val territories = context.territories.ifEmpty { defaultTerritories }

        return territories.mapNotNull { territory ->
            val result = lookup(appId, territory)
            when {
                result == null -> null
                // Storefront, kde appka není vydaná, vrátí prázdný výsledek — to není chyba.
                result.userRatingCount == null && result.averageUserRating == null -> null
                else ->
                    ObservedRatings(
                        platform = Platform.IOS,
                        territory = territory.uppercase(),
                        average = result.averageUserRating,
                        totalCount = result.userRatingCount,
                        histogram = emptyMap(),
                        source = RatingSource.ITUNES_LOOKUP,
                    )
            }
        }
    }

    private suspend fun lookup(
        appId: String,
        territory: String,
    ): LookupResult? {
        val response =
            try {
                httpClient.get("$baseUrl/lookup") {
                    parameter("id", appId)
                    parameter("country", territory.lowercase())
                    // Bez tohohle vrací Apple lokalizované popisy, které nás nezajímají a jen
                    // nafukují odpověď na stovky kilobajtů.
                    parameter("entity", "software")
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "iTunes lookup je nedostupný", error)
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "iTunes lookup omezuje tempo")
        }
        if (!response.status.isSuccess()) {
            // Jeden nefunkční storefront nesmí shodit celý denní přehled.
            logger.info { "iTunes lookup pro $appId/$territory vrátil ${response.status.value}, přeskakuji" }
            return null
        }

        val body =
            try {
                lookupJson.decodeFromString(LookupResponse.serializer(), response.bodyAsText())
            } catch (error: Exception) {
                logger.info { "iTunes lookup pro $appId/$territory vrátil nečitelnou odpověď, přeskakuji" }
                return null
            }
        return body.results.firstOrNull { it.trackId == null || it.trackId.toString() == appId }
    }

    companion object {
        const val ITUNES_BASE_URL = "https://itunes.apple.com"
        const val OFFICIAL_PRIORITY = 100

        /**
         * Storefronty, ze kterých se počítá globální číslo. Je to tentýž seznam, jaký má dnes
         * natvrdo n8n — po migraci tak čísla nevyskočí jen kvůli změně metodiky.
         */
        val DEFAULT_TERRITORIES =
            listOf(
                "US",
                "GB",
                "CA",
                "AU",
                "NZ",
                "IE",
                "DE",
                "FR",
                "ES",
                "IT",
                "SE",
                "NO",
                "DK",
                "FI",
                "NL",
                "BE",
                "CH",
                "AT",
                "CZ",
                "SK",
            )

        /**
         * `id1490577875` i holé `1490577875` znamenají totéž; lookup chce jen číslo.
         * Klient přitom do console opisuje jednou tak a jednou tak.
         */
        internal fun numericAppId(identifier: String): String =
            identifier.trim().removePrefix("id").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: throw StoreConnectorException(
                    StoreErrorKind.INVALID_REQUEST,
                    "App Store ID '$identifier' není číslo ani tvar id<číslo>",
                )
    }
}
