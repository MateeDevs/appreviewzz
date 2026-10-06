package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.PublicReviewSource
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}
private val feedJson = Json { ignoreUnknownKeys = true }

/**
 * Veřejné recenze z App Store RSS feedu (C4, sledování konkurence).
 *
 * Feed je to jediné, co Apple ukazuje bez klíče: posledních 50 recenzí na stránku, nejvýš
 * deset stránek, po storefrontech. Pro konkurenci to stačí — zajímá nás, na co si lidé
 * stěžují, ne úplná historie. Odpovědi vývojáře feed nenese; verze a datum ano.
 *
 * Tvar odpovědi je dědictví po Atomu převedeném do JSON: každá hodnota je `{ "label": … }`
 * a `entry` je pole, **nebo objekt, když je recenze jen jedna**, nebo chybí úplně. Proto
 * se parsuje ručně přes [JsonElement], ne přes datové třídy.
 */
class AppStoreRssReviewSource(
    private val httpClient: HttpClient,
    private val baseUrl: String = ITunesRatingsSource.ITUNES_BASE_URL,
    private val defaultTerritories: List<String> = DEFAULT_TERRITORIES,
    /** Stránek na storefront při jednom běhu. Ingest jede každou půlhodinu, dvě stránky stačí. */
    private val pages: Int = DEFAULT_PAGES,
) : PublicReviewSource {
    override val platform: Platform = Platform.IOS

    override suspend fun fetchPublicReviews(
        appIdentifier: String,
        territories: List<String>,
    ): List<ObservedReview> {
        val appId = appIdentifier.removePrefix("id").trim()
        require(appId.all { it.isDigit() } && appId.isNotEmpty()) { "App Store ID musí být číslo, ne '$appIdentifier'" }
        val storefronts = territories.ifEmpty { defaultTerritories }

        return storefronts
            .flatMap { territory ->
                (1..pages).flatMap { page -> fetchPage(appId, territory, page) }
            }
            // Táž recenze se objeví ve víc storefrontech jen výjimečně, ale ID je globální —
            // druhý výskyt by upsert stejně sloučil, tady se jen ušetří práce.
            .distinctBy { it.storeReviewId }
    }

    private suspend fun fetchPage(
        appId: String,
        territory: String,
        page: Int,
    ): List<ObservedReview> {
        val url = "$baseUrl/${territory.lowercase()}/rss/customerreviews/page=$page/id=$appId/sortby=mostrecent/json"
        val response =
            try {
                httpClient.get(url)
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "App Store RSS feed je nedostupný", error)
            }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw StoreConnectorException(StoreErrorKind.RATE_LIMITED, "App Store RSS feed omezuje tempo")
        }
        if (!response.status.isSuccess()) {
            // Storefront, kde appka není, vrací 4xx — to není chyba, jen prázdná země.
            logger.info { "App Store RSS pro $appId/$territory (strana $page) vrátil ${response.status.value}, přeskakuji" }
            return emptyList()
        }
        val root =
            try {
                feedJson.parseToJsonElement(response.bodyAsText())
            } catch (error: Exception) {
                logger.info { "App Store RSS pro $appId/$territory vrátil nečitelnou odpověď, přeskakuji" }
                return emptyList()
            }
        return entries(root).mapNotNull { it.toObservedReview(territory) }
    }

    private fun entries(root: JsonElement): List<JsonObject> {
        val entry = (root as? JsonObject)?.get("feed")?.jsonObject?.get("entry") ?: return emptyList()
        return when (entry) {
            is JsonArray -> entry.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(entry)
            else -> emptyList()
        }
    }

    private fun JsonObject.label(key: String): String? =
        (this[key] as? JsonObject)
            ?.get("label")
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }

    /** Řádek bez ID nebo hvězd se přeskočí — bez nich není co ukládat, a feed je občas má prázdné. */
    private fun JsonObject.toObservedReview(territory: String): ObservedReview? {
        val id = label("id") ?: return null
        val stars = label("im:rating")?.toIntOrNull()?.takeIf { it in 1..5 } ?: return null
        val submittedAt =
            label("updated")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
        return ObservedReview(
            platform = Platform.IOS,
            storeReviewId = "rss:$id",
            authorName =
                (this["author"] as? JsonObject)?.let { author ->
                    (author["name"] as? JsonObject)?.get("label")?.jsonPrimitive?.content
                },
            starRating = stars,
            title = label("title"),
            body = label("content"),
            locale = null,
            territory = territory.uppercase(),
            appVersion = label("im:version"),
            device = null,
            submittedAt = submittedAt,
            // Feed nese jen jedno datum; editaci recenze poznáme otiskem obsahu, ne časem.
            storeUpdatedAt = null,
            developerResponseBody = null,
            developerResponseAt = null,
        )
    }

    companion object {
        const val DEFAULT_PAGES = 2

        /**
         * Storefronty pro konkurenci: domácí trh plus největší anglické a německé — tam se
         * píše nejvíc. Nebere se celý seznam z [ITunesRatingsSource], protože každý storefront
         * jsou dva požadavky na běh a konkurence nestojí za stovky dotazů za hodinu.
         */
        val DEFAULT_TERRITORIES = listOf("CZ", "SK", "US", "GB", "DE")
    }
}
