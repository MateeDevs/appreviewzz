package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.InsightCoverage
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewAppTopic
import cz.matee.appreviewzz.core.port.NewReviewInsight
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAppTopicRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewInsightRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Výklady recenzí (F8). Zajímavá je tu jediná otázka: **co je potřeba přeanalyzovat**.
 * Odpověď na ni rozhoduje o tom, kolik se zaplatí za AI a jestli v grafu neleží vedle sebe
 * čísla ze dvou různých taxonomií.
 */
class InsightRepositoryTest :
    FunSpec({
        val exposed = TestDatabase.database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val insights = ExposedReviewInsightRepository(exposed)
        val appTopics = ExposedAppTopicRepository(exposed)

        val analyzedAt = Instant.parse("2026-09-03T08:00:00Z")

        beforeTest { TestDatabase.reset() }

        fun insight(
            review: Review,
            taxonomyVersion: String = Topic.TAXONOMY_VERSION,
        ) = NewReviewInsight(
            reviewId = review.id,
            appId = review.appId,
            contentHash = review.contentHash,
            taxonomyVersion = taxonomyVersion,
            promptVersion = "2026-09-v1",
            model = "gemini-2.5-flash-lite",
            sentiment = OverallSentiment.MIXED,
            type = ReviewType.BUG,
            urgency = Urgency.HIGH,
            language = "cs",
            translation = null,
            topics =
                listOf(
                    TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, "pořád padá"),
                    TopicMention(Topic.DESIGN.key, TopicSentiment.POSITIVE, null),
                ),
        )

        test("výklad se uloží i s tématy a čte se zpátky") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val review =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW)
                    .review

            insights.upsert(org.id, insight(review), analyzedAt)

            val stored = insights.findByReview(org.id, review.id).shouldNotBeNull()
            stored.sentiment shouldBe OverallSentiment.MIXED
            stored.urgency shouldBe Urgency.HIGH
            stored.topics.map { it.key } shouldBe listOf(Topic.CRASH.key, Topic.DESIGN.key)
            stored.topics.first().quote shouldBe "pořád padá"
        }

        test("přeanalyzování nahradí témata, nepřidá je") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val review =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW)
                    .review

            insights.upsert(org.id, insight(review), analyzedAt)
            insights.upsert(
                org.id,
                insight(review).copy(topics = listOf(TopicMention(Topic.SLOW.key, TopicSentiment.NEGATIVE, null))),
                analyzedAt,
            )

            insights
                .findByReview(org.id, review.id)
                .shouldNotBeNull()
                .topics
                .map { it.key } shouldBe listOf(Topic.SLOW.key)
        }

        test("listMissing vrací recenzi bez výkladu, editovanou i tu s jinou verzí taxonomie") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))

            val fresh =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(storeReviewId = "gp:1"), Fixtures.seenAt, ReviewState.NEW)
                    .review
            val edited =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(storeReviewId = "gp:2"), Fixtures.seenAt, ReviewState.NEW)
                    .review
            val stale =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(storeReviewId = "gp:3"), Fixtures.seenAt, ReviewState.NEW)
                    .review
            val current =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(storeReviewId = "gp:4"), Fixtures.seenAt, ReviewState.NEW)
                    .review

            insights.upsert(org.id, insight(edited), analyzedAt)
            insights.upsert(org.id, insight(stale, taxonomyVersion = "2026-01-v0"), analyzedAt)
            insights.upsert(org.id, insight(current), analyzedAt)

            // Autor recenzi přepsal: otisk se rozejde a starý výklad je neplatný.
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "gp:2", body = "Po aktualizaci to padá pořád."),
                Fixtures.seenAt,
                ReviewState.NEW,
            )

            val missing = insights.listMissing(org.id, app.id, Topic.TAXONOMY_VERSION, limit = 10)

            missing.map { it.id }.toSet() shouldBe setOf(fresh.id, edited.id, stale.id)
            insights.coverage(org.id, app.id, Topic.TAXONOMY_VERSION) shouldBe InsightCoverage(analyzed = 1, missing = 3)
        }

        test("výklady cizí organizace se nevrací") {
            val org = organizations.create("Matee", "matee")
            val other = organizations.create("Jiná", "jina")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val review =
                reviews
                    .upsert(org.id, app.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW)
                    .review
            insights.upsert(org.id, insight(review), analyzedAt)

            insights.findByReview(other.id, review.id) shouldBe null
            insights.findByReviews(other.id, listOf(review.id)) shouldBe emptyMap()
        }

        test("vlastní téma appky má klíč s prefixem custom a jde vypnout") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))

            val topic =
                appTopics.create(
                    org.id,
                    NewAppTopic(app.id, "Garmin", "Problems syncing workouts from Garmin watches."),
                )
            topic.key shouldBe Topic.CUSTOM_PREFIX + topic.id.value

            appTopics.listEnabled(org.id, app.id) shouldHaveSize 1
            appTopics.update(org.id, topic.id, topic.name, topic.description, enabled = false)
            appTopics.listEnabled(org.id, app.id).shouldBeEmpty()
            appTopics.listByApp(org.id, app.id) shouldHaveSize 1
        }
    })
