package cz.matee.appreviewzz.backup

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.time.Clock
import kotlin.time.toKotlinInstant

private val logger = KotlinLogging.logger {}

/**
 * Zálohy v S3 — náš provoz. Bucket zakládá terraform modul `backups`: verzování, zákaz
 * veřejného přístupu, TLS-only politika a lifecycle jako pojistka pro případ, že by aplikace
 * mazání starých záloh nestihla.
 *
 * Šifrování je SSE-S3, ne SSE-KMS: dumpy obsahují credentials už zabalené naším vault klíčem
 * a provoz přes KMS by navíc zašuměl CloudTrail metriku rozbalování klíčů (F1.9).
 */
class S3BackupStore(
    private val client: S3Client,
    private val bucket: String,
    prefix: String,
) : BackupStore {
    private val prefix = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override val description: String get() = "s3://$bucket/$prefix"

    override fun put(
        name: String,
        file: Path,
    ): StoredBackup {
        val key = "$prefix$name"
        val response =
            try {
                client.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                    RequestBody.fromFile(file),
                )
            } catch (error: SdkException) {
                throw BackupStoreException("Zálohu $name nešlo nahrát do s3://$bucket/$key", error)
            }
        logger.info { "Záloha nahrána do s3://$bucket/$key (etag ${response.eTag()})" }
        return StoredBackup(
            key = key,
            location = "s3://$bucket/$key",
            sizeBytes = file.toFile().length(),
            createdAt =
                kotlin.time.Clock.System
                    .now(),
        )
    }

    override fun list(): List<StoredBackup> {
        val result = mutableListOf<StoredBackup>()
        var continuation: String? = null
        do {
            val response =
                try {
                    client.listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .continuationToken(continuation)
                            .build(),
                    )
                } catch (error: SdkException) {
                    throw BackupStoreException("Obsah s3://$bucket/$prefix nejde vypsat", error)
                }
            response.contents().filter { it.key().endsWith(DUMP_SUFFIX) }.forEach { item ->
                result +=
                    StoredBackup(
                        key = item.key(),
                        location = "s3://$bucket/${item.key()}",
                        sizeBytes = item.size(),
                        createdAt = item.lastModified().toKotlinInstant(),
                    )
            }
            continuation = response.nextContinuationToken()
        } while (response.isTruncated == true)
        return result.sortedByDescending { it.createdAt }
    }

    override fun get(
        key: String,
        destination: Path,
    ): Path {
        // SDK odmítne psát do existujícího souboru, proto se cíl stahuje vedle a teprve pak přesune.
        val temporary = destination.resolveSibling("${destination.fileName}.download")
        try {
            temporary.deleteIfExists()
            client.getObject(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
                temporary,
            )
            temporary.copyTo(destination, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: SdkException) {
            throw BackupStoreException("Zálohu s3://$bucket/$key nejde stáhnout", error)
        } finally {
            temporary.deleteIfExists()
        }
        return destination
    }

    override fun delete(key: String) {
        try {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
            )
        } catch (error: SdkException) {
            throw BackupStoreException("Zálohu s3://$bucket/$key nešlo smazat", error)
        }
        logger.info { "Smazána stará záloha s3://$bucket/$key" }
    }
}
