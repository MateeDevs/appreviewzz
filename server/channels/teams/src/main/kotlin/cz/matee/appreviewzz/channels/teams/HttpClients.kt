package cz.matee.appreviewzz.channels.teams

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * HTTP klient pro Bot Framework a Microsoft Entra. `expectSuccess = false` je záměr: chybové
 * odpovědi si překládáme na [cz.matee.appreviewzz.core.port.ChannelException] sami, protože
 * na rozlišení „retry" versus „zavolej klienta" potřebujeme tělo odpovědi.
 */
fun teamsHttpClient(engine: HttpClientEngine = CIO.create()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }
    }

private const val REQUEST_TIMEOUT_MILLIS = 20_000L
private const val CONNECT_TIMEOUT_MILLIS = 10_000L
