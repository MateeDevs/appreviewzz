package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.CredentialPurpose
import cz.matee.appreviewzz.core.model.CredentialType
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReplySource
import cz.matee.appreviewzz.core.model.ReplyStatus
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.uuid.Uuid

private val ORG = OrganizationId(Uuid.random())

private class ReplyFixture(
    storeFailure: StoreConnectorException? = null,
    channelFailure: ChannelException? = null,
    attachCredential: Boolean = true,
    simulateReplies: () -> Boolean = { false },
    platform: Platform = Platform.ANDROID,
    channelType: ChannelType = ChannelType.SLACK,
) {
    val apps = FakeAppRepository()
    val reviews = FakeReviewRepository()
    val replies = FakeReplyRepository()
    val channelRepository = FakeChannelRepository()
    val messages = FakeReviewMessageRepository()
    val credentials = FakeCredentialRepository()
    val audit = RecordingAuditLog()
    val store = FakeReplyTarget(platform = platform, failWith = storeFailure)
    val slack = FakeNotificationChannel(type = channelType, failWith = channelFailure)
    val app = apps.put(Ingest.app(ORG))
    val channel = channelRepository.put(Delivery.channel(ORG, app.id, type = channelType))

    init {
        if (attachCredential) {
            credentials.attach(
                app.id,
                CredentialPurpose.REPLIES,
                Ingest.credential(ORG, CredentialType.GP_SERVICE_ACCOUNT),
            )
        }
    }

    val useCase =
        PublishReplyUseCase(
            apps = apps,
            reviews = reviews,
            replies = replies,
            channels = channelRepository,
            messages = messages,
            credentials = credentials,
            secrets = secretResolver("service-account-json"),
            audit = audit,
            replyTargets = listOf(store),
            notificationChannels = listOf(slack),
            clock = fixedClock(Delivery.now),
            simulateReplies = simulateReplies,
        )

    /** Simuluje stav po doručení: zpráva v kanálu existuje a dá se přepsat. */
    fun deliveredReview(state: ReviewState = ReviewState.NOTIFIED) =
        reviews.put(Delivery.review(ORG, app.id, state = state, platform = store.platform)).also { review ->
            val message = messages.claim(ORG, review.id, channel.id, review.contentHash)
            messages.markSent(ORG, message.id, channel.targetRef, "1755600000.000100", Delivery.now)
        }
}

