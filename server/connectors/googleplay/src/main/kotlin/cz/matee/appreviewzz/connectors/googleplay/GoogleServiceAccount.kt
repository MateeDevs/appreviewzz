package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private val serviceAccountJson = Json { ignoreUnknownKeys = true }

/**
 * Service account JSON klienta. Klient ho v consoli nahraje jako soubor, my z něj
 * potřebujeme jen e-mail (identita) a privátní klíč (podpis JWT).
 *
 * `toString` je redigovaný — objekt drží privátní klíč a nesmí se dostat do logu.
 */
class GoogleServiceAccount private constructor(
    val clientEmail: String,
    val projectId: String?,
    val tokenUri: String,
    val privateKey: PrivateKey,
) {
    override fun toString(): String = "GoogleServiceAccount(clientEmail=$clientEmail, projectId=$projectId)"

    companion object {
        private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

        fun parse(payload: SecretPayload): GoogleServiceAccount {
            val dto =
                try {
                    serviceAccountJson.decodeFromString<ServiceAccountDto>(payload.value)
                } catch (error: SerializationException) {
                    throw StoreConnectorException(
                        StoreErrorKind.AUTH,
                        "Service account není platný JSON z Google Cloud konzole",
                        error,
                    )
                }

            if (dto.type != "service_account") {
                throw StoreConnectorException(
                    StoreErrorKind.AUTH,
                    "Nahraný klíč má typ '${dto.type}', očekáván 'service_account'",
                )
            }
            val clientEmail =
                dto.clientEmail
                    ?: throw StoreConnectorException(StoreErrorKind.AUTH, "Service account nemá client_email")
            val privateKeyPem =
                dto.privateKey
                    ?: throw StoreConnectorException(StoreErrorKind.AUTH, "Service account nemá private_key")

            return GoogleServiceAccount(
                clientEmail = clientEmail,
                projectId = dto.projectId,
                tokenUri = dto.tokenUri ?: DEFAULT_TOKEN_URI,
                privateKey = parsePrivateKey(privateKeyPem),
            )
        }

        /** Nápověda do console — neutrální identifikátor, ze kterého klient pozná, který klíč nahrál. */
        fun hint(payload: SecretPayload): String? = runCatching { parse(payload).clientEmail }.getOrNull()

        private fun parsePrivateKey(pem: String): PrivateKey {
            val base64 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            return try {
                KeyFactory
                    .getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
            } catch (error: IllegalArgumentException) {
                throw StoreConnectorException(StoreErrorKind.AUTH, "private_key service accountu není platný PEM", error)
            } catch (error: InvalidKeySpecException) {
                throw StoreConnectorException(StoreErrorKind.AUTH, "private_key service accountu nejde načíst", error)
            }
        }
    }
}

@Serializable
private data class ServiceAccountDto(
    val type: String = "",
    @SerialName("client_email") val clientEmail: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("token_uri") val tokenUri: String? = null,
)
