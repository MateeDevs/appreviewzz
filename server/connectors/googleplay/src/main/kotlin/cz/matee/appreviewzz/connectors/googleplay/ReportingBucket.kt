package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GcsListing(
    val items: List<GcsObject> = emptyList(),
)

@Serializable
internal data class GcsObject(
    val name: String? = null,
    @SerialName("mediaLink") val mediaLink: String? = null,
)

/** Jméno bucketu tak, jak ho čekají volání GCS: bez `gs://`, bez koncového lomítka. */
internal fun normalizeBucket(raw: String?): String? =
    raw
        ?.trim()
        ?.removePrefix("gs://")
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }

/**
 * Čtení reportingového bucketu Play Console (`pubsite_prod_…`).
 *
 * Sdílené místo pro hodnocení i recenze: jsou to tytéž dvě volání Cloud Storage (výpis
 * objektů daného prefixu a stažení přes `mediaLink`) se stejným překladem HTTP stavů na
 * [StoreErrorKind]. Ten překlad je tu ta podstatná část — `403` na bucketu znamená chybějící
 * roli, kterou musí přidat člověk, kdežto `429` a `5xx` chtějí jen zkusit později.
 */
internal class ReportingBucketReader(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    /** Výpis objektů daného prefixu. Prázdný seznam znamená „nic takového tam neleží". */
    suspend fun list(
        bucket: String,
        prefix: String,
        token: String,
        limit: Int = LIST_LIMIT,
    ): List<GcsObject> {
        val listing =
            try {
                httpClient.get("$baseUrl/storage/v1/b/$bucket/o") {
                    bearerAuth(token)
                    parameter("prefix", prefix)
                    parameter("maxResults", limit)
                }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage je nedostupné", error)
            }
        if (!listing.status.isSuccess()) throw listing.status.toConnectorException(bucket)
        return listing.body<GcsListing>().items
    }

    suspend fun download(
        bucket: String,
        mediaLink: String,
        token: String,
    ): ByteArray {
        val download =
            try {
                httpClient.get(mediaLink) { bearerAuth(token) }
            } catch (error: Exception) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "Cloud Storage je nedostupné", error)
            }
        if (!download.status.isSuccess()) throw download.status.toConnectorException(bucket)
        return download.readRawBytes()
    }

    companion object {
        private const val LIST_LIMIT = 10
    }
}

internal fun HttpStatusCode.toConnectorException(bucket: String): StoreConnectorException =
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
