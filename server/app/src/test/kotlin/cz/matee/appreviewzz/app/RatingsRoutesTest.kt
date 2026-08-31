package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingSnapshotRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val OWNER = "vlastnik@example.com"
private const val SLUG = "matee"
private val NOW = Instant.parse("2026-08-21T09:00:00Z")

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/** Snapshoty se do databáze dostanou stejnou cestou jako z jobu — přes upsert repozitáře. */
private fun seedSnapshot(
    appId: String,
    date: LocalDate,
    average: Double,
    total: Long,
    platform: Platform = Platform.ANDROID,
    territory: String = "GLOBAL",
) {
    val exposed = TestDatabase.database.exposed
    val orgId: OrganizationId = checkNotNull(ExposedOrganizationRepository(exposed).findBySlug(SLUG)).id
    ExposedRatingSnapshotRepository(exposed).upsert(
        orgId,
        NewRatingSnapshot(
            appId = AppId(Uuid.parse(appId)),
            platform = platform,
            date = date,
            territory = territory,
            average = average,
            totalCount = total,
            source = RatingSource.GP_CSV,
        ),
        NOW,
    )
}

private suspend fun ApplicationTestBuilder.ownerWithApp(mailer: RecordingMailer): Pair<HttpClient, String> {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    val app =
        owner
            .postJson("/api/orgs/$SLUG/apps", """{"name":"IsleGrow","gpPackageName":"cz.matee.islegrow"}""")
            .bodyAsText()
            .jsonValue("id")
    return owner to app
}

class RatingsRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
        }

        "historie jde od nejstaršího a nese přírůstek mezi body" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithApp(mailer)
                seedSnapshot(appId, LocalDate(2026, 8, 19), 4.30, 1000)
                seedSnapshot(appId, LocalDate(2026, 8, 20), 4.35, 1020)
                seedSnapshot(appId, LocalDate(2026, 8, 21), 4.40, 1055)

                val body = owner.get("/api/orgs/$SLUG/apps/$appId/ratings").bodyAsText()

                // Graf se kreslí zleva doprava, takže nejstarší bod musí být první.
                (body.indexOf("2026-08-19") < body.indexOf("2026-08-21")) shouldBe true
                // U nejstaršího bodu není co odečíst, takže se přírůstek neposílá vůbec.
                body.substringBefore("2026-08-20") shouldNotContain "newCount"
                body shouldContain "\"newCount\":20"
                body shouldContain "\"newCount\":35"
                body shouldContain "\"change\":0.1"
            }
        }

        "do grafu jde jen globální řada, ne dvacet storefrontů" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithApp(mailer)
                seedSnapshot(appId, LocalDate(2026, 8, 20), 4.30, 1000)
                seedSnapshot(appId, LocalDate(2026, 8, 20), 4.90, 100, territory = "CZ")

                val body = owner.get("/api/orgs/$SLUG/apps/$appId/ratings").bodyAsText()

                body shouldContain "\"territory\":\"GLOBAL\""
                body shouldNotContain "\"territory\":\"CZ\""
            }
        }

        "appka jiné organizace není vidět" {
            testApplication {
                consoleModule(mailer)
                val (_, appId) = ownerWithApp(mailer)
                val stranger = browser()
                stranger.signUpVerified("cizi@example.com", mailer)

                stranger.get("/api/orgs/$SLUG/apps/$appId/ratings").status shouldBe HttpStatusCode.NotFound
            }
        }

        "ruční spuštění bez zdrojů hodnocení řekne větou, že není co poslat" {
            testApplication {
                consoleModule(mailer)
                val (owner, appId) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/ratings/run", "{}")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "nepodařilo načíst"
            }
        }
    })
