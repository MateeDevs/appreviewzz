package cz.matee.appreviewzz.jobs

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialId
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ChannelTarget
import cz.matee.appreviewzz.core.port.ConnectivityNotice
import cz.matee.appreviewzz.core.port.NewApp
import cz.matee.appreviewzz.core.port.NewChannel
import cz.matee.appreviewzz.core.port.NewCredential
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.PostedMessage
import cz.matee.appreviewzz.core.port.PublishedReply
import cz.matee.appreviewzz.core.port.ReplyRendering
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReplySuggestionRequest
import cz.matee.appreviewzz.core.port.ReplyTarget
import cz.matee.appreviewzz.core.port.ReviewSource
import cz.matee.appreviewzz.core.port.SecretResolver
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.SuggestReplyProvider
import cz.matee.appreviewzz.core.port.ValidationOutcome
import cz.matee.appreviewzz.core.usecase.DeliverReviewUseCase
import cz.matee.appreviewzz.core.usecase.IngestReviewsUseCase
import cz.matee.appreviewzz.core.usecase.PublishReplyUseCase
import cz.matee.appreviewzz.persistence.asDataSource
import cz.matee.appreviewzz.persistence.repository.ExposedAppRepository
import cz.matee.appreviewzz.persistence.repository.ExposedAuditLogRepository
import cz.matee.appreviewzz.persistence.repository.ExposedChannelRepository
import cz.matee.appreviewzz.persistence.repository.ExposedCredentialRepository
import cz.matee.appreviewzz.persistence.repository.ExposedDataKeyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedFailedJobRepository
import cz.matee.appreviewzz.persistence.repository.ExposedOrganizationRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReplyRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewMessageRepository
import cz.matee.appreviewzz.persistence.repository.ExposedReviewRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import io.kotest.matchers.collections.shouldContain as shouldContainElement

/**
 * Celá smyčka F2 nad skutečným Postgresem a plánovačem: **recenze ze storu → zpráva v kanálu →
 * kliknutí na Odeslat → odpověď ve storu → zpráva označená jako vyřízená**. Store i Slack jsou
 * dvojníci, všechno mezi nimi je skutečné — včetně fronty úloh a párování zprávy na recenzi.
 */
