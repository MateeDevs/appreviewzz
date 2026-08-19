package cz.matee.appreviewzz.app.cli

import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.DatabaseConfig
import cz.matee.appreviewzz.persistence.asDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Statement

/** Postgres pro testy seed CLI — CLI si otevírá vlastní pool, my držíme jen kontejner a úklid. */
object TestDatabase {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("appreviewzz_cli_test")
            .withUsername("appreviewzz")
            .withPassword("test")
            .also { it.start() }
    }

    val config: DatabaseConfig
        get() =
            DatabaseConfig(
                jdbcUrl = container.jdbcUrl,
                user = container.username,
                password = container.password,
                maxPoolSize = 2,
            )

    private val database: Database by lazy { Database.connect(config).also { it.migrate() } }

    fun reset() {
        database.asDataSource().connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement: Statement ->
                statement.execute(
                    """
                    TRUNCATE TABLE
                        scheduled_tasks, backup_run, audit_log, failed_job, rating_snapshot, reply, review_message,
                        review_revision, review, channel, app_credential, app,
                        credential, org_data_key, org_member, app_user, organization
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }
    }
}
