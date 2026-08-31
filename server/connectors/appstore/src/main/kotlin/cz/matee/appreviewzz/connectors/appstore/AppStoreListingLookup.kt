package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.AppListingSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Jméno appky z iTunes lookupu — táž cesta, jakou se berou hodnocení ([ITunesRatingsSource]),
 * jen se z odpovědi čte `trackName`.
 *
 * Používá se při přidávání aplikace v consoli: klient vloží odkaz z App Storu a název se
 * nabídne sám.
 */
class AppStoreListingLookup(
    private val httpClient: HttpClient,
    private val baseUrl: String = ITunesRatingsSource.ITUNES_BASE_URL,
    private val territories: List<String> = STOREFRONTS,
) : AppListingSource {
    override val platform: Platform = Platform.IOS

    /**
     * Storefronty se procházejí, dokud appku některý nezná: appka vydaná jen v Česku
     * v americkém storu není, a „nenašli jsme ji" by tu bylo zavádějící.
     */
    override suspend fun fetchName(identifier: String): String? {
        val appId = ITunesRatingsSource.numericAppId(identifier)
        return territories.firstNotNullOfOrNull { territory ->
            lookup(appId, territory)
                // Lookup podle ID najde i písničku nebo film — nabídnout jejich název jako
                // jméno appky by klienta jen zmátlo, tady je správná odpověď „nenašli jsme".
                ?.takeIf { it.wrapperType == null || it.wrapperType == SOFTWARE }
                ?.trackName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
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
                    parameter("entity", "software")
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "iTunes lookup je nedostupný", error)
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "iTunes lookup omezuje tempo")
        }
        if (!response.status.isSuccess()) {
            logger.info { "iTunes lookup pro $appId/$territory vrátil ${response.status.value}, přeskakuji" }
            return null
        }
        // Apple posílá odpověď jako `text/javascript` (dědictví po JSONP), takže se parsuje ručně.
        return runCatching { lookupJson.decodeFromString(LookupResponse.serializer(), response.bodyAsText()) }
            .getOrNull()
            ?.results
            ?.firstOrNull()
    }

    companion object {
        /**
         * Krátký seznam schválně: klient čeká u dialogu, takže dvacet storefrontů po řadě
         * není únosné. Kdo appku vydal jen jinde, si název napíše sám.
         */
        val STOREFRONTS = listOf("us", "cz", "gb", "de", "sk")

        private const val SOFTWARE = "software"
    }
}

private val lookupJson = Json { ignoreUnknownKeys = true }
