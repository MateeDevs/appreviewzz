package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewInsightRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val OWNER = "vlastnik@example.com"
private const val SLUG = "matee"
private val NOW = Instant.parse("2026-09-03T09:00:00Z")

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

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

/** Recenze i její výklad se do databáze dostanou stejnou cestou jako z ingestu a z rozboru. */
private fun seedReview(
    appId: String,
    storeReviewId: String,
    body: String,
    topics: List<TopicMention>,
    type: ReviewType = ReviewType.BUG,
    urgency: Urgency = Urgency.HIGH,
    sentiment: OverallSentiment = OverallSentiment.NEGATIVE,
    withInsight: Boolean = true,
): String {
    val exposed = TestDatabase.database.exposed
    val orgId: OrganizationId = checkNotNull(ExposedOrganizationRepository(exposed).findBySlug(SLUG)).id
    val review =
        ExposedReviewRepository(exposed)
            .upsert(
                orgId,
                AppId(Uuid.parse(appId)),
                ObservedReview(
                    platform = Platform.ANDROID,
                    storeReviewId = storeReviewId,
                    authorName = "Jana N.",
                    starRating = 2,
                    title = null,
                    body = body,
                    locale = "cs",
                    territory = "CZ",
                    appVersion = "3.2.0",
                    device = "Pixel 8",
                    submittedAt = NOW,
                    storeUpdatedAt = null,
                    developerResponseBody = null,
                    developerResponseAt = null,
                ),
                NOW,
                ReviewState.NEW,
            ).review
    if (withInsight) {
        ExposedReviewInsightRepository(exposed).upsert(
            orgId,
            NewReviewInsight(
                reviewId = review.id,
                appId = review.appId,
                contentHash = review.contentHash,
                taxonomyVersion = Topic.TAXONOMY_VERSION,
                promptVersion = "2026-09-v1",
                model = "gemini-2.5-flash-lite",
                sentiment = sentiment,
                type = type,
                urgency = urgency,
                language = "cs",
                translation = null,
                topics = topics,
            ),
            NOW,
        )
    }
    return review.id.toString()
}

/**
 * Rozbory recenzí v API: štítky u recenze, filtry inboxu, vlastní témata a stav výkladů.
 * Filtr je tu ta zajímavá část — je to jediná cesta, jak se klient dostane od „třicet
 * recenzí" k „těmhle devíti o pádech".
 */
class AnalysisRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer
        lateinit var analysis: RecordingAnalysisQueue

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
            analysis = RecordingAnalysisQueue()
        }

        "inbox nese štítky s českým názvem tématu i citátem" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(
                    appId,
                    "gp-1",
                    "Po aktualizaci to padá",
                    listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "to padá")),
                )

                val body = owner.get("/api/orgs/$SLUG/apps/$appId/reviews").bodyAsText()

                body shouldContain "\"key\":\"crash\""
                body shouldContain "\"name\":\"Pády\""
                body shouldContain "\"quote\":\"to padá\""
                body shouldContain "\"urgency\":\"HIGH\""
            }
        }

        "filtr podle tématu vrátí jen recenze, které o něm mluví" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(
                    appId,
                    "gp-1",
                    "Po aktualizaci to padá",
                    listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
                )
                seedReview(
                    appId,
                    "gp-2",
                    "Je to drahé",
                    listOf(TopicMention(Topic.PRICING.key, TopicSentiment.NEGATIVE, null)),
                    type = ReviewType.COMPLAINT,
                    urgency = Urgency.LOW,
                )

                val crashes = owner.get("/api/orgs/$SLUG/apps/$appId/reviews?topic=crash").bodyAsText()
                crashes shouldContain "Po aktualizaci to padá"
                crashes shouldNotContain "Je to drahé"

                val urgent = owner.get("/api/orgs/$SLUG/apps/$appId/reviews?urgency=HIGH").bodyAsText()
                urgent shouldContain "Po aktualizaci to padá"
                urgent shouldNotContain "Je to drahé"

                val complaints = owner.get("/api/orgs/$SLUG/apps/$appId/reviews?type=COMPLAINT").bodyAsText()
                complaints shouldContain "Je to drahé"
                complaints shouldNotContain "Po aktualizaci to padá"

                owner.get("/api/orgs/$SLUG/apps/$appId/reviews?urgency=NESMYSL").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "recenze se dvěma tématy se ve filtru neobjeví dvakrát" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(
                    appId,
                    "gp-1",
                    "Po aktualizaci to padá",
                    listOf(
                        TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null),
                        TopicMention(Topic.UPDATE.key, TopicSentiment.NEGATIVE, null),
                    ),
                )

                val body = owner.get("/api/orgs/$SLUG/apps/$appId/reviews?topic=crash,update").bodyAsText()

                Regex("Po aktualizaci to padá").findAll(body).count() shouldBe 1
            }
        }

        "výběr témat nese celou taxonomii i počty za posledních 30 dní" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(
                    appId,
                    "gp-1",
                    "Po aktualizaci to padá",
                    listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
                )

                val body = owner.get("/api/orgs/$SLUG/apps/$appId/topics").bodyAsText()

                body shouldContain "\"key\":\"crash\""
                body shouldContain "\"key\":\"pricing\""
                body shouldContain "\"group\":\"Stabilita\""
            }
        }

        "vlastní téma se dá přidat, upravit a smazat" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)

                val created =
                    owner.postJson(
                        "/api/orgs/$SLUG/apps/$appId/topics",
                        """{"name":"Garmin","description":"Problems syncing workouts from Garmin watches."}""",
                    )
                created.status shouldBe HttpStatusCode.Created
                val topicId = created.bodyAsText().jsonValue("key").removePrefix(Topic.CUSTOM_PREFIX)
                created.bodyAsText() shouldContain "\"custom\":true"

                owner
                    .patchJson(
                        "/api/orgs/$SLUG/apps/$appId/topics/$topicId",
                        """{"name":"Garmin hodinky","description":"Problems syncing workouts from Garmin watches.","enabled":false}""",
                    ).bodyAsText() shouldContain "\"enabled\":false"

                owner.deleteSigned("/api/orgs/$SLUG/apps/$appId/topics/$topicId").status shouldBe HttpStatusCode.NoContent
                owner.get("/api/orgs/$SLUG/apps/$appId/topics").bodyAsText() shouldNotContain "Garmin"
            }
        }

        "krátký popis vlastního tématu se odmítne větou, ne constraint violation" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)

                val response =
                    owner.postJson("/api/orgs/$SLUG/apps/$appId/topics", """{"name":"Garmin","description":"krátký"}""")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Popis tématu"
            }
        }

        "stav výkladu řekne, kolik recenzí ho má, a backfill se zařadí do fronty" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (owner, appId) = ownerWithApp(mailer)
                seedReview(
                    appId,
                    "gp-1",
                    "Po aktualizaci to padá",
                    listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
                )
                seedReview(appId, "gp-2", "Bez výkladu", emptyList(), withInsight = false)

                val status = owner.get("/api/orgs/$SLUG/apps/$appId/analysis/status").bodyAsText()
                status shouldContain "\"analyzed\":1"
                status shouldContain "\"missing\":1"
                status shouldContain Topic.TAXONOMY_VERSION

                val queued = owner.postJson("/api/orgs/$SLUG/apps/$appId/analysis/backfill", "{}")
                queued.status shouldBe HttpStatusCode.Accepted
                queued.bodyAsText() shouldContain "\"queued\":true"
                analysis.queued.single().second shouldBe appId
            }
        }

        "témata cizí organizace nejsou vidět" {
            testApplication {
                consoleModule(mailer, analysisQueue = analysis)
                val (_, appId) = ownerWithApp(mailer)

                val stranger = browser()
                stranger.signUpVerified("cizi@example.com", mailer)

                stranger.get("/api/orgs/$SLUG/apps/$appId/topics").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
