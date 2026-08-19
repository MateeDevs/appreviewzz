package cz.matee.appreviewzz.connectors.appstore

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
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Testovací klíč na křivce P-256, stejně jako `.p8` od Applu — podpis JWT jde ověřit. */
object TestAscKey {
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    const val KEY_ID = "ABCD1234EF"
    const val ISSUER_ID = "69a6de70-0000-47e3-e053-5b8c7c11a4d1"

    val publicKey: PublicKey get() = keyPair.public

    /** Týmový klíč — má Issuer ID. */
    fun teamKey(): SecretPayload =
        SecretPayload(
            """{"keyId":"$KEY_ID","issuerId":"$ISSUER_ID","privateKey":"${pem()}"}""",
        )

    /** Individuální klíč — Issuer ID nemá, JWT musí použít sub="user". */
    fun individualKey(): SecretPayload = SecretPayload("""{"keyId":"$KEY_ID","privateKey":"${pem()}"}""")

    private fun pem(): String =
        "-----BEGIN PRIVATE KEY-----\\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\\n-----END PRIVATE KEY-----\\n"
}

fun fixture(name: String): String = requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "Fixture $name chybí" }.readText()

/** MockEngine se záznamem requestů — App Store nemá token endpoint, takže stačí jeden handler. */
class RecordingEngine(
    private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?,
) {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient =
        appStoreHttpClient(
            MockEngine { request ->
                requests += request
                handler(request) ?: respond(
                    content = """{"errors":[{"status":"418","title":"Test nezná cestu ${request.url}"}]}""",
                    status = HttpStatusCode.fromValue(418),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
}
