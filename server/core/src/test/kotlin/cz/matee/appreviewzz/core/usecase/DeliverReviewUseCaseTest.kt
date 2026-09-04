package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.ReviewState
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicMention
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import cz.matee.appreviewzz.core.port.NotificationChannel
import cz.matee.appreviewzz.core.port.ReplySuggestion
import cz.matee.appreviewzz.core.port.ReviewAnalysis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.uuid.Uuid

private val ORG = OrganizationId(Uuid.random())

private class Fixture(
    suggestion: ReplySuggestion = ReplySuggestion.Suggested("Mrzí nás to, opravujeme.", "gemini-2.5-flash"),
    channels: List<NotificationChannel>? = null,
    /** `null` = instalace bez rozborů; zpráva pak vypadá jako před F8. */
    analysisResult: AnalysisResult? = null,
    /** Výklad složený z ID recenze — jako ho vrací skutečný model. */
    analysisEcho: ((String) -> ReviewAnalysis)? = null,
) {
    val apps = FakeAppRepository()
    val reviews = FakeReviewRepository()
    val channelRepository = FakeChannelRepository()
    val messages = FakeReviewMessageRepository()
    val slack = FakeNotificationChannel()
    val suggestions = FakeSuggestProvider(suggestion)
    val insights = FakeReviewInsightRepository()
    val app = apps.put(Ingest.app(ORG))

    private val analysis =
        if (analysisResult == null && analysisEcho == null) {
            null
        } else {
            val provider = FakeAnalysisProvider()
            analysisEcho?.let { provider.echo(analysis = it) }
            // Doručení může sáhnout na výklad víckrát (retry, druhý kanál) — proto stejná odpověď dokola.
            analysisResult?.let { result -> repeat(ANALYSIS_ANSWERS) { provider.answer(result) } }
            AnalyzeReviewsUseCase(
                apps = apps,
                reviews = reviews,
                insights = insights,
                appTopics = FakeAppTopicRepository(),
                provider = provider,
            )
        }

    val useCase =
        DeliverReviewUseCase(
            apps = apps,
            reviews = reviews,
            channels = channelRepository,
            messages = messages,
            secrets = secretResolver("xoxb-token"),
            suggestions = suggestions,
            analysis = analysis,
            notificationChannels = channels ?: listOf(slack),
            clock = fixedClock(Delivery.now),
        )
}

private const val ANALYSIS_ANSWERS = 5

