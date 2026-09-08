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
import io.kotest.matchers.collections.shouldBeEmpty
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
    autoThanks: Boolean = false,
    autoThanksTemplate: String? = null,
) {
    val apps = FakeAppRepository()
    val reviews = FakeReviewRepository()
    val channelRepository = FakeChannelRepository()
    val messages = FakeReviewMessageRepository()
    val slack = FakeNotificationChannel()
    val suggestions = FakeSuggestProvider(suggestion)
    val insights = FakeReviewInsightRepository()
    val app = apps.put(Ingest.app(ORG).copy(autoThanksEnabled = autoThanks, autoThanksTemplate = autoThanksTemplate))

    /** Automatická poděkování, která by šla do fronty odpovědí. */
    val autoReplies = mutableListOf<AutoReply>()

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
            enqueueAutoReply = { reply -> autoReplies.add(reply) },
            clock = fixedClock(Delivery.now),
        )
}

private const val ANALYSIS_ANSWERS = 5

class DeliverReviewUseCaseTest :
    FunSpec({
        /** Výklad pochvaly: pět hvězd, typ PRAISE, žádné záporné téma. */
        fun praise(reviewId: String) =
            ReviewAnalysis(
                id = reviewId,
                sentiment = OverallSentiment.POSITIVE,
                type = ReviewType.PRAISE,
                urgency = Urgency.LOW,
                language = "cs",
                topics = listOf(TopicMention(Topic.PRAISE.key, TopicSentiment.POSITIVE, null)),
                translation = null,
            )

        test("pětihvězdičková pochvala se zařadí k automatickému poděkování a zpráva nemá formulář") {
            val fixture = Fixture(autoThanks = true, analysisEcho = ::praise)
            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            val review =
                fixture.reviews.put(Delivery.review(ORG, fixture.app.id, starRating = 5, body = "Skvělá appka, díky!"))

            fixture.useCase.deliver(ORG, review.id)

            fixture.autoReplies.single().reviewId shouldBe review.id
            val notification =
                fixture.slack.posted
                    .single()
                    .second
            // Odpověď je ve frontě; formulář, který za vteřinu přestane dávat smysl, tam nepatří.
            notification.autoReply shouldContain "Mrzí nás to"
        }

        test("pochvala se záporným tématem jde do kanálu s formulářem, ne automaticky") {
            // Pět hvězd a přesto stížnost na reklamy — přesně ten případ, kdy automatické
            // „děkujeme za pochvalu" vypadá, že jsme recenzi vůbec nečetli.
            val fixture =
                Fixture(
                    autoThanks = true,
                    analysisEcho = { id ->
                        praise(id).copy(topics = listOf(TopicMention(Topic.ADS.key, TopicSentiment.NEGATIVE, null)))
                    },
                )
            val review =
                fixture.reviews.put(Delivery.review(ORG, fixture.app.id, starRating = 5, body = "Super, jen ty reklamy."))

            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            fixture.useCase.deliver(ORG, review.id)

            fixture.autoReplies.shouldBeEmpty()
            fixture.slack.posted
                .single()
                .second.autoReply
                .shouldBeNull()
        }

        test("bez výkladu se automaticky neodpovídá — bezpečná strana je ta, kde odpovídá člověk") {
            val fixture = Fixture(autoThanks = true)
            val review =
                fixture.reviews.put(Delivery.review(ORG, fixture.app.id, starRating = 5, body = "Skvělá appka!"))

            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            fixture.useCase.deliver(ORG, review.id)

            fixture.autoReplies.shouldBeEmpty()
        }

        test("recenze, která už má odpověď ve storu, druhou nedostane") {
            val fixture = Fixture(autoThanks = true, analysisEcho = ::praise)
            val review =
                fixture.reviews.put(
                    Delivery.review(
                        ORG,
                        fixture.app.id,
                        starRating = 5,
                        body = "Skvělá appka!",
                        developerResponse = "Děkujeme!",
                    ),
                )

            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            fixture.useCase.deliver(ORG, review.id)

            fixture.autoReplies.shouldBeEmpty()
        }

        test("bez návrhu od AI se použije záložní text, a bez něj se neodešle nic") {
            val withTemplate =
                Fixture(
                    suggestion = ReplySuggestion.Unavailable,
                    autoThanks = true,
                    autoThanksTemplate = "Díky za hezká slova!",
                    analysisEcho = ::praise,
                )
            val review =
                withTemplate.reviews.put(Delivery.review(ORG, withTemplate.app.id, starRating = 5, body = "Paráda!"))
            withTemplate.channelRepository.put(Delivery.channel(ORG, withTemplate.app.id))
            withTemplate.useCase.deliver(ORG, review.id)
            withTemplate.autoReplies.single().body shouldBe "Díky za hezká slova!"

            val without = Fixture(suggestion = ReplySuggestion.Unavailable, autoThanks = true, analysisEcho = ::praise)
            val other = without.reviews.put(Delivery.review(ORG, without.app.id, starRating = 5, body = "Paráda!"))
            without.channelRepository.put(Delivery.channel(ORG, without.app.id))
            without.useCase.deliver(ORG, other.id)
            without.autoReplies.shouldBeEmpty()
        }

        test("vypnuté poděkování nechává i dokonalou pochvalu na člověku") {
            val fixture = Fixture(analysisEcho = ::praise)
            val review = fixture.reviews.put(Delivery.review(ORG, fixture.app.id, starRating = 5, body = "Nejlepší!"))

            fixture.channelRepository.put(Delivery.channel(ORG, fixture.app.id))
            fixture.useCase.deliver(ORG, review.id)

            fixture.autoReplies.shouldBeEmpty()
        }

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
