package cz.matee.appreviewzz.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 10,
    val migrateOnStart: Boolean = true,
)

/**
 * Připojení k Postgresu (HikariCP) + Flyway migrace.
 *
 * Doménové tabulky přicházejí ve F1; F0 zavádí jen baseline schéma a health probe.
 */
class Database private constructor(
    val dataSource: HikariDataSource,
) : AutoCloseable {
    companion object {
        fun connect(config: DatabaseConfig): Database {
            val hikari =
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.user
                    password = config.password
                    maximumPoolSize = config.maxPoolSize
                    isAutoCommit = false
                    poolName = "appreviewzz-pool"
                    // Bez explicitního timeoutu se startup zasekne na minutu; radši rychlé selhání.
                    connectionTimeout = 10_000
                    validationTimeout = 5_000
                }
            logger.info { "Connecting to database ${config.jdbcUrl} (pool=${config.maxPoolSize})" }
            return Database(HikariDataSource(hikari))
        }
    }

    fun migrate() {
        val result =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        val schemaVersion = result.targetSchemaVersion ?: "beze změny"
        logger.info { "Flyway: applied ${result.migrationsExecuted} migration(s), schema at $schemaVersion" }
    }

    /** Readiness probe — spotřebuje jedno spojení z poolu a ověří, že je živé. */
    fun isHealthy(): Boolean =
        runCatching {
            dataSource.connection.use { it.isValid(VALIDATION_TIMEOUT_SECONDS) }
        }.getOrElse { error ->
            logger.warn(error) { "Database health check failed" }
            false
        }

    override fun close() = dataSource.close()
}

private const val VALIDATION_TIMEOUT_SECONDS = 2

/** Pro moduly, které potřebují jen DataSource a ne celý lifecycle. */
fun Database.asDataSource(): DataSource = dataSource