class DeliverReviewUseCaseTest :
    FunSpec({
        test("zpráva nese štítky z výkladu recenze") {
            val fixture =
                Fixture(
                    analysisEcho = { id ->
                        ReviewAnalysis(
                            id = id,
                            sentiment = OverallSentiment.NEGATIVE,
                            type = ReviewType.BUG,
                            urgency = Urgency.HIGH,
                            language = "cs",
                            topics = listOf(TopicMention(Topic.CRASH.key, TopicSentiment.NEGATIVE, null)),
                            translation = null,
                        )
                    },
                )
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            fixture.useCase.deliver(ORG, review.id)

            val insight =
                fixture.slack.posted
                    .single()
                    .second.insight
            insight?.topics shouldContainExactly listOf(Topic.CRASH.labelCs)
            insight?.urgency shouldBe Urgency.HIGH
        }

        test("selhání rozboru recenzi nezdrží, jen odejde bez štítků") {
            val fixture = Fixture(analysisResult = AnalysisResult.Failed("Gemini vrátilo 503"))
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.sent shouldHaveSize 1
            fixture.slack.posted
                .single()
                .second.insight
                .shouldBeNull()
        }

        test("recenze odejde do kanálu i s návrhem a zpráva se zaznamená") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            val channel = fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.sent shouldHaveSize 1
            report.skipped.shouldBeNull()
            val (target, notification) = fixture.slack.posted.single()
            target.conversationId shouldBe channel.targetRef
            target.credential.value shouldBe "xoxb-token"
            notification.suggestedReply shouldBe "Mrzí nás to, opravujeme."
            notification.appName shouldBe "IsleGrow"
            fixture.messages.sent shouldHaveSize 1
            fixture.reviews.stateUpdates shouldContainExactly listOf(review.id to ReviewState.NOTIFIED)
        }

        test("opakované doručení téhož znění zprávu nepošle podruhé") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            fixture.useCase.deliver(ORG, review.id)
            val second = fixture.useCase.deliver(ORG, review.id)

            fixture.slack.posted shouldHaveSize 1
            second.deliveries.single().shouldBeAlreadySent()
        }

        test("návrh se generuje jednou na recenzi, ne jednou na kanál") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, locale = MessageLocale.EN))

            fixture.useCase.deliver(ORG, review.id)

            fixture.suggestions.calls shouldBe 1
            fixture.slack.posted shouldHaveSize 2
            // Každý kanál dostane vlastní jazyk — dva týmy nad jednou appkou.
            fixture.slack.posted.map { it.second.locale } shouldContainExactly listOf(MessageLocale.CS, MessageLocale.EN)
        }

        test("selhání AI doručení nezastaví, jen se zaznamená") {
            val fixture = Fixture(suggestion = ReplySuggestion.Failed("Gemini vrátilo 429"))
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.sent shouldHaveSize 1
            report.suggestionError shouldContain "429"
            fixture.slack.posted
                .single()
                .second.suggestedReply
                .shouldBeNull()
        }

        test("potlačená recenze se nedoručuje nikam") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id, state = ReviewState.SUPPRESSED))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.skipped shouldBe DeliverySkipReason.SUPPRESSED
            fixture.slack.posted.shouldHaveSize(0)
            fixture.suggestions.calls shouldBe 0
        }

        test("appka bez kanálu nebo s vypnutým kanálem se přeskočí") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))

            fixture.useCase.deliver(ORG, review.id).skipped shouldBe DeliverySkipReason.NO_CHANNEL

            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, enabled = false))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, deliverReviews = false))
            fixture.useCase.deliver(ORG, review.id).skipped shouldBe DeliverySkipReason.NO_CHANNEL
        }

        test("kanál bez credentialu se přeskočí, ostatní doručí") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, credentialId = null))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.sent shouldHaveSize 1
            report.deliveries
                .filterIsInstance<ChannelDelivery.Skipped>()
                .single()
                .reason shouldBe
                ChannelSkipReason.MISSING_CREDENTIAL
        }

        test("kanál typu bez implementace v procesu se přeskočí, ne spadne") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id, type = ChannelType.TEAMS))

            val report = fixture.useCase.deliver(ORG, review.id)

            report.deliveries
                .filterIsInstance<ChannelDelivery.Skipped>()
                .single()
                .reason shouldBe
                ChannelSkipReason.NO_IMPLEMENTATION
        }

        test("limit Slacku je opakovatelný, vyhozený bot ne") {
            val limited =
                Fixture(
                    channels = listOf(FakeNotificationChannel(failWith = ChannelException(ChannelErrorKind.RATE_LIMITED, "ratelimited"))),
                )
            val review = limited.reviews.put(Delivery.review(ORG, limited.app.id))
            limited.channelRepository.put(Delivery.channel(ORG, limited.app.id))

            val report = limited.useCase.deliver(ORG, review.id)

            report.isRetryable shouldBe true
            limited.messages.failed shouldHaveSize 1
            limited.reviews.stateUpdates.shouldHaveSize(0)

            val kicked =
                Fixture(
                    channels = listOf(FakeNotificationChannel(failWith = ChannelException(ChannelErrorKind.NOT_FOUND, "not_in_channel"))),
                )
            val other = kicked.reviews.put(Delivery.review(ORG, kicked.app.id))
            kicked.channelRepository.put(Delivery.channel(ORG, kicked.app.id))

            kicked.useCase.deliver(ORG, other.id).isRetryable shouldBe false
        }

        test("aktualizovaná recenze se doručí označená jako aktualizace") {
            val fixture = Fixture()
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id, state = ReviewState.UPDATED))
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))

            fixture.useCase.deliver(ORG, review.id)

            fixture.slack.posted
                .single()
                .second.isUpdate shouldBe true
        }

        test("smazaná recenze nebo vypnutá appka jsou přeskočení, ne chyba") {
            val fixture = Fixture()

            fixture.useCase.deliver(ORG, Delivery.review(ORG, fixture.app.id).id).skipped shouldBe
                DeliverySkipReason.REVIEW_NOT_FOUND
        }
    })

private fun ChannelDelivery.shouldBeAlreadySent() {
    check(this is ChannelDelivery.AlreadySent) { "Čekal jsem AlreadySent, dostal $this" }
}
