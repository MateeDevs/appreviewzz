package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.persistence.repository.ExposedAnalysisAggregateRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Import historie z archivu storu (A10) a to, co z něj plyne pro čísla v rozboru.
 *
 * Obojí stojí na tom, že archiv nese **jiná ID než API** — historie se proto páruje časem
 * odeslání a odpověď napsaná v Play Console je u ní jediné, co o odpovídání víme.
 */
class ReviewHistoryRepositoryTest :
    FunSpec({
        val exposed = TestDatabase.database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val aggregates = ExposedAnalysisAggregateRepository(exposed)

        beforeTest { TestDatabase.reset() }

        test("listTimeKeys vrací ID a časy recenzí v období, jen pro svou appku a platformu") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow", ascAppId = "123"))
            val other = apps.create(org.id, NewApp(name = "MujUp", gpPackageName = "cz.myup.customer"))

            val inRange = Instant.parse("2026-08-10T09:30:00Z")
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "gp:v-obdobi", submittedAt = inRange),
                Fixtures.seenAt,
                ReviewState.NEW,
            )
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "gp:mimo", submittedAt = Instant.parse("2026-06-01T09:30:00Z")),
                Fixtures.seenAt,
                ReviewState.NEW,
            )
            reviews.upsert(
                org.id,
                app.id,
                Fixtures
                    .observedReview(storeReviewId = "asc:jina-platforma", submittedAt = inRange)
                    .copy(platform = Platform.IOS),
                Fixtures.seenAt,
                ReviewState.NEW,
            )
            reviews.upsert(
                org.id,
                other.id,
                Fixtures.observedReview(storeReviewId = "gp:jina-appka", submittedAt = inRange),
                Fixtures.seenAt,
                ReviewState.NEW,
            )

            val keys =
                reviews.listTimeKeys(
                    org.id,
                    app.id,
                    Platform.ANDROID,
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-09-01T00:00:00Z"),
                )

            keys.map { it.storeReviewId } shouldBe listOf("gp:v-obdobi")
            keys.single().submittedAt shouldBe inRange
        }

        test("mimo období nevrací nic") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            reviews.upsert(org.id, app.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW)

            reviews
                .listTimeKeys(
                    org.id,
                    app.id,
                    Platform.ANDROID,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-02-01T00:00:00Z"),
                ).shouldBeEmpty()
        }

        /**
         * Odpověď napsaná v Play Console se počítá jako odpověď. U historie dotažené z archivu
         * je to jediné, co o odpovídání víme — bez toho by rozbor za minulý měsíc tvrdil
         * „odpovězeno 0", i kdyby klient odpovídal na všechno.
         */
        test("do statistiky odpovědí se počítá i odpověď napsaná ve storu") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val submitted = Instant.parse("2026-08-10T09:00:00Z")
            reviews.upsert(
                org.id,
                app.id,
                Fixtures
                    .observedReview(storeReviewId = "csv:1786432491440", submittedAt = submitted)
                    .copy(
                        developerResponseBody = "Děkujeme za zpětnou vazbu.",
                        developerResponseAt = submitted + kotlin.time.Duration.parse("4h"),
                    ),
                Fixtures.seenAt,
                ReviewState.SUPPRESSED,
            )
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "csv:bez-odpovedi", submittedAt = submitted),
                Fixtures.seenAt,
                ReviewState.SUPPRESSED,
            )

            val stats =
                aggregates.replyStats(
                    org.id,
                    app.id,
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-09-01T00:00:00Z"),
                )

            stats.total shouldBe 2
            stats.replied shouldBe 1
            stats.medianHours shouldBe 4.0
        }
    })
