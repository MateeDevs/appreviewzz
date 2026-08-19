package cz.matee.appreviewzz.backup

import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Path

/**
 * Výběr úložiště podle URI z konfigurace (`BACKUP_TARGET`):
 *
 * - `s3://bucket/prefix` — náš provoz i každý, kdo má S3 kompatibilní úložiště
 * - `file:///var/lib/appreviewzz/backups` — self-host
 *
 * `BACKUP_S3_ENDPOINT` přepne klienta na jiné S3 (MinIO, Backblaze, Wasabi) — proto path-style
 * adresace, virtual-host styl by u takových služeb potřeboval wildcard DNS.
 */
object BackupStores {
    private const val S3_SCHEME = "s3://"
    private const val FILE_SCHEME = "file://"

    fun fromUri(
        uri: String,
        endpoint: String? = null,
        clientFactory: (String?) -> S3Client = ::defaultS3Client,
    ): BackupStore =
        when {
            uri.startsWith(S3_SCHEME) -> {
                val withoutScheme = uri.removePrefix(S3_SCHEME).trim('/')
                val bucket = withoutScheme.substringBefore('/')
                if (bucket.isBlank()) throw BackupStoreException("BACKUP_TARGET '$uri' neobsahuje jméno bucketu")
                S3BackupStore(clientFactory(endpoint), bucket, withoutScheme.substringAfter('/', ""))
            }

            uri.startsWith(FILE_SCHEME) -> {
                val path = uri.removePrefix(FILE_SCHEME)
                if (path.isBlank()) throw BackupStoreException("BACKUP_TARGET '$uri' neobsahuje cestu")
                FileBackupStore(Path.of(path))
            }

            else ->
                throw BackupStoreException(
                    "Neznámé BACKUP_TARGET '$uri'; podporováno: $S3_SCHEME, $FILE_SCHEME",
                )
        }

    private fun defaultS3Client(endpoint: String?): S3Client =
        S3Client
            .builder()
            .apply {
                if (!endpoint.isNullOrBlank()) {
                    endpointOverride(URI.create(endpoint))
                    forcePathStyle(true)
                }
            }.build()
}
