package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private val apiKeyJson = Json { ignoreUnknownKeys = true }

/**
 * Klíč do App Store Connect API tak, jak ho klient dostane od Applu: Key ID, obsah
 * staženého `.p8` a — u týmových klíčů — Issuer ID.
 *
 * Apple rozlišuje dva druhy klíčů a **liší se claimy v JWT**:
 * - **týmový** (Users and Access → Integrations): má Issuer ID, do tokenu jde `iss`
 * - **individuální** (klíč konkrétního člověka): Issuer ID nemá, místo `iss` se posílá `sub: "user"`
 *
 * Dnešní n8n umí jen jednu z variant, což je důvod, proč některé klienty nešlo připojit.
 */
class AscApiKey private constructor(
    val keyId: String,
    val issuerId: String?,
    val privateKey: PrivateKey,
) {
    val isIndividual: Boolean get() = issuerId == null

    override fun toString(): String = "AscApiKey(keyId=$keyId, issuerId=$issuerId, individual=$isIndividual)"

    companion object {
        fun parse(payload: SecretPayload): AscApiKey {
            val dto =
                try {
                    apiKeyJson.decodeFromString<AscApiKeyDto>(payload.value)
                } catch (error: SerializationException) {
                    throw StoreConnectorException(
                        StoreErrorKind.AUTH,
                        "App Store credential musí být JSON s poli keyId, privateKey a volitelně issuerId",
                        error,
                    )
                }

            val keyId =
                dto.keyId?.takeIf { it.isNotBlank() }
                    ?: throw StoreConnectorException(StoreErrorKind.AUTH, "Chybí Key ID klíče z App Store Connect")
            val privateKeyPem =
                dto.privateKey?.takeIf { it.isNotBlank() }
                    ?: throw StoreConnectorException(StoreErrorKind.AUTH, "Chybí obsah staženého .p8 souboru")

            return AscApiKey(
                keyId = keyId,
                issuerId = dto.issuerId?.takeIf { it.isNotBlank() },
                privateKey = parsePrivateKey(privateKeyPem),
            )
        }

        /** Do console: Key ID stačí k rozpoznání klíče a nic neprozrazuje. */
        fun hint(payload: SecretPayload): String? = runCatching { "Key ID ${parse(payload).keyId}" }.getOrNull()

        private fun parsePrivateKey(pem: String): PrivateKey {
            val base64 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            return try {
                // .p8 od Applu je PKCS#8 s EC klíčem na křivce P-256.
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
            } catch (error: IllegalArgumentException) {
                throw StoreConnectorException(StoreErrorKind.AUTH, "Obsah .p8 souboru není platný PEM", error)
            } catch (error: InvalidKeySpecException) {
                throw StoreConnectorException(
                    StoreErrorKind.AUTH,
                    "Obsah .p8 souboru nejde načíst jako klíč App Store Connect",
                    error,
                )
            }
        }
    }
}

@Serializable
private data class AscApiKeyDto(
    val keyId: String? = null,
    val issuerId: String? = null,
    val privateKey: String? = null,
)
