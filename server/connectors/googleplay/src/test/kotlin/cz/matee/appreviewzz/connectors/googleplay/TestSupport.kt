package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.SecretPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64

/** Testovací service account s reálným RSA klíčem — podpis JWT tak jde ověřit. */
object TestServiceAccount {
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()

    const val CLIENT_EMAIL = "appreviewzz@islegrow.iam.gserviceaccount.com"
    const val TOKEN_URI = "https://oauth2.googleapis.com/token"

    val publicKey: PublicKey get() = keyPair.public

    fun payload(): SecretPayload {
        val pem =
            buildString {
                append("-----BEGIN PRIVATE KEY-----\\n")
                append(
                    Base64
                        .getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray())
                        .encodeToString(keyPair.private.encoded)
                        .replace("\n", "\\n"),
                )
                append("\\n-----END PRIVATE KEY-----\\n")
            }
        return SecretPayload(
            """
            {
              "type": "service_account",
              "project_id": "islegrow",
              "private_key_id": "abcdef0123456789",
              "private_key": "$pem",
              "client_email": "$CLIENT_EMAIL",
              "client_id": "123456789",
              "token_uri": "$TOKEN_URI"
            }
            """.trimIndent(),
        )
    }

    private const val KEY_SIZE_BITS = 2048
    private const val PEM_LINE_LENGTH = 64
}

fun fixture(name: String): String = requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "Fixture $name chybí" }.readText()

/**
 * MockEngine, který odpovídá podle cesty. Token endpoint obslouží vždy, ostatní volání
 * si test definuje sám — a zároveň se zaznamenávají, aby šlo tvrdit něco o requestech.
 */
class RecordingEngine(
    private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?,
) {
    val requests = mutableListOf<HttpRequestData>()
    var tokenRequests: Int = 0
        private set

    fun client(): HttpClient =
        googleHttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.toString().startsWith(TestServiceAccount.TOKEN_URI)) {
                    tokenRequests++
                    respond(
                        content = """{"access_token":"ya29.test-token","expires_in":3600,"token_type":"Bearer"}""",
                        headers = jsonHeaders,
                    )
                } else {
                    handler(request) ?: respond(
                        content = """{"error":{"code":418,"message":"Test nezná tuhle cestu: ${request.url}"}}""",
                        status = HttpStatusCode.fromValue(418),
                        headers = jsonHeaders,
                    )
                }
            },
        )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
