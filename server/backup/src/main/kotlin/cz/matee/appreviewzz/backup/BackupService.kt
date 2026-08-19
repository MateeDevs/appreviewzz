package cz.matee.appreviewzz.backup

import cz.matee.appreviewzz.core.model.BackupRun
import cz.matee.appreviewzz.core.model.BackupRunId
import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.core.port.BackupRunRepository
import cz.matee.appreviewzz.core.port.DatabaseBackup
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Retence. `keepAtLeast` je záměrná pojistka: kdyby zálohy z jakéhokoli důvodu přestaly
 * vznikat, samotné stáří by po `retentionDays` smazalo i tu poslední, kterou máme.
 */
data class BackupRetention(
    val days: Int = DEFAULT_DAYS,
    val keepAtLeast: Int = DEFAULT_KEEP_AT_LEAST,
) {
    init {
        require(days > 0) { "Retence musí být aspoň jeden den" }
        require(keepAtLeast > 0) { "Vždycky musí zůstat aspoň jedna záloha" }
    }

    companion object {
        const val DEFAULT_DAYS = 30
        const val DEFAULT_KEEP_AT_LEAST = 7
    }
}

/** Co obnova reálně přinesla — výstup ostrého drillu i ručního `backup restore`. */
data class RestoreReport(
    val key: String,
    val database: String,
    val sizeBytes: Long,
    /** `null` = k záloze nemáme záznam v historii, otisk se tedy nemá s čím porovnat. */
    val checksumVerified: Boolean?,
    val schemaVersion: String?,
    val rowCounts: Map<String, Long>,
)

/**
 * Zálohování databáze (F1.8): `pg_dump` → object storage → úklid starých záloh.
 *
 * Neúspěch **nehází výjimku ven** — zapíše se do historie a vrátí jako `FAILED`. Naplánovaná
 * úloha z toho udělá záznam v DLQ a metrika stáří poslední úspěšné zálohy začne růst;
 * tichý výpadek záloh je horší než hlasitá chyba.
 */
class BackupService(
    private val target: PostgresTarget,
    private val store: BackupStore,
    private val runs: BackupRunRepository,
    private val commands: PostgresCommands = PostgresCommands(),
    private val retention: BackupRetention = BackupRetention(),
    private val workDirectory: Path? = null,
    private val clock: Clock = Clock.System,
) : DatabaseBackup {
    override fun backupNow(): BackupRun {
        val startedAt = clock.now()
        val name = dumpName(target.database, startedAt)
        val workFile = workDirectory?.resolve(name) ?: Files.createTempFile("appreviewzz-backup", DUMP_SUFFIX)

        return try {
            val dump = commands.dump(target, workFile)
            val stored = store.put(name, dump.path)
            logger.info { "Záloha hotová: ${stored.location} (${dump.sizeBytes} B)" }
            prune()
            record(
                BackupRun(
                    id = BackupRunId(Uuid.random()),
                    startedAt = startedAt,
                    finishedAt = clock.now(),
                    status = BackupStatus.SUCCEEDED,
                    location = stored.location,
                    sizeBytes = dump.sizeBytes,
                    checksum = dump.sha256,
                    error = null,
                ),
            )
        } catch (error: Exception) {
            // Schválně široce: ať zálohu shodí cokoli, musí z toho být záznam v historii.
            // Kdyby byla nedostupná i databáze, poletí výjimka dál a vezme si ji scheduler.
            recordFailure(startedAt, error)
        } finally {
            workFile.deleteIfExists()
        }
    }

    /** Smaže zálohy starší než retence. Vrací klíče, které zmizely. */
    fun prune(now: Instant = clock.now()): List<String> {
        val expired = expiredBackups(store.list(), now, retention)
        expired.forEach { store.delete(it.key) }
        return expired.map { it.key }
    }

    fun list(): List<StoredBackup> = store.list()

    /**
     * Obnova do **vedlejší** databáze na téže instanci. Přepis běžícího provozu tudy nevede
     * schválně — ostrá obnova znamená obnovit vedle, ověřit a teprve pak přepnout aplikaci.
     */
    fun restore(
        key: String,
        databaseName: String,
        dropExisting: Boolean = false,
    ): RestoreReport {
        requireSafeDatabaseName(databaseName)
        require(databaseName != target.database) {
            "Do běžící databáze '${target.database}' se přes tenhle příkaz obnovovat nedá — obnov vedle a přepni aplikaci"
        }

        val downloaded = Files.createTempFile("appreviewzz-restore", DUMP_SUFFIX)
        return try {
            store.get(key, downloaded)
            val checksum = PostgresCommands.sha256(downloaded)
            val expected = runs.listRecent(HISTORY_LOOKUP).firstOrNull { it.location?.endsWith(key) == true }?.checksum
            if (expected != null && expected != checksum) {
                throw BackupStoreException("Otisk zálohy $key nesedí na historii — soubor se cestou změnil")
            }

            val restored = target.withDatabase(databaseName)
            Postgres.createDatabase(target, databaseName, dropExisting)
            commands.restore(restored, downloaded)

            RestoreReport(
                key = key,
                database = databaseName,
                sizeBytes = Files.size(downloaded),
                checksumVerified = expected?.let { true },
                schemaVersion = Postgres.schemaVersion(restored),
                rowCounts = Postgres.rowCounts(restored, VERIFIED_TABLES),
            )
        } finally {
            downloaded.deleteIfExists()
        }
    }

    private fun record(run: BackupRun): BackupRun = runs.record(run)

    private fun recordFailure(
        startedAt: Instant,
        error: Throwable,
    ): BackupRun {
        logger.error(error) { "Záloha databáze selhala" }
        return record(
            BackupRun(
                id = BackupRunId(Uuid.random()),
                startedAt = startedAt,
                finishedAt = clock.now(),
                status = BackupStatus.FAILED,
                location = null,
                sizeBytes = null,
                checksum = null,
                error = error.message ?: error::class.qualifiedName,
            ),
        )
    }

    companion object {
        /** Tabulky, jejichž počty řádků obnova vypisuje — kostra domény plus provozní stopa. */
        val VERIFIED_TABLES =
            listOf("organization", "app", "credential", "org_data_key", "review", "reply", "audit_log")

        private const val HISTORY_LOOKUP = 200
        private val SAFE_DATABASE_NAME = Regex("^[a-z][a-z0-9_]{0,62}$")

        /** `appreviewzz-2026-08-19T02-30-00Z.dump` — řadí se abecedně i chronologicky zároveň. */
        fun dumpName(
            database: String,
            at: Instant,
        ): String {
            val stamp =
                at
                    .toString()
                    .substringBefore('.')
                    .removeSuffix("Z")
                    .replace(":", "-")
            return "$database-${stamp}Z$DUMP_SUFFIX"
        }

        /**
         * Které zálohy jsou na smazání. Oddělené od úložiště schválně — je to jediné místo,
         * kde se rozhoduje o mazání dat, a chce být otestované bez S3 i bez disku.
         */
        fun expiredBackups(
            backups: List<StoredBackup>,
            now: Instant,
            retention: BackupRetention,
        ): List<StoredBackup> {
            val newestFirst = backups.sortedByDescending { it.createdAt }
            val threshold = now - retention.days.days
            return newestFirst.drop(retention.keepAtLeast).filter { it.createdAt < threshold }
        }

        private fun requireSafeDatabaseName(name: String) {
            require(SAFE_DATABASE_NAME.matches(name)) {
                "Jméno databáze '$name' není bezpečné; povolená jsou malá písmena, číslice a podtržítko"
            }
        }
    }
}
