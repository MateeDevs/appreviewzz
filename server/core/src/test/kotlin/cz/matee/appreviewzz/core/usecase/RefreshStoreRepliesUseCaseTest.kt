package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewChange
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.ReviewRefreshSource
import cz.matee.appreviewzz.core.port.ReviewRepository
import cz.matee.appreviewzz.core.port.ReviewUpsertOutcome
import cz.matee.appreviewzz.core.port.ReviewUpsertResult
import cz.matee.appreviewzz.core.port.StoreContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Dohledávání odpovědí napsaných v Play Console. Testuje se rozhodování use-casu — SQL nad
 * `listAwaitingStoreReply` má vlastní test v persistence.
 */
class RefreshStoreRepliesUseCaseTest :
    FunSpec({
        lateinit var apps: FakeAppRepository
        lateinit var credentials: FakeCredentialRepository
        lateinit var reviews: RefreshReviewRepository

        val org = OrganizationId(Uuid.random())

        fun useCase(vararg sources: ReviewRefreshSource) =
            RefreshStoreRepliesUseCase(
                apps = apps,
                credentials = credentials,
                reviews = reviews,
                secrets = secretResolver(),
                sources = sources.toList(),
                clock = fixedClock(),
            )

        fun appWithKey(): AppId {
            val app = apps.put(Ingest.app(org))
            credentials.attach(
                app.id,
                CredentialPurpose.REVIEWS,
                Ingest.credential(org, CredentialType.GP_SERVICE_ACCOUNT),
            )
            return app.id
        }

        beforeTest {
            apps = FakeAppRepository()
            credentials = FakeCredentialRepository()
            reviews = RefreshReviewRepository()
        }

        test("sahá jen za okno ingestu a jen do rozumné historie") {
            val appId = appWithKey()

            runBlocking { useCase(FakeRefreshSource { null }).refresh(org, appId) }

            // Horní mez je ta podstatná: recenze, kterou ingest ještě vidí, patří jemu.
            reviews.bounds shouldBe (Ingest.now - 180.days to Ingest.now - 8.days)
            reviews.limit shouldBe RefreshStoreRepliesUseCase.DEFAULT_BATCH_SIZE
        }

        test("odpověď nalezená ve storu překlopí recenzi do REPLIED") {
            val appId = appWithKey()
            val review = reviews.put(pending(org, appId, "gp:answered"))
            val source = FakeRefreshSource { Ingest.observed(it, developerResponseBody = "Díky za zpětnou vazbu.") }

            val report = runBlocking { useCase(source).refresh(org, appId) }

            report.answered shouldBe 1
            reviews.stateUpdates shouldContainExactly listOf(review.id to ReviewState.REPLIED)
            source.asked shouldContainExactly listOf("gp:answered")
        }

        test("recenze bez odpovědi zůstane, kde byla") {
            val appId = appWithKey()
            reviews.put(pending(org, appId, "gp:silent"))

            val report = runBlocking { useCase(FakeRefreshSource { Ingest.observed(it) }).refresh(org, appId) }

            report.answered shouldBe 0
            reviews.stateUpdates.shouldBeEmpty()
        }

        test("recenzi, kterou store už nezná, jen započítá") {
            val appId = appWithKey()
            reviews.put(pending(org, appId, "gp:deleted"))

            val report = runBlocking { useCase(FakeRefreshSource { null }).refresh(org, appId) }

            report.platforms shouldContainExactly
                listOf(PlatformRefresh.Refreshed(Platform.ANDROID, checked = 1, answered = 0, gone = 1))
            reviews.stateUpdates.shouldBeEmpty()
        }

        test("když autor recenzi zároveň přepsal, do REPLIED se nepřeklápí") {
            val appId = appWithKey()
            reviews.put(pending(org, appId, "gp:edited"))
            reviews.changes = setOf(ReviewChange.TEXT, ReviewChange.DEVELOPER_RESPONSE)
            val source = FakeRefreshSource { Ingest.observed(it, developerResponseBody = "Odpovězeno v Play Console.") }

            val report = runBlocking { useCase(source).refresh(org, appId) }

            // Změna textu je zpráva pro tým, ne uzavření recenze — zůstává v UPDATED.
            report.answered shouldBe 0
            reviews.stateUpdates.shouldBeEmpty()
        }

        test("appka bez klíče se přeskočí, do storu se nesahá") {
            val app = apps.put(Ingest.app(org))
            val source = FakeRefreshSource { error("Konektor se neměl volat") }

            val report = runBlocking { useCase(source).refresh(org, app.id) }

            report.platforms shouldContainExactly
                listOf(PlatformRefresh.Skipped(Platform.ANDROID, PlatformSkipReason.MISSING_CREDENTIAL))
        }

        test("smazaná ani vypnutá appka nespadne") {
            val disabled = apps.put(Ingest.app(org, enabled = false))
            val useCase = useCase(FakeRefreshSource { null })

            runBlocking { useCase.refresh(org, AppId(Uuid.random())) }.appSkipped shouldBe AppSkipReason.NOT_FOUND
            runBlocking { useCase.refresh(org, disabled.id) }.appSkipped shouldBe AppSkipReason.DISABLED
        }
    })