class PublishReplyUseCaseTest :
    FunSpec({
        Platform.entries.forEach { platform ->
            ReplySource.entries.forEach { source ->
                test("simulace $platform z $source uloží odpověď bez volání storu a bez klíče k publikaci") {
                    val fixture =
                        ReplyFixture(
                            attachCredential = false,
                            simulateReplies = { true },
                            platform = platform,
                            channelType = if (source == ReplySource.TEAMS) ChannelType.TEAMS else ChannelType.SLACK,
                        )
                    val review = fixture.deliveredReview()
                    val channelId = if (source == ReplySource.SLACK || source == ReplySource.TEAMS) fixture.channel.id else null
                    val outcome = fixture.useCase.publish(ReplyCommand(ORG, review.id, "Testovací odpověď", source, channelId))
                    outcome.shouldBeInstanceOf<ReplyOutcome.Simulated>().reply.simulated shouldBe true
                    fixture.store.published.shouldHaveSize(0)
                    fixture.replies.all
                        .single()
                        .simulated shouldBe true
                    fixture.replies.all
                        .single()
                        .publishedAt shouldBe null
                    fixture.reviews.stateUpdates.shouldHaveSize(0)
                    fixture.audit.entries
                        .single()
                        .action shouldBe "reply.simulated"
                    if (channelId != null) {
                        fixture.slack.replied
                            .single()
                            .simulated shouldBe true
                    }
                }
            }
        }

        test("vypnutí režimu ani dvojklik neodešle dříve simulovanou odpověď") {
            var simulation = true
            val fixture = ReplyFixture(simulateReplies = { simulation })
            val review = fixture.deliveredReview()
            val command = ReplyCommand(ORG, review.id, "Zůstane pouze u nás", ReplySource.SLACK, fixture.channel.id)
            fixture.useCase.publish(command).shouldBeInstanceOf<ReplyOutcome.Simulated>()
            simulation = false
            fixture.useCase.publish(command).shouldBeInstanceOf<ReplyOutcome.Simulated>()
            fixture.replies.all.shouldHaveSize(1)
            fixture.audit.entries.shouldHaveSize(1)
            fixture.store.published.shouldHaveSize(0)
            fixture.useCase.publish(command.copy(body = "Nová skutečná odpověď")).shouldBeInstanceOf<ReplyOutcome.Published>()
            fixture.store.published.shouldHaveSize(1)
        }

        test("simulace zachycená ve frontě platí i po vypnutí režimu") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()
            fixture.useCase
                .publish(ReplyCommand(ORG, review.id, "Test z fronty", ReplySource.CONSOLE, simulated = true))
                .shouldBeInstanceOf<ReplyOutcome.Simulated>()
            fixture.store.published.shouldHaveSize(0)
        }

        test("pád úpravy chatu nezmění simulaci na publikaci") {
            val fixture = ReplyFixture(simulateReplies = { true }, channelFailure = ChannelException(ChannelErrorKind.TRANSIENT, "výpadek"))
            val review = fixture.deliveredReview()
            fixture.useCase
                .publish(ReplyCommand(ORG, review.id, "Test", ReplySource.SLACK, fixture.channel.id))
                .shouldBeInstanceOf<ReplyOutcome.Simulated>()
            fixture.replies.all
                .single()
                .simulated shouldBe true
            fixture.store.published.shouldHaveSize(0)
        }

        test("odpověď se publikuje do storu, zapíše a zpráva ve Slacku se přepíše") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(
                        orgId = ORG,
                        reviewId = review.id,
                        body = "  Mrzí nás to, opravujeme.  ",
                        source = ReplySource.SLACK,
                        channelId = fixture.channel.id,
                        authorExternalId = "U0123",
                        authorDisplayName = "tadeas",
                    ),
                )

            outcome.shouldBeInstanceOf<ReplyOutcome.Published>()
            fixture.store.published.single().let { (identifier, storeReviewId, body) ->
                identifier shouldBe fixture.app.gpPackageName
                storeReviewId shouldBe review.storeReviewId
                body shouldBe "Mrzí nás to, opravujeme."
            }
            fixture.replies.all
                .single()
                .status shouldBe ReplyStatus.PUBLISHED
            fixture.reviews.stateUpdates shouldHaveSize 1
            fixture.reviews.stateUpdates
                .single()
                .second shouldBe ReviewState.REPLIED
            fixture.slack.replied
                .single()
                .replyText shouldBe "Mrzí nás to, opravujeme."
            fixture.audit.entries
                .single()
                .action shouldBe "reply.published"
        }

        test("dvojklik na Odeslat pošle odpověď jen jednou") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()
            val command =
                ReplyCommand(
                    orgId = ORG,
                    reviewId = review.id,
                    body = "Díky!",
                    source = ReplySource.SLACK,
                    channelId = fixture.channel.id,
                )

            fixture.useCase.publish(command)
            val second = fixture.useCase.publish(command)

            second.shouldBeInstanceOf<ReplyOutcome.AlreadyPublished>()
            fixture.store.published shouldHaveSize 1
        }

        test("prázdná odpověď se do storu nedostane") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(ORG, review.id, "   ", ReplySource.SLACK, fixture.channel.id),
                )

            outcome shouldBe ReplyOutcome.Rejected(ReplyRejection.EMPTY_BODY)
            fixture.store.published.shouldHaveSize(0)
        }

        test("odpověď delší než limit storu se ořízne už při zápisu") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()

            fixture.useCase.publish(
                ReplyCommand(ORG, review.id, "a".repeat(1_000), ReplySource.SLACK, fixture.channel.id),
            )

            fixture.store.published
                .single()
                .third.length shouldBe 350
            fixture.replies.all
                .single()
                .body.length shouldBe 350
        }

        test("chybějící klíč k odpovídání se pozná dřív, než se sáhne do storu") {
            val fixture = ReplyFixture(attachCredential = false)
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(ORG, review.id, "Díky!", ReplySource.SLACK, fixture.channel.id),
                )

            outcome shouldBe ReplyOutcome.Rejected(ReplyRejection.MISSING_CREDENTIAL)
            fixture.store.published.shouldHaveSize(0)
        }

        test("odmítnutí storu jde do vlákna pod zprávou a odpověď zůstane jako neúspěšná") {
            val fixture =
                ReplyFixture(storeFailure = StoreConnectorException(StoreErrorKind.INVALID_REQUEST, "Reply too long"))
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(ORG, review.id, "Díky!", ReplySource.SLACK, fixture.channel.id),
                )

            outcome.shouldBeInstanceOf<ReplyOutcome.Failed>().isRetryable shouldBe false
            fixture.slack.failures.single() shouldContain "Reply too long"
            fixture.replies.all
                .single()
                .status shouldBe ReplyStatus.FAILED
            // Recenze zůstává nezodpovězená, formulář ve Slacku taky — dá se zkusit znovu.
            fixture.reviews.stateUpdates.shouldHaveSize(0)
        }

        test("limit storu je opakovatelný") {
            val fixture = ReplyFixture(storeFailure = StoreConnectorException(StoreErrorKind.RATE_LIMITED, "quota"))
            val review = fixture.deliveredReview()

            fixture.useCase
                .publish(ReplyCommand(ORG, review.id, "Díky!", ReplySource.SLACK, fixture.channel.id))
                .shouldBeInstanceOf<ReplyOutcome.Failed>()
                .isRetryable shouldBe true
        }

        test("nepovedený úklid zprávy v kanálu nezruší publikovanou odpověď") {
            val fixture = ReplyFixture(channelFailure = ChannelException(ChannelErrorKind.NOT_FOUND, "message_not_found"))
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(ORG, review.id, "Díky!", ReplySource.SLACK, fixture.channel.id),
                )

            outcome.shouldBeInstanceOf<ReplyOutcome.Published>()
            fixture.replies.all
                .single()
                .status shouldBe ReplyStatus.PUBLISHED
        }

        test("odpověď z console nemá kanál a publikuje se stejně") {
            val fixture = ReplyFixture()
            val review = fixture.deliveredReview()

            val outcome =
                fixture.useCase.publish(
                    ReplyCommand(orgId = ORG, reviewId = review.id, body = "Díky!", source = ReplySource.CONSOLE),
                )

            outcome.shouldBeInstanceOf<ReplyOutcome.Published>()
            fixture.slack.replied.shouldHaveSize(0)
        }

        test("recenze z platformy bez konektoru se neodešle") {
            val fixture = ReplyFixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id, platform = Platform.IOS))

            fixture.useCase.publish(
                ReplyCommand(ORG, review.id, "Díky!", ReplySource.SLACK, fixture.channel.id),
            ) shouldBe ReplyOutcome.Rejected(ReplyRejection.NO_TARGET)
        }

        test("smazaná recenze je odmítnutí, ne pád") {
            val fixture = ReplyFixture()

            fixture.useCase.publish(
                ReplyCommand(ORG, Delivery.review(ORG, fixture.app.id).id, "Díky!", ReplySource.SLACK),
            ) shouldBe ReplyOutcome.Rejected(ReplyRejection.REVIEW_NOT_FOUND)
        }

        test("jazyk kanálu se drží i při úklidu zprávy") {
            val fixture = ReplyFixture()
            val english = fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, locale = MessageLocale.EN))
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            val message = fixture.messages.claim(ORG, review.id, english.id, review.contentHash)
            fixture.messages.markSent(ORG, message.id, english.targetRef, "1755600000.000200", Delivery.now)

            fixture.useCase.publish(ReplyCommand(ORG, review.id, "Thanks!", ReplySource.SLACK, english.id))

            fixture.slack.replied
                .single()
                .notification.locale shouldBe MessageLocale.EN
        }
    })
