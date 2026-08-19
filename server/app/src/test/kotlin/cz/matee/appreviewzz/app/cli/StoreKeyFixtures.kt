package cz.matee.appreviewzz.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.writeText

/**
 * Klíče pro testy se generují, ne kopírují z produkce. Konektory je reálně parsují
 * (RSA u service accountu, EC P-256 u `.p8`), takže fixture musí být opravdový klíč.
 */
object StoreKeyFixtures {
    fun serviceAccountFile(
        directory: Path,
        clientEmail: String = "reviews@isle-grow.iam.gserviceaccount.com",
    ): Path {
        val json =
            buildJsonObject {
                put("type", "service_account")
                put("project_id", "isle-grow")
                put("client_email", clientEmail)
                put("private_key", pem(generate("RSA")))
                put("token_uri", "https://oauth2.googleapis.com/token")
            }
        return write(directory.resolve("service-account.json"), Json.encodeToString(JsonObject.serializer(), json))
    }

    fun appStoreKeyFile(directory: Path): Path = write(directory.resolve("AuthKey.p8"), pem(generate("EC")))

    private fun write(
        path: Path,
        content: String,
    ): Path = path.also { it.writeText(content) }

    private fun generate(algorithm: String): PrivateKey =
        KeyPairGenerator
            .getInstance(algorithm)
            .apply {
                if (algorithm == "EC") initialize(ECGenParameterSpec("secp256r1")) else initialize(RSA_KEY_SIZE)
            }.generateKeyPair()
            .private

    private fun pem(key: PrivateKey): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(key.encoded) +
            "\n-----END PRIVATE KEY-----\n"

    private const val RSA_KEY_SIZE = 2048
    private const val PEM_LINE_LENGTH = 64
}