private fun pending(
    orgId: OrganizationId,
    appId: AppId,
    storeReviewId: String,
): Review =
    Review(
        id = ReviewId(Uuid.random()),
        orgId = orgId,
        appId = appId,
        platform = Platform.ANDROID,
        storeReviewId = storeReviewId,
        authorName = "Vladimír K.",
        starRating = 1,
        title = null,
        body = "Kartou nejde platit.",
        locale = "cs",
        territory = null,
        appVersion = "3.72.0",
        device = null,
        submittedAt = Ingest.now - 30.days,
        storeUpdatedAt = null,
        contentHash = "hash-1",
        developerResponseBody = null,
        developerResponseAt = null,
        state = ReviewState.NOTIFIED,
        firstSeenAt = Ingest.now - 30.days,
        lastSeenAt = Ingest.now - 22.days,
    )

private class FakeRefreshSource(
    override val platform: Platform = Platform.ANDROID,
    private val response: (String) -> ObservedReview?,
) : ReviewRefreshSource {
    val asked = mutableListOf<String>()

    override suspend fun fetchReview(
        context: StoreContext,
        storeReviewId: String,
    ): ObservedReview? {
        asked += storeReviewId
        return response(storeReviewId)
    }
}

/**
 * Upsert vrací připravený výsledek — dedup se tady nesimuluje, testuje se reakce na něj.
 * [changes] je to, co by rozpoznal skutečný repozitář.
 */
private class RefreshReviewRepository : ReviewRepository {
    private val pending = mutableListOf<Review>()
    val stateUpdates = mutableListOf<Pair<ReviewId, ReviewState>>()
    var bounds: Pair<Instant, Instant>? = null
    var limit: Int? = null
    var changes: Set<ReviewChange> = setOf(ReviewChange.DEVELOPER_RESPONSE)

    fun put(review: Review): Review = review.also { pending += it }

    override fun listAwaitingStoreReply(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        submittedAfter: Instant,
        submittedBefore: Instant,
        limit: Int,
    ): List<Review> {
        bounds = submittedAfter to submittedBefore
        this.limit = limit
        return pending.filter { it.orgId == orgId && it.appId == appId && it.platform == platform }
    }

    override fun upsert(
        orgId: OrganizationId,
        appId: AppId,
        observed: ObservedReview,
        seenAt: Instant,
        initialState: ReviewState,
    ): ReviewUpsertResult {
        val existing = pending.first { it.storeReviewId == observed.storeReviewId }
        val updated =
            existing.copy(
                state = ReviewState.UPDATED,
                developerResponseBody = observed.developerResponseBody,
                developerResponseAt = observed.developerResponseAt,
                lastSeenAt = seenAt,
            )
        val outcome =
            if (observed.developerResponseBody == existing.developerResponseBody) {
                ReviewUpsertOutcome.UNCHANGED
            } else {
                ReviewUpsertOutcome.UPDATED
            }
        return ReviewUpsertResult(updated, outcome, if (outcome == ReviewUpsertOutcome.UPDATED) changes else emptySet())
    }

    override fun updateState(
        orgId: OrganizationId,
        id: ReviewId,
        state: ReviewState,
    ): Boolean {
        stateUpdates += id to state
        return true
    }

    override fun findById(
        orgId: OrganizationId,
        id: ReviewId,
    ): Review? = error("Nepoužívá se")

    override fun findByStoreId(
        orgId: OrganizationId,
        appId: AppId,
        platform: Platform,
        storeReviewId: String,
    ): Review? = error("Nepoužívá se")

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
        states: Set<ReviewState>,
        limit: Int,
    ): List<Review> = error("Nepoužívá se")
}
