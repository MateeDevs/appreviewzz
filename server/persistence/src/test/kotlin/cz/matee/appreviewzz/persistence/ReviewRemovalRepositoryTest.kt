package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.ReviewFilter
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Recenze smazané autorem. Store o smazání nedá vědět — recenze jen přestane chodit,
 * takže se značí podle toho, co ve výpisu chybí, a odznačí, když se zase objeví.
 */
class ReviewRemovalRepositoryTest :
    FunSpec({
        val exposed = TestDatabase.database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)

        beforeTest { TestDatabase.reset() }

        fun ios(
            storeReviewId: String,
            submittedAt: Instant,
        ) = Fixtures.observedReview(storeReviewId = storeReviewId, platform = Platform.IOS, submittedAt = submittedAt)

        test("markUnlistedRemoved označí jen recenze v pokrytém období, které běh neviděl") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "MujUp", ascAppId = "123"))
            val previousRun = Clock.System.now() - 30.minutes
            val run = Clock.System.now()
            val oldest = Instant.parse("2026-01-10T08:00:00Z")
            reviews.upsert(org.id, app.id, ios("asc:deleted", Instant.parse("2026-06-04T05:41:30Z")), previousRun, ReviewState.REPLIED)
            reviews.upsert(org.id, app.id, ios("asc:older", Instant.parse("2025-12-01T08:00:00Z")), previousRun, ReviewState.REPLIED)
            reviews.upsert(org.id, app.id, ios("asc:listed", oldest), run, ReviewState.REPLIED)
            reviews.upsert(org.id, app.id, ios("asc:fresh", Instant.parse("2026-09-30T10:35:06Z")), run, ReviewState.NEW)

            reviews.markUnlistedRemoved(org.id, app.id, Platform.IOS, oldest, run) shouldBe 1

            val removed =
                listOf("asc:deleted", "asc:older", "asc:listed", "asc:fresh").filter {
                    reviews.findByStoreId(org.id, app.id, Platform.IOS, it)?.removedAt != null
                }
            removed shouldContainExactly listOf("asc:deleted")
            // Podruhé už se nic nepočítá — označení je jednorázové.
            reviews.markUnlistedRemoved(org.id, app.id, Platform.IOS, oldest, run) shouldBe 0
        }

        test("smazaná recenze vypadne z fronty k odpovědi a vrátí se, když ji store zase ukáže") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "MujUp", gpPackageName = "cz.myup.customer"))
            val observed = Fixtures.observedReview(storeReviewId = "gp:deleted")
            val review = reviews.upsert(org.id, app.id, observed, Fixtures.seenAt, ReviewState.NOTIFIED).review
            val pending = ReviewFilter(states = setOf(ReviewState.NEW, ReviewState.NOTIFIED, ReviewState.UPDATED))

            reviews.markRemoved(org.id, review.id, Fixtures.seenAt) shouldBe true

            reviews.listByApp(org.id, app.id, pending, 50).map { it.id } shouldBe emptyList()
            reviews.listByApp(org.id, app.id, ReviewFilter(), 50).single().removedAt shouldNotBe null
            reviews.markRemoved(org.id, review.id, Fixtures.seenAt) shouldBe false

            val back = reviews.upsert(org.id, app.id, observed, Fixtures.seenAt + 30.minutes, ReviewState.NEW)

            back.review.removedAt shouldBe null
            reviews.listByApp(org.id, app.id, pending, 50).map { it.id } shouldContainExactly listOf(review.id)
        }
    })
