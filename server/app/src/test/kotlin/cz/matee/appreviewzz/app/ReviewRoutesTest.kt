package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val OWNER = "vlastnik@example.com"
private const val COLLEAGUE = "kolega@example.com"
private const val SLUG = "matee"
private val NOW = Instant.parse("2026-08-20T09:00:00Z")

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/** Recenze se do databáze dostanou stejnou cestou jako z ingestu — přes upsert repozitáře. */
private fun seedReview(
    orgSlug: String,
    appId: String,
    storeReviewId: String,
    stars: Int = 2,
    body: String = "Po aktualizaci to padá",
    state: ReviewState = ReviewState.NEW,
): String {
    val exposed = TestDatabase.database.exposed
    val orgId: OrganizationId = checkNotNull(ExposedOrganizationRepository(exposed).findBySlug(orgSlug)).id
    val observed =
        ObservedReview(
            platform = Platform.ANDROID,
            storeReviewId = storeReviewId,
            authorName = "Jana N.",
            starRating = stars,
            title = null,
            body = body,
            locale = "cs",
            territory = "CZ",
            appVersion = "3.1.0",
            device = "Pixel 8",
            submittedAt = NOW,
            storeUpdatedAt = null,
            developerResponseBody = null,
            developerResponseAt = null,
        )
    return ExposedReviewRepository(exposed)
        .upsert(orgId, AppId(Uuid.parse(appId)), observed, NOW, state)
        .review.id
        .toString()
}

private suspend fun ApplicationTestBuilder.ownerWithApp(mailer: RecordingMailer): Pair<HttpClient, String> {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    val app =
        owner
            .postJson("/api/orgs/$SLUG/apps", """{"name":"Testovací appka","gpPackageName":"cz.matee.test"}""")
            .bodyAsText()
            .jsonValue("id")
    return owner to app
}

/**
 * Review inbox, odpovídání z console, delivery health a audit log.
 *
 * Odpověď se zařazuje do fronty, ne publikuje v requestu — test proto kontroluje, co
 * do fronty spadlo, ne co odešlo do storu.
 */
class ReviewRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer
        lateinit var queue: RecordingReplyQueue

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
            queue = RecordingReplyQueue()
        }

        "inbox vrací recenze aplikace a umí filtrovat podle stavu" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(SLUG, appId, "gp-1")
                seedReview(SLUG, appId, "gp-2", stars = 5, body = "Paráda", state = ReviewState.NOTIFIED)

                val all = owner.get("/api/orgs/$SLUG/apps/$appId/reviews").bodyAsText()
                all shouldContain "Po aktualizaci to padá"
                all shouldContain "Paráda"

                val onlyNew = owner.get("/api/orgs/$SLUG/apps/$appId/reviews?state=NEW").bodyAsText()
                onlyNew shouldContain "Po aktualizaci to padá"
                onlyNew shouldNotContain "Paráda"

                owner.get("/api/orgs/$SLUG/apps/$appId/reviews?state=NESMYSL").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "detail recenze nese zprávy i odpovědi" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")

                val detail = owner.get("/api/orgs/$SLUG/reviews/$reviewId")
                detail.status shouldBe HttpStatusCode.OK
                val body = detail.bodyAsText()
                body shouldContain "\"starRating\":2"
                body shouldContain "\"messages\":[]"
                body shouldContain "\"replies\":[]"
            }
        }

        "odpověď z console jde do fronty i s autorem" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")

                val response =
                    owner.postJson(
                        "/api/orgs/$SLUG/reviews/$reviewId/reply",
                        """{"body":"Mrzí nás to, opravíme to ve verzi 3.1.1."}""",
                    )
                response.status shouldBe HttpStatusCode.Accepted
                response.bodyAsText() shouldContain "\"queued\":true"

                val queued = queue.queued.single()
                queued.reviewId shouldBe reviewId
                queued.body shouldContain "3.1.1"
                queued.authorDisplayName shouldBe "Tester"
            }
        }

        "dvojklik na odeslat druhou odpověď nezaloží" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")
                val body = """{"body":"Díky za zpětnou vazbu."}"""

                owner.postJson("/api/orgs/$SLUG/reviews/$reviewId/reply", body).bodyAsText() shouldContain "\"queued\":true"
                val second = owner.postJson("/api/orgs/$SLUG/reviews/$reviewId/reply", body)
                second.status shouldBe HttpStatusCode.Accepted
                second.bodyAsText() shouldContain "\"queued\":false"
            }
        }

        "prázdná odpověď se odmítne" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")

                owner
                    .postJson("/api/orgs/$SLUG/reviews/$reviewId/reply", """{"body":"   "}""")
                    .status shouldBe HttpStatusCode.BadRequest
                queue.queued shouldHaveSize 0
            }
        }

        "recenzi jde odložit, ale ne prohlásit za odpovězenou" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")

                owner
                    .patchJson("/api/orgs/$SLUG/reviews/$reviewId", """{"state":"IGNORED"}""")
                    .status shouldBe HttpStatusCode.OK
                owner.get("/api/orgs/$SLUG/apps/$appId/reviews?state=IGNORED").bodyAsText() shouldContain "gp-1"

                // REPLIED si drží pipeline; ručně by to byla lež o tom, co se stalo.
                val lie = owner.patchJson("/api/orgs/$SLUG/reviews/$reviewId", """{"state":"REPLIED"}""")
                lie.status shouldBe HttpStatusCode.BadRequest
                lie.bodyAsText() shouldContain "odložit"
            }
        }

        "člen recenze vidí i na ně odpovídá" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")
                owner.postJson("/api/orgs/$SLUG/invitations", """{"email":"$COLLEAGUE","role":"MEMBER"}""")
                val member = joinViaInvitation(mailer, COLLEAGUE)

                member.get("/api/orgs/$SLUG/apps/$appId/reviews").status shouldBe HttpStatusCode.OK
                member
                    .postJson("/api/orgs/$SLUG/reviews/$reviewId/reply", """{"body":"Díky, díváme se na to."}""")
                    .status shouldBe HttpStatusCode.Accepted
            }
        }

        "recenze cizí organizace není vidět ani přes přímé ID" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")

                val outsider = browser()
                outsider.signUpVerified("cizi@example.com", mailer)
                outsider.postJson("/api/orgs", """{"name":"Cizí"}""")

                outsider.get("/api/orgs/cizi/reviews/$reviewId").status shouldBe HttpStatusCode.NotFound
                outsider
                    .postJson("/api/orgs/cizi/reviews/$reviewId/reply", """{"body":"Podvod"}""")
                    .status shouldBe HttpStatusCode.NotFound
                queue.queued shouldHaveSize 0
                owner.get("/api/orgs/$SLUG/reviews/$reviewId").status shouldBe HttpStatusCode.OK
            }
        }

        "health ukáže, co chybí, aby recenze chodily" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(SLUG, appId, "gp-1")

                val health = owner.get("/api/orgs/$SLUG/health")
                health.status shouldBe HttpStatusCode.OK
                val body = health.bodyAsText()
                body shouldContain "\"name\":\"Testovací appka\""
                body shouldContain "\"pendingReviews\":1"
                // Kanál ani klíč zatím nejsou — přesně to má obrazovka říct.
                body shouldContain "\"channels\":[]"
                body shouldContain "\"credentials\":[]"
                body shouldContain "\"failedJobs\":[]"
            }
        }

        "audit log zaznamenal onboarding" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val (owner, appId) = ownerWithApp(mailer)
                val reviewId = seedReview(SLUG, appId, "gp-1")
                owner.patchJson("/api/orgs/$SLUG/reviews/$reviewId", """{"state":"IGNORED"}""")

                val audit = owner.get("/api/orgs/$SLUG/audit").bodyAsText()
                audit shouldContain "org.created"
                audit shouldContain "app.created"
                audit shouldContain "review.state_changed"
                audit shouldContain "\"actor\":\"Tester\""
            }
        }

        "bez přihlášení nevydá inbox ani health nic" {
            testApplication {
                consoleModule(mailer, replyQueue = queue)
                val anonymous = browser()
                anonymous.get("/api/orgs/$SLUG/health").status shouldBe HttpStatusCode.Unauthorized
                anonymous.get("/api/orgs/$SLUG/audit").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
