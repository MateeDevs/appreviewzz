package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.MessageStatus
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.usecase.DeliverReviewUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Cesta „ingest → doručení" nad skutečným Postgresem a skutečným plánovačem. Zajímá nás
 * hlavně to, že se doručení plánuje jako **vlastní úloha** (a jde tedy retryovat zvlášť)
 * a že po odeslání vznikne zpráva, přes kterou se pak páruje odpověď ze Slacku.
 */
class DeliveryJobsTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val reviewMessages = ExposedReviewMessageRepository(exposed)
        val channels = ExposedChannelRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)

        var storeResponse: () -> List<ObservedReview> = { emptyList() }
        var channelFailure: ChannelException? = null
        val posted = CopyOnWriteArrayList<ReviewNotification>()

        val source =
            object : ReviewSource {
                override val platform = Platform.ANDROID

                override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> = storeResponse()

                override suspend fun validate(context: StoreContext): ValidationOutcome = ValidationOutcome(true)
            }

        val slack =
            object : NotificationChannel {
                override val type = ChannelType.SLACK

                override suspend fun postReview(
                    target: ChannelTarget,
                    notification: ReviewNotification,
                ): PostedMessage {
                    channelFailure?.let { throw it }
                    posted += notification
                    return PostedMessage(target.conversationId, "1755600000.${posted.size}")
                }

                override suspend fun markReplied(
                    target: ChannelTarget,
                    message: PostedMessage,
                    rendering: ReplyRendering,
                ) = Unit

                override suspend fun postRatingsDigest(
                    target: ChannelTarget,
                    digest: RatingsDigest,
                ): PostedMessage = PostedMessage(target.conversationId, "1755600000.digest")

                override suspend fun postConnectivityCheck(
                    target: ChannelTarget,
                    notice: ConnectivityNotice,
                ): PostedMessage = PostedMessage(target.conversationId, "1755600000.check")

                override suspend fun reportFailure(
                    target: ChannelTarget,
                    message: PostedMessage,
                    notification: ReviewNotification,
                    error: String,
                ) = Unit
            }

        val secrets = SecretResolver { _, _ -> SecretPayload("tajemstvi") }

        val deliveryJobs =
            DeliveryJobs(
                deliver =
                    DeliverReviewUseCase(
                        apps = apps,
                        reviews = reviews,
                        channels = channels,
                        messages = reviewMessages,
                        secrets = secrets,
                        suggestions =
                            SuggestReplyProvider { _: ReplySuggestionRequest ->
                                ReplySuggestion.Suggested("Mrzí nás to, opravujeme.", "test")
                            },
                        notificationChannels = listOf(slack),
                    ),
                failedJobs = failedJobs,
                retries = 1,
                firstRetryDelay = Duration.ofMillis(200),
            )

        val ingestJobs =
            IngestJobs(
                ingest =
                    IngestReviewsUseCase(
                        apps = apps,
                        credentials = credentials,
                        reviews = reviews,
                        secrets = secrets,
                        audit = ExposedAuditLogRepository(exposed),
                        sources = listOf(source),
                    ),
                apps = apps,
                failedJobs = failedJobs,
                delivery = deliveryJobs,
                sweepInterval = Duration.ofSeconds(1),
            )

        fun setUpApp(withChannel: Boolean = true): cz.matee.appreviewzz.core.model.App {
            val org = organizations.create("Matee", "matee-${Uuid.random()}".take(30))
            val app =
                apps.create(
                    org.id,
                    // Watermark před fixture recenzemi — jinak je potlačí čas založení appky.
                    cz.matee.appreviewzz.core.port
                        .NewApp(
                            name = "IsleGrow",
                            gpPackageName = "cz.matee.islegrow",
                            notifyFrom = Instant.parse("2026-08-01T00:00:00Z"),
                        ),
                )
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Instant.parse("2026-08-19T08:00:00Z"))
            val storeKey =
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
            credentials.attachToApp(org.id, app.id, storeKey.id, CredentialPurpose.REVIEWS)

            if (withChannel) {
                val install =
                    credentials.create(
                        org.id,
                        NewCredential(
                            id = CredentialId(Uuid.random()),
                            type = CredentialType.SLACK_INSTALL,
                            label = "Slack Matee",
                            dataKeyId = key.id,
                            ciphertext = byteArrayOf(2),
                            fingerprint = "sha256:efgh",
                        ),
                    )
                channels.create(
                    org.id,
                    NewChannel(appId = app.id, type = ChannelType.SLACK, targetRef = "C0123", credentialId = install.id),
                )
            }
            return app
        }

        fun observedReview(storeReviewId: String) =
            ObservedReview(
                platform = Platform.ANDROID,
                storeReviewId = storeReviewId,
                authorName = "Jana N.",
                starRating = 2,
                title = null,
                body = "Po updatu se nedostanu dál.",
                locale = "cs",
                territory = "CZ",
                appVersion = "3.2.1",
                device = "Pixel 8",
                submittedAt = Instant.parse("2026-08-19T09:30:00Z"),
                storeUpdatedAt = null,
                developerResponseBody = null,
                developerResponseAt = null,
            )

        beforeTest {
            TestDatabase.reset()
            storeResponse = { emptyList() }
            channelFailure = null
            posted.clear()
        }

        test("recenze z ingestu se doručí vlastní úlohou a zůstane po ní zpráva k párování") {
            val app = setUpApp()
            storeResponse = { listOf(observedReview("gp:1")) }
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = ingestJobs,
                    deliveryJobs = deliveryJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                eventually(30.seconds) {
                    posted shouldHaveSize 1
                    posted.single().suggestedReply shouldBe "Mrzí nás to, opravujeme."
                }
                eventually(30.seconds) {
                    val review = reviews.listByApp(app.orgId, app.id).single()
                    review.state shouldBe ReviewState.NOTIFIED
                    val message = reviewMessages.listByReview(app.orgId, review.id).single()
                    message.status shouldBe MessageStatus.SENT
                    message.providerMessageId shouldContain "1755600000."
                    // Právě podle téhle dvojice se pozná, ke které recenzi patří klik ve Slacku.
                    reviewMessages
                        .findByProviderMessage(ChannelType.SLACK, "C0123", message.providerMessageId!!)
                        ?.reviewId shouldBe review.id
                }
            } finally {
                scheduler.stop()
            }
        }

        test("nedoručitelná zpráva skončí v DLQ, ale ingest běží dál") {
            val app = setUpApp()
            storeResponse = { listOf(observedReview("gp:2")) }
            channelFailure = ChannelException(ChannelErrorKind.NOT_FOUND, "not_in_channel")
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = ingestJobs,
                    deliveryJobs = deliveryJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                eventually(30.seconds) {
                    val open = failedJobs.listOpenByOrg(app.orgId)
                    open shouldHaveSize 1
                    open.single().taskName shouldBe DeliveryJobs.DELIVER_TASK
                    open.single().errorMessage shouldContain "not_in_channel"
                }
                // Recenze zůstane nedoručená, ale uložená — po opravě kanálu se dá poslat znovu.
                reviews.listByApp(app.orgId, app.id).single().state shouldBe ReviewState.NEW
            } finally {
                scheduler.stop()
            }
        }

        test("payload doručení je čitelný JSON s otiskem znění") {
            val data = DeliveryJobData(orgId = "org-1", reviewId = "review-1", contentHash = "abc")

            val bytes = JsonTaskSerializer.serialize(data)

            bytes.toString(Charsets.UTF_8) shouldBe """{"orgId":"org-1","reviewId":"review-1","contentHash":"abc"}"""
            JsonTaskSerializer.deserialize(DeliveryJobData::class.java, bytes) shouldBe data
        }
    })
