package cz.matee.appreviewzz.backup

import cz.matee.appreviewzz.core.model.BackupStatus
import cz.matee.appreviewzz.persistence.Database
import cz.matee.appreviewzz.persistence.DatabaseConfig
import cz.matee.appreviewzz.persistence.repository.ExposedBackupRunRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * **Drill obnovy** (F1.8, akceptace F1). Ne test, že se zavolala nějaká metoda — celý kolotoč:
 * databáze s daty → `pg_dump` → úložiště → `pg_restore` do prázdné databáze → počty řádků.
 *
 * Právě tohle je ta část, kterou u záloh nikdo nedělá a která rozhoduje o tom, jestli zálohy
 * k něčemu jsou. V CI běží při každém buildu, takže se obnova nemůže tiše rozbít.
 */
class BackupDrillTest :
    FunSpec({
        val runnable = PgTools.available || PgTools.runningInCi

        test("zálohu jde nahrát do úložiště a obnovit z ní databázi").config(enabled = runnable) {
            check(PgTools.available) { PgTools.explanation }

            val container =
                PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                    withDatabaseName("appreviewzz_drill")
                    withUsername("appreviewzz")
                    withPassword("test")
                    start()
                }

            try {
                val database =
                    Database.connect(
                        DatabaseConfig(
                            jdbcUrl = container.jdbcUrl,
                            user = container.username,
                            password = container.password,
                            maxPoolSize = 2,
                        ),
                    )
                database.use {
                    database.migrate()

                    val target =
                        PostgresTarget.fromJdbcUrl(container.jdbcUrl, container.username, container.password)
                    seed(target)
                    val directory = tempdir().toPath()
                    val service =
                        BackupService(
                            target = target,
                            store = FileBackupStore(directory),
                            runs = ExposedBackupRunRepository(database.exposed),
                            commands = PostgresCommands(PgTools.pgDump, PgTools.pgRestore),
                        )

                    val run = service.backupNow()
                    run.status shouldBe BackupStatus.SUCCEEDED
                    run.sizeBytes.shouldNotBeNull() shouldBeGreaterThan 0L
                    run.checksum.shouldNotBeNull() shouldMatch Regex("[0-9a-f]{64}")

                    val stored = service.list()
                    stored shouldHaveSize 1

                    val report = service.restore(stored.first().key, "appreviewzz_obnova")
                    // Otisk se ověřuje proti historii běhů — jinak by obnova mohla brát poškozený soubor.
                    report.checksumVerified shouldBe true
                    report.schemaVersion.shouldNotBeNull()
                    report.rowCounts["organization"] shouldBe 2
                    report.rowCounts["app"] shouldBe 1
                    // Zálohovaná data musí přežít i obsahově, ne jen počtem řádků.
                    slugs(target.withDatabase("appreviewzz_obnova")) shouldBe listOf("druha", "prvni")
                }
            } finally {
                container.stop()
            }
        }
    })

private fun seed(target: PostgresTarget) {
    target.connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO organization (name, slug) VALUES ('První', 'prvni'), ('Druhá', 'druha');
                INSERT INTO app (org_id, name, gp_package_name)
                    SELECT id, 'Testovací appka', 'cz.matee.test' FROM organization WHERE slug = 'prvni';
                """.trimIndent(),
            )
        }
    }
}

private fun slugs(target: PostgresTarget): List<String> =
    target.connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT slug FROM organization ORDER BY slug").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

private fun PostgresTarget.connect(): Connection = DriverManager.getConnection(jdbcUrl(), user, password)
