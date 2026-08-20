package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.persistence.schema.AppCredentials
import cz.matee.appreviewzz.persistence.schema.Apps
import cz.matee.appreviewzz.persistence.schema.AuditLogs
import cz.matee.appreviewzz.persistence.schema.BackupRuns
import cz.matee.appreviewzz.persistence.schema.Channels
import cz.matee.appreviewzz.persistence.schema.Credentials
import cz.matee.appreviewzz.persistence.schema.FailedJobs
import cz.matee.appreviewzz.persistence.schema.OrgDataKeys
import cz.matee.appreviewzz.persistence.schema.OrgInvitations
import cz.matee.appreviewzz.persistence.schema.OrgMembers
import cz.matee.appreviewzz.persistence.schema.Organizations
import cz.matee.appreviewzz.persistence.schema.RatingSnapshots
import cz.matee.appreviewzz.persistence.schema.Replies
import cz.matee.appreviewzz.persistence.schema.ReviewMessages
import cz.matee.appreviewzz.persistence.schema.ReviewRevisions
import cz.matee.appreviewzz.persistence.schema.Reviews
import cz.matee.appreviewzz.persistence.schema.UserSessions
import cz.matee.appreviewzz.persistence.schema.UserTokens
import cz.matee.appreviewzz.persistence.schema.Users
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.jetbrains.exposed.v1.core.Table

/**
 * Pravda o schématu je Flyway migrace; Exposed objekty jsou jen typovaný pohled na ni.
 * Tenhle test hlídá, že se ty dva pohledy nerozešly — bez něj se překlep v názvu sloupce
 * projeví až runtime chybou v produkci.
 */
class SchemaConsistencyTest :
    FunSpec({
        val tables: List<Table> =
            listOf(
                Organizations,
                Users,
                UserSessions,
                UserTokens,
                OrgMembers,
                OrgInvitations,
                OrgDataKeys,
                Credentials,
                Apps,
                AppCredentials,
                Channels,
                Reviews,
                ReviewRevisions,
                ReviewMessages,
                Replies,
                RatingSnapshots,
                AuditLogs,
                FailedJobs,
                BackupRuns,
            )

        test("každý sloupec z Exposed definic existuje v databázi") {
            val database = TestDatabase.database
            val missing = mutableListOf<String>()

            database.dataSource.connection.use { connection ->
                tables.forEach { table ->
                    val actual = mutableSetOf<String>()
                    connection
                        .prepareStatement(
                            "SELECT column_name FROM information_schema.columns " +
                                "WHERE table_schema = 'public' AND table_name = ?",
                        ).use { statement ->
                            statement.setString(1, table.tableName)
                            statement.executeQuery().use { rows ->
                                while (rows.next()) actual += rows.getString(1)
                            }
                        }
                    table.columns.forEach { column ->
                        if (column.name !in actual) missing += "${table.tableName}.${column.name}"
                    }
                }
            }

            missing.shouldBeEmpty()
        }
    })
