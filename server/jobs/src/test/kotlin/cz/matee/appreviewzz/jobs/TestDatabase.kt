package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.DatabaseConfig
import cz.matee.appreviewzz.persistence.asDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Statement

/** Postgres pro testy plánovače — schéma včetně `scheduled_tasks` dodá Flyway z persistence modulu. */
object TestDatabase {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("appreviewzz_jobs_test")
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
                    maxPoolSize = 6,
                ),
            ).also { it.migrate() }
    }

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
