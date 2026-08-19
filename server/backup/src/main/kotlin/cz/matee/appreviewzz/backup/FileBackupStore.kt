package cz.matee.appreviewzz.backup

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.time.toKotlinInstant

private val logger = KotlinLogging.logger {}

/**
 * Zálohy v adresáři — výchozí cesta pro self-host (`file:///var/lib/appreviewzz/backups`).
 *
 * Adresář na témže stroji jako databáze **není záloha**; je to jen místo, odkud si ji odveze
 * rsync nebo namontované síťové úložiště. Runbook to říká nahlas, protože je to nejčastější
 * způsob, jak přijít o data i s vypečenými zálohami.
 */
class FileBackupStore(
    private val directory: Path,
) : BackupStore {
    override val description: String get() = "file://$directory"

    init {
        try {
            Files.createDirectories(directory)
        } catch (error: IOException) {
            throw BackupStoreException("Adresář pro zálohy $directory nejde vyrobit", error)
        }
    }

    override fun put(
        name: String,
        file: Path,
    ): StoredBackup {
        val destination = directory.resolve(name)
        try {
            // Přes dočasný soubor: přerušený zápis nesmí po sobě nechat něco, co vypadá jako záloha.
            val partial = directory.resolve("$name$PARTIAL_SUFFIX")
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            throw BackupStoreException("Zálohu $name nešlo uložit do $directory", error)
        }
        return destination.toStoredBackup()
    }

    override fun list(): List<StoredBackup> =
        try {
            Files.list(directory).use { paths ->
                paths
                    .asSequence()
                    .filter { it.name.endsWith(DUMP_SUFFIX) }
                    .map { it.toStoredBackup() }
                    .sortedByDescending { it.createdAt }
                    .toList()
            }
        } catch (error: IOException) {
            throw BackupStoreException("Adresář se zálohami $directory nejde přečíst", error)
        }

    override fun get(
        key: String,
        destination: Path,
    ): Path =
        try {
            Files.copy(directory.resolve(key), destination, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            throw BackupStoreException("Zálohu $key nejde přečíst z $directory", error)
        }

    override fun delete(key: String) {
        try {
            Files.deleteIfExists(directory.resolve(key))
            logger.info { "Smazána stará záloha $key" }
        } catch (error: IOException) {
            throw BackupStoreException("Zálohu $key nešlo smazat z $directory", error)
        }
    }

    private fun Path.toStoredBackup(): StoredBackup =
        StoredBackup(
            key = name,
            location = "file://${toAbsolutePath()}",
            sizeBytes = Files.size(this),
            createdAt = Files.getLastModifiedTime(this).toInstant().toKotlinInstant(),
        )

    private companion object {
        const val PARTIAL_SUFFIX = ".partial"
    }
}

/** Přípona dumpu; podle ní se v úložišti poznají naše zálohy od všeho ostatního. */
const val DUMP_SUFFIX = ".dump"
