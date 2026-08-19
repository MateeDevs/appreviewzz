package cz.matee.appreviewzz.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest

private val logger = KotlinLogging.logger {}

/**
 * KEK v AWS KMS — cesta pro náš provoz ([ADR 0008]). Klíč nikdy neopustí KMS; aplikace
 * volá jen `GenerateDataKey` a `Decrypt` a přesně na tyhle dvě operace je omezený i IAM
 * uživatel. Každé rozbalení je vidět v CloudTrailu, což je zdroj dat pro alarm na objem.
 */
class AwsKmsKekProvider(
    private val keyId: String,
    private val client: KmsClient,
) : KekProvider {
    override val uri: String get() = "$SCHEME$keyId"

    override fun generateDataKey(): DataKeyMaterial =
        try {
            val response =
                client.generateDataKey(
                    GenerateDataKeyRequest
                        .builder()
                        .keyId(keyId)
                        .keySpec(DataKeySpec.AES_256)
                        .build(),
                )
            DataKeyMaterial(
                plaintext = response.plaintext().asByteArray(),
                wrapped = response.ciphertextBlob().asByteArray(),
            )
        } catch (error: SdkException) {
            throw KeyManagementException("KMS nevydal datový klíč pro $keyId", error)
        }

    override fun unwrap(wrapped: ByteArray): ByteArray =
        try {
            client
                .decrypt(
                    DecryptRequest
                        .builder()
                        .keyId(keyId)
                        .ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                        .build(),
                ).plaintext()
                .asByteArray()
        } catch (error: SdkException) {
            throw KeyManagementException("KMS nerozbalil datový klíč klíčem $keyId", error)
        }

    companion object {
        const val SCHEME = "aws-kms://"

        fun fromUri(
            uri: String,
            clientFactory: () -> KmsClient = { KmsClient.create() },
        ): AwsKmsKekProvider {
            require(uri.startsWith(SCHEME)) { "KEK URI musí začínat $SCHEME, dostal jsem '$uri'" }
            val keyId = uri.removePrefix(SCHEME)
            require(keyId.isNotBlank()) { "KEK URI neobsahuje ARN klíče" }
            logger.info { "Vault používá AWS KMS klíč $keyId" }
            return AwsKmsKekProvider(keyId, clientFactory())
        }
    }
}
