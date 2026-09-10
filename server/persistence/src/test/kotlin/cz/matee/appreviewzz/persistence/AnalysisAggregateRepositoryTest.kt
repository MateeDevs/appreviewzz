package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.port.AnalysisReviewScope
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewReply
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.persistence.repository.ExposedAnalysisAggregateRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewInsightRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

private val FROM = Instant.parse("2026-09-01T00:00:00Z")
private val TO = Instant.parse("2026-09-09T00:00:00Z")

/**
 * Agregace rozborů. Testuje se to, co SQL dělá jinak než paměť: denní buckety v zóně
 * aplikace, filtr na platformu a trh, a efekt odpovědi počítaný z revizí recenze.
 */
class AnalysisAggregateRepositoryTest :
    FunSpec({
        val exposed = TestDatabase.database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val insights = ExposedReviewInsightRepository(exposed)
        val replies = ExposedReplyRepository(exposed)
        val aggregates = ExposedAnalysisAggregateRepository(exposed)

        beforeTest { TestDatabase.reset() }

        fun insight(
            review: Review,
            sentiment: OverallSentiment = OverallSentiment.NEGATIVE,
        ) = NewReviewInsight(
            reviewId = review.id,
            appId = review.appId,
            contentHash = review.contentHash,
            taxonomyVersion = Topic.TAXONOMY_VERSION,
            promptVersion = "2026-09-v1",
            model = "gemini-2.5-flash-lite",
            sentiment = sentiment,
            type = ReviewType.BUG,
            urgency = Urgency.HIGH,
            language = "cs",
            translation = null,
            topics = listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "pořád padá")),
        )

        test("denní buckety se počítají v zóně aplikace, ne v UTC") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            // 22:30 UTC je v Praze už 8. 9. Kdyby se bucketovalo v UTC, spadla by recenze
            // do 7. 9. a denní počty by u půlnočního provozu byly systematicky vedle.
            val late =
                reviews
                    .upsert(
                        org.id,
                        app.id,
                        Fixtures.observedReview(storeReviewId = "gp:late", submittedAt = Instant.parse("2026-09-07T22:30:00Z")),
                        Fixtures.seenAt,
                        ReviewState.NEW,
                    ).review
            insights.upsert(org.id, insight(late), Fixtures.seenAt)

            val prague = aggregates.daily(org.id, app.id, FROM, TO, "Europe/Prague").single()
            val utc = aggregates.daily(org.id, app.id, FROM, TO, "UTC").single()

            prague.date shouldBe LocalDate(2026, 9, 8)
            utc.date shouldBe LocalDate(2026, 9, 7)
        }

        test("filtr na platformu a trh zúží počty, ne jen seznam") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            listOf("gp:cz" to Platform.ANDROID, "as:cz" to Platform.IOS).forEach { (id, platform) ->
                val review =
                    reviews
                        .upsert(
                            org.id,
                            app.id,
                            Fixtures.observedReview(
                                storeReviewId = id,
                                platform = platform,
                                submittedAt = Instant.parse("2026-09-05T10:00:00Z"),
                            ),
                            Fixtures.seenAt,
                            ReviewState.NEW,
                        ).review
                insights.upsert(org.id, insight(review), Fixtures.seenAt)
            }

            aggregates.aggregate(org.id, app.id, FROM, TO).reviews shouldBe 2
            aggregates.aggregate(org.id, app.id, FROM, TO, AnalysisFilter(platform = Platform.IOS)).reviews shouldBe 1
            aggregates.aggregate(org.id, app.id, FROM, TO, AnalysisFilter(territory = "CZ")).reviews shouldBe 2
            aggregates.aggregate(org.id, app.id, FROM, TO, AnalysisFilter(territory = "DE")).reviews shouldBe 0
            aggregates.territories(org.id, app.id, FROM, TO) shouldHaveSize 1
        }

        test("hodnocení bez textu se počítá jen ve výslovně rozšířeném pohledu") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val bare =
                reviews
                    .upsert(
                        org.id,
                        app.id,
                        Fixtures.observedReview(
                            storeReviewId = "gp:bare",
                            body = null,
                            submittedAt = Instant.parse("2026-09-05T10:00:00Z"),
                        ),
                        Fixtures.seenAt,
                        ReviewState.NEW,
                    ).review
            insights.upsert(org.id, insight(bare, OverallSentiment.POSITIVE), Fixtures.seenAt)

            aggregates.aggregate(org.id, app.id, FROM, TO).reviews shouldBe 0
            val all =
                aggregates.aggregate(
                    org.id,
                    app.id,
                    FROM,
                    TO,
                    AnalysisFilter(reviewScope = AnalysisReviewScope.ALL),
                )
            all.reviews shouldBe 1
            all.avgStars shouldBe 4.0
            all.sentiments[OverallSentiment.POSITIVE] shouldBe 1
            val allDays =
                aggregates.daily(
                    org.id,
                    app.id,
                    FROM,
                    TO,
                    "Europe/Prague",
                    AnalysisFilter(reviewScope = AnalysisReviewScope.ALL),
                )
            allDays.single().reviews shouldBe 1
        }

        test("recenze, kterou autor po odpovědi přepsal na víc hvězd, se počítá jako zvednutá") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val review =
                reviews
                    .upsert(
                        org.id,
                        app.id,
                        Fixtures.observedReview(
                            storeReviewId = "gp:uplift",
                            starRating = 1,
                            body = "Nefunguje to.",
                            submittedAt = Instant.parse("2026-09-02T08:00:00Z"),
                        ),
                        Instant.parse("2026-09-02T08:00:00Z"),
                        ReviewState.NEW,
                    ).review
            insights.upsert(org.id, insight(review), Fixtures.seenAt)

            val reply =
                replies.create(
                    org.id,
                    NewReply(reviewId = review.id, body = "Omlouváme se, opravíme.", source = ReplySource.CONSOLE),
                )
            replies.markPublished(org.id, reply.id, Instant.parse("2026-09-03T08:00:00Z"))

            // Autor po odpovědi recenzi přepsal a přidal hvězdy — nová revize se založí
            // tím, že se ze storu vrátí jiný obsah.
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(
                    storeReviewId = "gp:uplift",
                    starRating = 4,
                    body = "Opravili to, díky.",
                    submittedAt = Instant.parse("2026-09-02T08:00:00Z"),
                ),
                Instant.parse("2026-09-04T08:00:00Z"),
                ReviewState.NEW,
            )

            val stats = aggregates.replyStats(org.id, app.id, FROM, TO)

            stats.replied shouldBe 1
            stats.uplifted shouldBe 1
            stats.dropped shouldBe 0
        }
    })
