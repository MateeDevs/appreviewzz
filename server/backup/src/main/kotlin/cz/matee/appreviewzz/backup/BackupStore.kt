package cz.matee.appreviewzz.backup

import java.nio.file.Path
import kotlin.time.Instant

/** Jedna záloha tak, jak ji vidí úložiště. */
data class StoredBackup(
    /** Klíč v rámci úložiště — tím se záloha adresuje při obnově i mazání. */
    val key: String,
    /** Čitelná adresa do logu a historie: `s3://bucket/klíč`, `file:///cesta`. */
    val location: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

/**
 * Kam se ukládají dumpy. Dvě implementace, protože máme dva provozy: náš (S3) a self-host
 * (adresář, typicky namontovaný na síťové úložiště).
 *
 * Chybějící mazání by z retence udělalo jen dobrou vůli, proto je součástí rozhraní —
 * u S3 ho sice dublují lifecycle pravidla, ale ta na disku self-hostera nikdo nemá.
 */
interface BackupStore {
    /** Popis do logu a nápovědy — nikdy neobsahuje přístupové údaje. */
    val description: String

    fun put(
        name: String,
        file: Path,
    ): StoredBackup

    /** Od nejnovější po nejstarší. */
    fun list(): List<StoredBackup>

    fun get(
        key: String,
        destination: Path,
    ): Path

    fun delete(key: String)
}

/** Úložiště nešlo otevřít, nebo operace nad ním selhala. */
class BackupStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
