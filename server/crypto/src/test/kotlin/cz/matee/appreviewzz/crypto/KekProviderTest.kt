package cz.matee.appreviewzz.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.DecryptRequest
import software.amazon.awssdk.services.kms.model.DecryptResponse
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse
import software.amazon.awssdk.services.kms.model.KmsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText

class KekProviderTest :
    FunSpec({
        test("lokální keyset se vyrobí při první instalaci a přežije restart") {
            val path = Files.createTempDirectory("keyset").resolve("appreviewzz.key")

            val first = LocalKeysetKekProvider.openOrCreate(path)
            val material = first.generateDataKey()

            val second = LocalKeysetKekProvider.openOrCreate(path)
            second.unwrap(material.wrapped).toList() shouldBe material.plaintext.toList()
            second.uri shouldBe "local://$path"
        }

        test("soubor s keysetem není čitelný pro ostatní uživatele") {
            val path = Files.createTempDirectory("keyset").resolve("appreviewzz.key")
            LocalKeysetKekProvider.openOrCreate(path)

            Files.getPosixFilePermissions(path) shouldBe
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }

        test("cizí keyset datový klíč nerozbalí") {
            val original = Files.createTempDirectory("keyset").resolve("a.key")
            val other = Files.createTempDirectory("keyset").resolve("b.key")
            val material = LocalKeysetKekProvider.openOrCreate(original).generateDataKey()

            shouldThrow<KeyManagementException> { LocalKeysetKekProvider.openOrCreate(other).unwrap(material.wrapped) }
        }

        test("poškozený keyset se pozná při startu, ne až při prvním credentialu") {
            val path = Files.createTempDirectory("keyset").resolve("rozbity.key")
            path.writeText("tohle rozhodně není klíč")

            shouldThrow<KeyManagementException> { LocalKeysetKekProvider.openOrCreate(path) }
        }

        test("KMS provider mapuje GenerateDataKey a Decrypt") {
            val client = FakeKmsClient()
            val provider = AwsKmsKekProvider.fromUri("aws-kms://arn:aws:kms:eu-central-1:1:key/abc") { client }

            val material = provider.generateDataKey()

            provider.uri shouldBe "aws-kms://arn:aws:kms:eu-central-1:1:key/abc"
            material.plaintext.size shouldBe Aead.KEY_SIZE_BYTES
            material.wrapped.toList() shouldNotBe material.plaintext.toList()
            provider.unwrap(material.wrapped).toList() shouldBe material.plaintext.toList()
            client.lastKeyId shouldBe "arn:aws:kms:eu-central-1:1:key/abc"
        }

        test("výpadek KMS se hlásí jako chyba správce klíčů, ne jako AWS výjimka") {
            val provider = AwsKmsKekProvider.fromUri("aws-kms://arn:test") { FailingKmsClient() }

            val error = shouldThrow<KeyManagementException> { provider.generateDataKey() }
            error.message shouldContain "arn:test"
        }

        test("URI vybírá providera a nesmysl padá hned") {
            val path = Files.createTempDirectory("keyset").resolve("z-uri.key")

            KekProviders.fromUri("local://$path").uri shouldBe "local://$path"
            shouldThrow<KeyManagementException> { KekProviders.fromUri("vault://transit/appreviewzz") }
            shouldThrow<KeyManagementException> { KekProviders.fromUri("smyslenej://klic") }
        }
    })

/** Napodobuje KMS: zabalení je prosté otočení pořadí bajtů, na mapování to stačí. */
private class FakeKmsClient : KmsClient {
    var lastKeyId: String? = null

    override fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse {
        lastKeyId = request.keyId()
        val plaintext = ByteArray(Aead.KEY_SIZE_BYTES) { index -> index.toByte() }
        return GenerateDataKeyResponse
            .builder()
            .keyId(request.keyId())
            .plaintext(SdkBytes.fromByteArray(plaintext))
            .ciphertextBlob(SdkBytes.fromByteArray(plaintext.reversedArray()))
            .build()
    }

    override fun decrypt(request: DecryptRequest): DecryptResponse {
        lastKeyId = request.keyId()
        return DecryptResponse
            .builder()
            .plaintext(SdkBytes.fromByteArray(request.ciphertextBlob().asByteArray().reversedArray()))
            .build()
    }

    override fun serviceName(): String = "kms"

    override fun close() = Unit
}

private class FailingKmsClient : KmsClient {
    override fun generateDataKey(request: GenerateDataKeyRequest): GenerateDataKeyResponse =
        throw KmsException.builder().message("Access denied").build()

    override fun decrypt(request: DecryptRequest): DecryptResponse = throw KmsException.builder().message("Access denied").build()

    override fun serviceName(): String = "kms"

    override fun close() = Unit
}
