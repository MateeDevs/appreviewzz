package cz.matee.appreviewzz.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * HTTP klient pro AI providera. Timeout je krátký schválně: návrh odpovědi je pohodlí navíc,
 * takže radši zpráva bez návrhu než recenze, která visí minutu ve frontě doručení.
 */
fun aiHttpClient(
    engine: HttpClientEngine = CIO.create(),
    requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }
    }

private const val REQUEST_TIMEOUT_MILLIS = 20_000L

/**
 * Rozbor recenzí jede v dávkách po patnácti a je to dlouhá odpověď — dvacet vteřin jako
 * u návrhu odpovědi by dávku utnulo uprostřed. Doručení to nezdrží: rozbor běží mimo
 * horkou cestu do kanálu.
 */
const val ANALYSIS_TIMEOUT_MILLIS = 60_000L
private const val CONNECT_TIMEOUT_MILLIS = 5_000L
