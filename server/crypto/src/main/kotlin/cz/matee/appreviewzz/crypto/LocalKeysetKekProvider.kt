package cz.matee.appreviewzz.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * KEK v souboru — cesta pro self-host, kde nikdo nechce zakládat AWS účet.
 *
 * Soubor s master klíčem je **stejně kritický jako záloha databáze**: bez něj se credentials
 * nedají obnovit ani z korektní zálohy. Patří to do instalační dokumentace ([ADR 0005]).
 */
class LocalKeysetKekProvider private constructor(
    private val masterKey: ByteArray,
    private val path: Path,
) : KekProvider {
    override val uri: String get() = "local://$path"

    override fun generateDataKey(): DataKeyMaterial {
        val dek = ByteArray(Aead.KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        return DataKeyMaterial(plaintext = dek, wrapped = Aead.encrypt(masterKey, dek, WRAP_AAD))
    }

    override fun unwrap(wrapped: ByteArray): ByteArray =
        try {
            Aead.decrypt(masterKey, wrapped, WRAP_AAD)
        } catch (error: GeneralSecurityException) {
            throw KeyManagementException("Datový klíč nejde rozbalit lokálním keysetem ($path)", error)
        }

    companion object {
        private val WRAP_AAD = "appreviewzz:dek-wrap:v1".toByteArray()

        /**
         * Načte keyset, nebo ho při první instalaci vyrobí. Soubor dostane práva 600 —
         * na sdíleném serveru je čitelný keyset totéž jako plaintext credentials.
         */
        fun openOrCreate(path: Path): LocalKeysetKekProvider {
            if (!path.exists()) {
                path.parent?.createDirectories()
                val key = ByteArray(Aead.KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
                path.writeText(Base64.getEncoder().encodeToString(key))
                restrictPermissions(path)
                logger.warn {
                    "Vygenerován nový lokální keyset $path — zazálohuj ho, bez něj jsou credentials nenávratně ztracené"
                }
                return LocalKeysetKekProvider(key, path)
            }

            val decoded =
                try {
                    Base64.getDecoder().decode(path.readText().trim())
                } catch (error: IllegalArgumentException) {
                    throw KeyManagementException("Keyset $path není platný base64", error)
                }
            if (decoded.size != Aead.KEY_SIZE_BYTES) {
                throw KeyManagementException(
                    "Keyset $path má ${decoded.size} bajtů, očekáváno ${Aead.KEY_SIZE_BYTES}",
                )
            }
            return LocalKeysetKekProvider(decoded, path)
        }

        private fun restrictPermissions(path: Path) {
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }.onFailure {
                // Windows a exotické souborové systémy POSIX práva neumí; ať to kvůli tomu nepadá.
                logger.warn { "Nepodařilo se omezit práva keysetu $path na 600" }
            }
        }
    }
}