class ReplyJobsTest :
    FunSpec({
        val database = TestDatabase.database
        val exposed = database.exposed

        val organizations = ExposedOrganizationRepository(exposed)
        val apps = ExposedAppRepository(exposed)
        val credentials = ExposedCredentialRepository(exposed)
        val dataKeys = ExposedDataKeyRepository(exposed)
        val reviews = ExposedReviewRepository(exposed)
        val reviewMessages = ExposedReviewMessageRepository(exposed)
        val replies = ExposedReplyRepository(exposed)
        val channels = ExposedChannelRepository(exposed)
        val failedJobs = ExposedFailedJobRepository(exposed)
        val audit = ExposedAuditLogRepository(exposed)

        var storeFailure: StoreConnectorException? = null
        val publishedToStore = CopyOnWriteArrayList<String>()
        val repliedInSlack = CopyOnWriteArrayList<ReplyRendering>()
        val failuresInSlack = CopyOnWriteArrayList<String>()

        val source =
            object : ReviewSource {
                override val platform = Platform.ANDROID

                override suspend fun fetchReviews(context: StoreContext): List<ObservedReview> =
                    listOf(
                        ObservedReview(
                            platform = Platform.ANDROID,
                            storeReviewId = "gp:1",
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
                        ),
                    )

                override suspend fun validate(context: StoreContext): ValidationOutcome = ValidationOutcome(true)
            }

        val replyTarget =
            object : ReplyTarget {
                override val platform = Platform.ANDROID
                override val replyMaxLength = 350

                override suspend fun publishReply(
                    context: StoreContext,
                    storeReviewId: String,
                    body: String,
                ): PublishedReply {
                    storeFailure?.let { throw it }
                    publishedToStore += body
                    return PublishedReply(body, Clock.System.now())
                }
            }

        val slack =
            object : NotificationChannel {
                override val type = ChannelType.SLACK

                override suspend fun postReview(
                    target: ChannelTarget,
                    notification: ReviewNotification,
                ): PostedMessage = PostedMessage(target.conversationId, "1755600000.000100")

                override suspend fun markReplied(
                    target: ChannelTarget,
                    message: PostedMessage,
                    rendering: ReplyRendering,
                ) {
                    repliedInSlack += rendering
                }

                override suspend fun postConnectivityCheck(
                    target: ChannelTarget,
                    notice: ConnectivityNotice,
                ): PostedMessage = PostedMessage(target.conversationId, "1755600000.check")

                override suspend fun reportFailure(
                    target: ChannelTarget,
                    message: PostedMessage,
                    notification: ReviewNotification,
                    error: String,
                ) {
                    failuresInSlack += error
                }
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
            )

        val replyJobs =
            ReplyJobs(
                publish =
                    PublishReplyUseCase(
                        apps = apps,
                        reviews = reviews,
                        replies = replies,
                        channels = channels,
                        messages = reviewMessages,
                        credentials = credentials,
                        secrets = secrets,
                        audit = audit,
                        replyTargets = listOf(replyTarget),
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
                        audit = audit,
                        sources = listOf(source),
                    ),
                apps = apps,
                failedJobs = failedJobs,
                delivery = deliveryJobs,
                sweepInterval = Duration.ofSeconds(1),
            )

        fun setUpApp(): App {
            val org = organizations.create("Matee", "matee-${Uuid.random()}".take(30))
            val app = apps.create(org.id, NewApp(name = "IsleGrow", gpPackageName = "cz.matee.islegrow"))
            val key = dataKeys.create(org.id, "local://keyset", byteArrayOf(9), Instant.parse("2026-08-19T08:00:00Z"))

            fun credential(
                type: CredentialType,
                label: String,
            ) = credentials.create(
                org.id,
                NewCredential(
                    id = CredentialId(Uuid.random()),
                    type = type,
                    label = label,
                    dataKeyId = key.id,
                    ciphertext = byteArrayOf(1),
                    fingerprint = "sha256:${Uuid.random()}",
                ),
            )

            val storeKey = credential(CredentialType.GP_SERVICE_ACCOUNT, "IsleGrow GP")
            credentials.attachToApp(org.id, app.id, storeKey.id, CredentialPurpose.REVIEWS)
            // Odpovídání má vlastní účel: klíč jen ke čtení recenzí by odpověď nepublikoval.
            credentials.attachToApp(org.id, app.id, storeKey.id, CredentialPurpose.REPLIES)

            val install = credential(CredentialType.SLACK_INSTALL, "Slack Matee")
            channels.create(
                org.id,
                NewChannel(appId = app.id, type = ChannelType.SLACK, targetRef = "C0123", credentialId = install.id),
            )
            return app
        }

        beforeTest {
            TestDatabase.reset()
            storeFailure = null
            publishedToStore.clear()
            repliedInSlack.clear()
            failuresInSlack.clear()
        }

        /** To, co dělá interactivity webhook: najde zprávu podle kanálu a ts a zařadí publikaci. */
        fun replyFromSlack(
            client: com.github.kagkarlsson.scheduler.SchedulerClient,
            review: Review,
            text: String,
        ): Boolean {
            val message =
                reviewMessages
                    .findByProviderMessage(ChannelType.SLACK, "C0123", "1755600000.000100")
                    .shouldNotBeNull()
            message.reviewId shouldBe review.id
            return replyJobs.enqueue(
                client,
                ReplyJobData(
                    orgId = message.orgId.toString(),
                    reviewId = message.reviewId.toString(),
                    channelId = message.channelId.toString(),
                    body = text,
                    source = ReplySource.SLACK.name,
                    authorExternalId = "U0123",
                    authorDisplayName = "tadeas",
                ),
            )
        }

        test("recenze projde od storu přes kanál až po publikovanou odpověď") {
            val app = setUpApp()
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = ingestJobs,
                    deliveryJobs = deliveryJobs,
                    replyJobs = replyJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                val review =
                    eventually(30.seconds) {
                        val stored = reviews.listByApp(app.orgId, app.id).single()
                        stored.state shouldBe ReviewState.NOTIFIED
                        stored
                    }

                replyFromSlack(scheduler, review, "Mrzí nás to, opravu vydáme příští týden.") shouldBe true

                eventually(30.seconds) {
                    publishedToStore.single() shouldBe "Mrzí nás to, opravu vydáme příští týden."
                    reviews.findById(app.orgId, review.id)?.state shouldBe ReviewState.REPLIED
                    replies
                        .listByReview(app.orgId, review.id)
                        .single()
                        .status shouldBe ReplyStatus.PUBLISHED
                    // Zpráva v kanálu se přepsala na „odpovězeno" — s textem i s autorem.
                    repliedInSlack.single().replyText shouldContain "opravu vydáme"
                    repliedInSlack.single().authorDisplayName shouldBe "tadeas"
                }
                audit.list(app.orgId).map { it.action } shouldContainElement "reply.published"
            } finally {
                scheduler.stop()
            }
        }

        test("odmítnutí storu jde do vlákna a odpověď zůstane nepublikovaná") {
            val app = setUpApp()
            storeFailure = StoreConnectorException(StoreErrorKind.INVALID_REQUEST, "Reply contains a link")
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = ingestJobs,
                    deliveryJobs = deliveryJobs,
                    replyJobs = replyJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            scheduler.start()

            try {
                val review =
                    eventually(30.seconds) {
                        reviews.listByApp(app.orgId, app.id).single().also { it.state shouldBe ReviewState.NOTIFIED }
                    }

                replyFromSlack(scheduler, review, "Napiš nám na podporu.")

                eventually(30.seconds) {
                    failuresInSlack.single() shouldContain "Reply contains a link"
                    replies
                        .listByReview(app.orgId, review.id)
                        .single()
                        .status shouldBe ReplyStatus.FAILED
                    // Recenze zůstává k vyřízení, formulář ve Slacku taky — dá se zkusit znovu.
                    reviews.findById(app.orgId, review.id)?.state shouldBe ReviewState.NOTIFIED
                    failedJobs.listOpenByOrg(app.orgId) shouldHaveSize 1
                }
            } finally {
                scheduler.stop()
            }
        }

        test("dvojklik na Odeslat zařadí publikaci jen jednou") {
            val app = setUpApp()
            // Plánovač schválně nezná publikační úlohu: zajímá nás fronta, ne výsledek publikace.
            val scheduler =
                buildScheduler(
                    dataSource = database.asDataSource(),
                    jobs = ingestJobs,
                    deliveryJobs = deliveryJobs,
                    config = SchedulerConfig(threads = 2, pollingInterval = Duration.ofMillis(200)),
                )
            val queue = buildSchedulerClient(database.asDataSource(), replyJobs)
            scheduler.start()

            try {
                val review =
                    eventually(30.seconds) {
                        reviews.listByApp(app.orgId, app.id).single().also { it.state shouldBe ReviewState.NOTIFIED }
                    }

                replyFromSlack(queue, review, "Díky!") shouldBe true
                replyFromSlack(queue, review, "Díky!") shouldBe false
                // Jiný text je jiná odpověď — tu zařadit jde.
                replyFromSlack(queue, review, "Díky, opravíme to.") shouldBe true
                queue.getScheduledExecutionsForTask(ReplyJobs.PUBLISH_TASK, ReplyJobData::class.java) shouldHaveSize 2
            } finally {
                scheduler.stop()
            }
        }
    })
