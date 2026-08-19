package cz.matee.appreviewzz.backup

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

/**
 * Pár dotazů kolem obnovy, které `pg_restore` neumí: založit cílovou databázi a po obnově
 * se podívat, co v ní vlastně je. Jde přes obyčejné JDBC, protože obnova běží mimo aplikační
 * pool — typicky z CLI, na databázi, která v tu chvíli ještě neexistuje.
 */
internal object Postgres {
    /**
     * Založí databázi pro obnovu. Připojuje se do provozní databáze, ne do `postgres` —
     * aplikační uživatel na `postgres` právo mít nemusí, do své vlastní se dostane vždycky.
     */
    fun createDatabase(
        target: PostgresTarget,
        name: String,
        dropExisting: Boolean,
    ) {
        connect(target).use { connection ->
            connection.createStatement().use { statement ->
                if (dropExisting) {
                    logger.warn { "Zahazuji databázi \"$name\" před obnovou" }
                    // FORCE odpojí případné zbylé session; jinak DROP spadne na „database is being accessed".
                    statement.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)")
                }
                statement.execute("CREATE DATABASE \"$name\"")
            }
        }
        logger.info { "Databáze \"$name\" je připravená pro obnovu" }
    }

    fun schemaVersion(target: PostgresTarget): String? =
        connect(target).use { connection ->
            if (!tableExists(connection, "flyway_schema_history")) return null
            connection
                .prepareStatement(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                ).use { statement ->
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
        }

    fun rowCounts(
        target: PostgresTarget,
        tables: List<String>,
    ): Map<String, Long> =
        connect(target).use { connection ->
            tables
                .filter { tableExists(connection, it) }
                .associateWith { table ->
                    // Jméno tabulky je z našeho seznamu konstant, ne ze vstupu — proto smí do SQL.
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM \"$table\"").use { rows ->
                            if (rows.next()) rows.getLong(1) else 0L
                        }
                    }
                }
        }

    private fun tableExists(
        connection: Connection,
        table: String,
    ): Boolean =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, "public.$table")
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun connect(target: PostgresTarget): Connection =
        try {
            DriverManager.getConnection(target.jdbcUrl(), target.user, target.password)
        } catch (error: SQLException) {
            throw BackupStoreException("Nejde se připojit k $target", error)
        }
}
