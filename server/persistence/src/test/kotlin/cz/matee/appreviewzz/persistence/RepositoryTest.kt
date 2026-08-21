package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ValidationStatus
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.core.port.NewReply
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedMembershipRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingSnapshotRepository
import cz.matee.appreviewzz.persistence.repository.ExposedRatingsDigestRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RepositoryTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val users = ExposedUserRepository(exposed)
        val memberships = ExposedMembershipRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val replies = ExposedReplyRepository(exposed)
        val channels = ExposedChannelRepository(exposed)
        val messages = ExposedReviewMessageRepository(exposed)
        val ratings = ExposedRatingSnapshotRepository(exposed)
        val ratingsDigests = ExposedRatingsDigestRepository(exposed)
        val auditLog = ExposedAuditLogRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)

        beforeTest { TestDatabase.reset() }

        test("organizace, uživatel a členství") {
            val org = organizations.create("Matee", "matee")
            val user = users.create("Info@Matee.CZ", "Tadeáš")

            organizations.findBySlug("matee").shouldNotBeNull().id shouldBe org.id
            // E-mail se normalizuje na lowercase, jinak by CHECK v databázi zápis odmítl.
            user.email shouldBe "info@matee.cz"
            users.findByEmail("INFO@matee.cz").shouldNotBeNull().id shouldBe user.id

            memberships.upsert(org.id, user.id, OrgRole.OWNER)
            memberships.upsert(org.id, user.id, OrgRole.ADMIN)
            memberships.listByOrg(org.id) shouldHaveSize 1
            memberships.roleOf(org.id, user.id) shouldBe OrgRole.ADMIN
        }

        test("rotace DEK nechá aktivní vždy jen jeden klíč") {
            val org = organizations.create("Matee", "matee")
            val first = dataKeys.create(org.id, "aws-kms://key-1", byteArrayOf(1, 2, 3), Fixtures.seenAt)
            val second = dataKeys.create(org.id, "aws-kms://key-1", byteArrayOf(4, 5, 6), Fixtures.seenAt)

            dataKeys.findActive(org.id).shouldNotBeNull().id shouldBe second.id
            dataKeys.findById(org.id, first.id).shouldNotBeNull().active shouldBe false
        }

        test("credential se ukládá jen zašifrovaný a validace se zaznamenává") {
            val org = organizations.create("Matee", "matee")
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Fixtures.seenAt)
            val meta =
                credentials.create(
                    org.id,
                    NewCredential(
                        id = CredentialId(Uuid.random()),
                        type = CredentialType.GP_SERVICE_ACCOUNT,
                        label = "IsleGrow GP",
                        dataKeyId = key.id,
                        ciphertext = byteArrayOf(1, 2, 3, 4),
                        fingerprint = "sha256:abcd",
                        hint = "svc@project.iam.gserviceaccount.com",
                    ),
                )

            meta.validationStatus shouldBe ValidationStatus.UNKNOWN
            credentials
                .loadForDecryption(org.id, meta.id)
                .shouldNotBeNull()
                .ciphertext
                .toList() shouldBe
                listOf<Byte>(1, 2, 3, 4)

            credentials.recordValidation(org.id, meta.id, ValidationStatus.VALID, null, Fixtures.seenAt)
            credentials.findMeta(org.id, meta.id).shouldNotBeNull().validationStatus shouldBe ValidationStatus.VALID
        }

        test("appka, přiřazení credentialu a hledání podle účelu") {
            val org = organizations.create("Matee", "matee")
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Fixtures.seenAt)
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val credential =
                credentials.create(
                    org.id,
                    NewCredential(
                        id = CredentialId(Uuid.random()),
                        type = CredentialType.GP_SERVICE_ACCOUNT,
                        label = "IsleGrow GP",
                        dataKeyId = key.id,
                        ciphertext = byteArrayOf(1),
                        fingerprint = "sha256:abcd",
                    ),
                )

            credentials.attachToApp(org.id, app.id, credential.id, CredentialPurpose.REVIEWS)
            credentials.attachToApp(org.id, app.id, credential.id, CredentialPurpose.REVIEWS)

            credentials
                .findForApp(org.id, app.id, CredentialPurpose.REVIEWS, CredentialType.GP_SERVICE_ACCOUNT)
                .shouldNotBeNull()
                .id shouldBe credential.id
            credentials.findForApp(org.id, app.id, CredentialPurpose.REPLIES, CredentialType.GP_SERVICE_ACCOUNT) shouldBe null
        }

        test("upsert recenze: založení, beze změny, editace") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val observed = Fixtures.observedReview()

            val created = reviews.upsert(org.id, app.id, observed, Fixtures.seenAt, ReviewState.NEW)
            created.outcome shouldBe ReviewUpsertOutcome.CREATED

            val later = Fixtures.seenAt.plus(kotlin.time.Duration.parse("30m"))
            val unchanged = reviews.upsert(org.id, app.id, observed, later, ReviewState.NEW)
            unchanged.outcome shouldBe ReviewUpsertOutcome.UNCHANGED
            unchanged.review.id shouldBe created.review.id
            unchanged.review.lastSeenAt shouldBe later

            // Autor recenzi přepsal — dnešní n8n dedup by ji ignoroval, tady se pozná podle otisku.
            val edited = observed.copy(body = "Po aktualizaci to konečně funguje.", starRating = 5)
            val updated = reviews.upsert(org.id, app.id, edited, later, ReviewState.NEW)
            updated.outcome shouldBe ReviewUpsertOutcome.UPDATED
            updated.review.starRating shouldBe 5
            updated.review.id shouldBe created.review.id
        }

        test("editovaná recenze jde do stavu UPDATED i po odpovědi a nese seznam změn") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val observed = Fixtures.observedReview(starRating = 3)

            val created = reviews.upsert(org.id, app.id, observed, Fixtures.seenAt, ReviewState.NEW)
            reviews.updateState(org.id, created.review.id, ReviewState.REPLIED)

            val edited = observed.copy(starRating = 5, body = "Po odpovědi supportu měním na pět hvězd.")
            val updated = reviews.upsert(org.id, app.id, edited, Fixtures.seenAt, ReviewState.NEW)

            updated.outcome shouldBe ReviewUpsertOutcome.UPDATED
            updated.review.state shouldBe ReviewState.UPDATED
            updated.changes shouldBe setOf(ReviewChange.RATING, ReviewChange.TEXT)
            updated.isNotifiable() shouldBe true
        }

        test("recenze pod watermarkem zůstane potlačená i po editaci") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val observed = Fixtures.observedReview()

            reviews.upsert(org.id, app.id, observed, Fixtures.seenAt, ReviewState.SUPPRESSED)
            val updated =
                reviews.upsert(
                    org.id,
                    app.id,
                    observed.copy(body = "Doplňuji: po aktualizaci lepší."),
                    Fixtures.seenAt,
                    ReviewState.SUPPRESSED,
                )

            updated.review.state shouldBe ReviewState.SUPPRESSED
            updated.isNotifiable() shouldBe false
        }

        test("každé znění recenze má vlastní zprávu v kanálu, totéž znění jen jednu") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val channel =
                channels.create(
                    org.id,
                    NewChannel(appId = app.id, type = ChannelType.SLACK, targetRef = "C123"),
                )
            val observed = Fixtures.observedReview()
            val created = reviews.upsert(org.id, app.id, observed, Fixtures.seenAt, ReviewState.NEW)

            val first = messages.claim(org.id, created.review.id, channel.id, created.review.contentHash)
            messages.claim(org.id, created.review.id, channel.id, created.review.contentHash).id shouldBe first.id
            messages.markSent(org.id, first.id, "C123", "1724060000.000100", Fixtures.seenAt)

            val updated =
                reviews.upsert(
                    org.id,
                    app.id,
                    observed.copy(starRating = 5),
                    Fixtures.seenAt,
                    ReviewState.NEW,
                )
            val second = messages.claim(org.id, created.review.id, channel.id, updated.review.contentHash)
            second.id shouldNotBe first.id
            messages.markSent(org.id, second.id, "C123", "1724060900.000200", Fixtures.seenAt)

            messages.listByReview(org.id, created.review.id) shouldHaveSize 2
            // „✅ odpovězeno" se dopisuje k poslední odeslané zprávě, ne k té první.
            messages
                .findLatestSent(org.id, created.review.id, channel.id)
                .shouldNotBeNull()
                .providerMessageId shouldBe "1724060900.000200"
            messages
                .findByProviderMessage(ChannelType.SLACK, "C123", "1724060900.000200")
                .shouldNotBeNull()
                .reviewId shouldBe created.review.id
        }

        test("odpověď se stejným textem se nezaloží dvakrát") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val review = reviews.upsert(org.id, app.id, Fixtures.observedReview(), Fixtures.seenAt, ReviewState.NEW).review

            val first =
                replies.create(
                    org.id,
                    NewReply(reviewId = review.id, body = "Díky za zpětnou vazbu!", source = ReplySource.SLACK),
                )
            val second =
                replies.create(
                    org.id,
                    NewReply(reviewId = review.id, body = "Díky za zpětnou vazbu!", source = ReplySource.SLACK),
                )

            second.id shouldBe first.id
            replies.listByReview(org.id, review.id) shouldHaveSize 1

            replies.markPublished(org.id, first.id, Fixtures.seenAt)
            replies.listByStatus(org.id, ReplyStatus.PUBLISHED) shouldHaveSize 1
        }

        test("denní snapshot hodnocení se přepisuje, ne duplikuje") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val date = LocalDate(2026, 8, 19)

            ratings.upsert(
                org.id,
                NewRatingSnapshot(
                    appId = app.id,
                    platform = Platform.ANDROID,
                    date = date,
                    average = 4.31,
                    totalCount = 1200,
                    histogram = mapOf(1 to 20L, 5 to 800L),
                    source = RatingSource.GP_CSV,
                ),
                Fixtures.seenAt,
            )
            val second =
                ratings.upsert(
                    org.id,
                    NewRatingSnapshot(
                        appId = app.id,
                        platform = Platform.ANDROID,
                        date = date,
                        average = 4.35,
                        totalCount = 1210,
                        histogram = mapOf(1 to 20L, 5 to 810L),
                        source = RatingSource.GP_CSV,
                    ),
                    Fixtures.seenAt,
                )

            ratings.listRecent(org.id, app.id, Platform.ANDROID) shouldHaveSize 1
            val stored = ratings.findByDate(org.id, app.id, Platform.ANDROID, date).shouldNotBeNull()
            stored.id shouldBe second.id
            stored.totalCount shouldBe 1210
            stored.histogram[5] shouldBe 810L
        }

        test("historie hodnocení je vidět po storefrontech i globálně, ale nemíchá se") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", ascAppId = "id1490577875"))

            listOf("GLOBAL" to 4.5, "CZ" to 4.9, "US" to 4.4).forEach { (territory, average) ->
                ratings.upsert(
                    org.id,
                    NewRatingSnapshot(
                        appId = app.id,
                        platform = Platform.IOS,
                        date = LocalDate(2026, 8, 20),
                        territory = territory,
                        average = average,
                        totalCount = 100,
                        source = RatingSource.ITUNES_LOOKUP,
                    ),
                    Fixtures.seenAt,
                )
            }

            // Bez filtru storefrontu by „předchozí snapshot" byl jiná země, ne jiný den.
            val global = ratings.listRecent(org.id, app.id, Platform.IOS)
            global shouldHaveSize 1
            global.single().territory shouldBe "GLOBAL"
            ratings.listRecent(org.id, app.id, Platform.IOS, territory = "CZ").single().average shouldBe 4.9
        }

        test("přehled hodnocení se pro jeden den a kanál rezervuje jen jednou") {
            val org = organizations.create("Matee", "matee")
            val app = apps.create(org.id, NewApp(name = "IsleGrow", ascAppId = "id1490577875"))
            val channel = channels.create(org.id, NewChannel(appId = app.id, type = ChannelType.SLACK, targetRef = "C0123"))
            val date = LocalDate(2026, 8, 21)

            ratingsDigests.claim(org.id, app.id, channel.id, date, Fixtures.seenAt) shouldBe true
            // Opakovaný běh jobu nesmí poslat druhý přehled — ten by navíc ukázal nulovou deltu.
            ratingsDigests.claim(org.id, app.id, channel.id, date, Fixtures.seenAt) shouldBe false
            ratingsDigests.claim(org.id, app.id, channel.id, LocalDate(2026, 8, 22), Fixtures.seenAt) shouldBe true
            ratingsDigests.lastSent(org.id, channel.id) shouldBe LocalDate(2026, 8, 22)
        }

        test("audit log a DLQ") {
            val org = organizations.create("Matee", "matee")
            auditLog.append(
                cz.matee.appreviewzz.core.port.auditEntry(
                    orgId = org.id,
                    action = "credential.created",
                    targetType = "credential",
                    targetId = "abc",
                    metadata = mapOf("type" to "GP_SERVICE_ACCOUNT"),
                ),
            )
            auditLog.list(org.id) shouldHaveSize 1

            val failedAt: Instant = Fixtures.seenAt
            failedJobs.record("ingest-reviews", "app-1", org.id, null, "IOException", "timeout", failedAt)
            val repeated =
                failedJobs.record("ingest-reviews", "app-1", org.id, null, "IOException", "timeout", failedAt)

            repeated.attempts shouldBe 2
            failedJobs.listOpen() shouldHaveSize 1
            failedJobs.resolve("ingest-reviews", "app-1", failedAt) shouldBe true
            failedJobs.listOpen() shouldHaveSize 0
        }
    })
