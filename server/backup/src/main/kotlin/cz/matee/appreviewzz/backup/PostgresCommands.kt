package cz.matee.appreviewzz.backup

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/** `pg_dump`/`pg_restore` skončily jinak než úspěchem, nebo je vůbec nešlo spustit. */
class BackupToolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Hotový dump na disku — velikost a otisk se ukládají do historie a ověřují při obnově. */
data class DumpFile(
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Tenká obálka nad klientskými nástroji Postgresu. Zálohuje se **logicky** (`pg_dump -Fc`),
 * protože databáze běží v kontejneru bez PITR
 * ([ADR 0010](../../../../../../../docs/adr/0010-zalohy-pg-dump.md)).
 *
 * Formát `custom` se volí kvůli obnově: `pg_restore` z něj umí obnovit i jednotlivé tabulky
 * a nepotřebuje, aby cílová databáze měla stejné jméno ani stejného vlastníka.
 */
class PostgresCommands(
    private val pgDumpPath: String = DEFAULT_PG_DUMP,
    private val pgRestorePath: String = DEFAULT_PG_RESTORE,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    fun dump(
        target: PostgresTarget,
        destination: Path,
    ): DumpFile {
        logger.info { "pg_dump $target → $destination" }
        run(
            command =
                listOf(
                    pgDumpPath,
                    "--host=${target.host}",
                    "--port=${target.port}",
                    "--username=${target.user}",
                    "--dbname=${target.database}",
                    "--format=custom",
                    // Vlastník a granty se váží na role, které v cílové instanci nemusí existovat;
                    // bez nich projde obnova i do čerstvého kontejneru s jiným uživatelem.
                    "--no-owner",
                    "--no-privileges",
                    "--file=$destination",
                ),
            target = target,
            what = "pg_dump",
        )

        val size = Files.size(destination)
        if (size == 0L) throw BackupToolException("pg_dump vyrobil prázdný soubor — takovou zálohu brát nemůžeme")
        return DumpFile(path = destination, sizeBytes = size, sha256 = sha256(destination))
    }

    /**
     * Obnova do **prázdné** databáze s `--exit-on-error`. Bez toho skončí `pg_restore` nulou
     * i po desítkách chyb a „úspěšná" obnova by klidně mohla mít půlku tabulek pryč.
     */
    fun restore(
        target: PostgresTarget,
        source: Path,
    ) {
        logger.info { "pg_restore $source → $target" }
        run(
            command =
                listOf(
                    pgRestorePath,
                    "--host=${target.host}",
                    "--port=${target.port}",
                    "--username=${target.user}",
                    "--dbname=${target.database}",
                    "--no-owner",
                    "--no-privileges",
                    "--exit-on-error",
                    source.toString(),
                ),
            target = target,
            what = "pg_restore",
        )
    }

    private fun run(
        command: List<String>,
        target: PostgresTarget,
        what: String,
    ) {
        val process =
            try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .also { it.environment()["PGPASSWORD"] = target.password }
                    .start()
            } catch (error: IOException) {
                throw BackupToolException(
                    "$what nešel spustit (${command.first()}) — chybí v image klient Postgresu?",
                    error,
                )
            }

        // Výstup se čte průběžně; kdyby se naplnil buffer roury, proces by se zasekl.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BackupToolException("$what běžel přes $timeout a byl ukončen")
        }
        if (process.exitValue() != 0) {
            throw BackupToolException("$what skončil kódem ${process.exitValue()}: ${output.tail()}")
        }
        if (output.isNotBlank()) logger.debug { "$what: ${output.tail()}" }
    }

    companion object {
        const val DEFAULT_PG_DUMP = "pg_dump"
        const val DEFAULT_PG_RESTORE = "pg_restore"
        val DEFAULT_TIMEOUT: Duration = 30.minutes

        private const val OUTPUT_TAIL_CHARS = 2000

        fun sha256(path: Path): String =
            Files.newInputStream(path).use { stream ->
                val digest = DigestInputStream(stream, MessageDigest.getInstance("SHA-256"))
                digest.drain()
                digest.messageDigest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }

        private fun InputStream.drain() {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            @Suppress("ControlFlowWithEmptyBody")
            while (read(buffer) != -1) {
                // Digest si obsah bere sám při čtení; tady jde jen o to dojet na konec souboru.
            }
        }

        private fun String.tail(): String = takeLast(OUTPUT_TAIL_CHARS).trim()
    }
}
