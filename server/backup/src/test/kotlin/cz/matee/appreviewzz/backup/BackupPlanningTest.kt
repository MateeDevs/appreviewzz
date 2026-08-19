package cz.matee.appreviewzz.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Rozhodování kolem záloh bez databáze a bez úložiště — jméno souboru, adresa serveru
 * a hlavně **co se smaže**. Mazání je jediná nevratná operace celé F1.8, takže si zaslouží
 * test, který jde spustit kdykoli a kdekoli.
 */
class BackupPlanningTest :
    FunSpec({
        val now = Instant.parse("2026-08-19T02:30:00Z")

        fun backup(
            name: String,
            ageDays: Int,
        ) = StoredBackup(
            key = name,
            location = "file:///backups/$name",
            sizeBytes = 1024,
            createdAt = now - ageDays.days,
        )

        test("jméno dumpu nese databázi i čas a řadí se chronologicky") {
            BackupService.dumpName("appreviewzz", now) shouldBe "appreviewzz-2026-08-19T02-30-00Z.dump"
            BackupService.dumpName("appreviewzz", Instant.parse("2026-08-19T02:30:00.123456Z")) shouldBe
                "appreviewzz-2026-08-19T02-30-00Z.dump"
        }

        test("maže se jen to, co je za retencí") {
            val backups = listOf(backup("dnes", 0), backup("stara", 31), backup("starsi", 40))

            BackupService
                .expiredBackups(backups, now, BackupRetention(days = 30, keepAtLeast = 1))
                .map { it.key } shouldContainExactly listOf("stara", "starsi")
        }

        test("poslední zálohy zůstávají, i když jsou za retencí") {
            // Kdyby zálohy přestaly vznikat, samotné stáří by po měsíci smazalo i tu poslední.
            val backups = List(3) { index -> backup("zaloha-$index", 60 + index) }

            BackupService.expiredBackups(backups, now, BackupRetention(days = 30, keepAtLeast = 3)) shouldBe emptyList()
        }

        test("retence se nedá nastavit tak, aby nezbylo nic") {
            shouldThrow<IllegalArgumentException> { BackupRetention(days = 0) }
            shouldThrow<IllegalArgumentException> { BackupRetention(keepAtLeast = 0) }
        }

        test("z JDBC URL se vyčte host, port i databáze") {
            val target =
                PostgresTarget.fromJdbcUrl(
                    "jdbc:postgresql://postgres:5433/appreviewzz?sslmode=require",
                    user = "appreviewzz",
                    password = "tajne",
                )

            target.host shouldBe "postgres"
            target.port shouldBe 5433
            target.database shouldBe "appreviewzz"
            target.withDatabase("obnova").jdbcUrl() shouldBe "jdbc:postgresql://postgres:5433/obnova"
        }

        test("URL bez portu bere výchozích 5432, nesmysl neprojde") {
            PostgresTarget.fromJdbcUrl("jdbc:postgresql://db/appreviewzz", "u", "p").port shouldBe 5432
            shouldThrow<IllegalArgumentException> { PostgresTarget.fromJdbcUrl("postgres://db/app", "u", "p") }
            shouldThrow<IllegalArgumentException> { PostgresTarget.fromJdbcUrl("jdbc:postgresql://db", "u", "p") }
        }

        test("neznámé úložiště se pozná při startu, ne až u první noční zálohy") {
            shouldThrow<BackupStoreException> { BackupStores.fromUri("ftp://zalohy") }
            shouldThrow<BackupStoreException> { BackupStores.fromUri("s3://") }
        }
    })
