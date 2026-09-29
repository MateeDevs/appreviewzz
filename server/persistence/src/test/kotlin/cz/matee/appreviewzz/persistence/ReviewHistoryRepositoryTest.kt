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
         * Editovaná stará recenze: export ji má pod časem odeslání z roku 2023, API pod časem
         * změny. Ingest z API se ptá právě časem změny.
         */
        test("listArchivedTimeKeys najde archivní recenzi podle času odeslání i změny, jen s csv ID") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "MujUp", gpPackageName = "cz.myup.customer"))
            val edited = Instant.parse("2026-09-03T04:46:19.978Z")
            reviews.upsert(
                org.id,
                app.id,
                Fixtures
                    .observedReview(storeReviewId = "csv:1f964d28", submittedAt = Instant.parse("2023-12-01T17:27:04Z"))
                    .copy(storeUpdatedAt = edited),
                Fixtures.seenAt,
                ReviewState.SUPPRESSED,
            )
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "gp:AOqpTOapi", submittedAt = edited),
                Fixtures.seenAt,
                ReviewState.NEW,
            )

            val keys =
                reviews.listArchivedTimeKeys(
                    org.id,
                    app.id,
                    Platform.ANDROID,
                    edited - kotlin.time.Duration.parse("1s"),
                    edited + kotlin.time.Duration.parse("1s"),
                )

            keys.map { it.storeReviewId } shouldBe listOf("csv:1f964d28")
            keys.single().storeUpdatedAt shouldBe edited
        }

        test("adoptArchived přejmenuje archivní řádek na ID z API a doplní autora") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "MujUp", gpPackageName = "cz.myup.customer"))
            val submitted = Instant.parse("2026-09-22T06:11:11.374Z")
            val archived =
                reviews
                    .upsert(
                        org.id,
                        app.id,
                        Fixtures.observedReview(storeReviewId = "csv:c999e68a", submittedAt = submitted).copy(authorName = null),
                        Fixtures.seenAt,
                        ReviewState.SUPPRESSED,
                    ).review
            val fromApi = Fixtures.observedReview(storeReviewId = "gp:AOqpTOfresh", submittedAt = submitted)

            val adopted = reviews.adoptArchived(org.id, app.id, "csv:c999e68a", fromApi)

            adopted?.id shouldBe archived.id
            val stored = reviews.findByStoreId(org.id, app.id, Platform.ANDROID, "gp:AOqpTOfresh")
            stored?.id shouldBe archived.id
            stored?.authorName shouldBe fromApi.authorName
            reviews.findByStoreId(org.id, app.id, Platform.ANDROID, "csv:c999e68a") shouldBe null
        }

        test("adoptArchived nic nepřejmenuje, když recenze pod ID z API už existuje") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "MujUp", gpPackageName = "cz.myup.customer"))
            val submitted = Instant.parse("2026-09-22T06:11:11Z")
            val fromApi = Fixtures.observedReview(storeReviewId = "gp:AOqpTOfresh", submittedAt = submitted)
            reviews.upsert(org.id, app.id, fromApi, Fixtures.seenAt, ReviewState.NEW)
            reviews.upsert(
                org.id,
                app.id,
                Fixtures.observedReview(storeReviewId = "csv:c999e68a", submittedAt = submitted),
                Fixtures.seenAt,
                ReviewState.SUPPRESSED,
            )

            reviews.adoptArchived(org.id, app.id, "csv:c999e68a", fromApi) shouldBe null
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
