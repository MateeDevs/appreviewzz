package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.AppListingSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient

private val logger = KotlinLogging.logger {}

/**
 * Jméno appky z veřejné stránky Play Storu — jediné, co se dá o balíčku zjistit bez klíče.
 *
 * Používá se při přidávání aplikace v consoli: klient vloží odkaz ze storu a nemusí nic
 * opisovat. Když se stránka nepřečte, není to chyba k řešení — jméno si vyplní sám.
 */
class PlayStoreListingLookup(
    private val httpClient: HttpClient,
    private val baseUrl: String = PlayStoreScrapeRatingsSource.PLAY_STORE_BASE_URL,
) : AppListingSource {
    override val platform: Platform = Platform.ANDROID

    override suspend fun fetchName(identifier: String): String? {
        val html =
            PlayListing.fetch(httpClient, baseUrl, identifier) { status ->
                logger.info { "Play listing pro $identifier vrátil $status, jméno nebude" }
            } ?: return null
        return PlayListingParser.name(html)
    }
}
