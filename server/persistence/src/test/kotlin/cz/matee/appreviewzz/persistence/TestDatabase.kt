package cz.matee.appreviewzz.persistence

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Statement

/**
 * Jeden Postgres kontejner pro celý testovací běh (Testcontainers ho zabije s JVM).
 * Testy si mezi sebou uklízejí přes [reset]; startovat kontejner per třídu by běh
 * natáhl o desítky sekund bez jakéhokoli přínosu.
 */
object TestDatabase {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("appreviewzz_test")
            .withUsername("appreviewzz")
            .withPassword("test")
            .also { it.start() }
    }

    val database: Database by lazy {
        Database
            .connect(
                DatabaseConfig(
                    jdbcUrl = container.jdbcUrl,
                    user = container.username,
                    password = container.password,
                    maxPoolSize = 4,
                ),
            ).also { it.migrate() }
    }

    /** Vyprázdní doménová data mezi testy; schéma zůstává, migrace se nepouštějí znovu. */
    fun reset() {
        database.dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement: Statement ->
                statement.execute(
                    """
                    TRUNCATE TABLE
                        audit_log, failed_job, rating_snapshot, reply, review_message,
                        review_revision, review, channel, app_credential, app,
                        credential, org_data_key, org_member, app_user, organization
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }
    }
}
