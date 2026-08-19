package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReplyRendering
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

class SlackBlocksTest :
    FunSpec({
        test("zpráva s recenzí má hlavičku, text, vstup s návrhem a tlačítko") {
            val blocks = SlackBlocks.review(notification())

            blocks
                .ofType("header")
                .single()
                .at("text", "text")
                ?.jsonPrimitive
                ?.content shouldBe "🤖 Jana N. ★★☆☆☆"
            blocks.render() shouldContain "Po updatu se nedostanu dál."

            val input = blocks.ofType("input").single()
            input.text("block_id") shouldBe SlackBlocks.REPLY_BLOCK_ID
            input.at("element", "action_id")?.jsonPrimitive?.content shouldBe SlackBlocks.REPLY_ACTION_ID
            input.at("element", "initial_value")?.jsonPrimitive?.content shouldBe "Mrzí nás to, chybu už opravujeme."
            input.at("element", "max_length")?.jsonPrimitive?.content shouldBe "350"
            input.at("label", "text")?.jsonPrimitive?.content shouldContain "max 350 znaků"

            val button =
                blocks
                    .ofType("actions")
                    .single()
                    .getValue("elements")
                    .jsonArray
                    .single()
                    .jsonObject
            button.text("action_id") shouldBe SlackBlocks.SUBMIT_ACTION_ID
            button.at("text", "text")?.jsonPrimitive?.content shouldBe "Odeslat"
        }

        test("hvězdičky kreslí unicode, ne custom emoji workspace") {
            val blocks = SlackBlocks.review(notification(review(stars = 5)))

            blocks.render() shouldContain "★★★★★"
            // :empty_star: je custom emoji, které v cizím workspace neexistuje.
            blocks.render() shouldNotContain "empty_star"
        }

        test("iOS vstup se vejde do slackového stropu 3000 znaků") {
            val blocks = SlackBlocks.review(notification(review(platform = Platform.IOS)))

            blocks
                .ofType("input")
                .single()
                .at("element", "max_length")
                ?.jsonPrimitive
                ?.content shouldBe "3000"
        }

        test("dřívější odpověď se ukáže i s varováním podle platformy") {
            val blocks = SlackBlocks.review(notification(review(developerResponse = "Díky za nahlášení!")))

            blocks.render() shouldContain "Už jste na tuto recenzi odpověděli"
            blocks.render() shouldContain "> Díky za nahlášení!"
            blocks
                .ofType("input")
                .single()
                .at("label", "text")
                ?.jsonPrimitive
                ?.content shouldContain "přidána do konverzace"
        }

        test("text recenze se escapuje, aby se z něj nestal markup") {
            val blocks = SlackBlocks.review(notification(review(body = "<https://zlo|klikni> & <@U123>")))

            blocks.render() shouldContain "&lt;https://zlo|klikni&gt; &amp; &lt;@U123&gt;"
        }

        test("aktualizovaná recenze se odliší od první notifikace") {
            SlackBlocks.review(notification(isUpdate = true)).render() shouldContain "Aktualizovaná recenze"
            SlackBlocks.review(notification()).render() shouldNotContain "Aktualizovaná recenze"
        }

        test("po odeslání zmizí vstup i tlačítko a přibude odpověď") {
            val blocks =
                SlackBlocks.replied(
                    ReplyRendering(
                        notification = notification(),
                        replyText = "Mrzí nás to, oprava je v testování.",
                        authorDisplayName = "Tadeáš",
                        repliedAt = Instant.parse("2026-08-19T15:00:00Z"),
                    ),
                )

            blocks.ofType("input") shouldHaveSize 0
            blocks.ofType("actions") shouldHaveSize 0
            blocks.render() shouldContain "Odpověděli jste"
            blocks.render() shouldContain "Mrzí nás to, oprava je v testování."
            blocks.render() shouldContain "Tadeáš"
            blocks.render() shouldContain "Recenze byla zpracována"
        }

        test("anglický kanál dostane anglické texty") {
            val blocks = SlackBlocks.review(notification(locale = MessageLocale.EN))

            blocks.render() shouldContain "Suggested reply (max 350 characters)"
            blocks.render() shouldContain "\"text\":\"Send\""
        }

        test("chybová zpráva do vlákna nese důvod, ale ne celý stack") {
            val blocks = SlackBlocks.failure(notification(), "a".repeat(2_000))

            blocks.render() shouldContain "Odpověď se nepodařilo odeslat"
            blocks.render().length shouldBeLessThanOrEqual 1_200
        }

        test("datum je slackové dynamické, s fallbackem v zóně appky") {
            val blocks = SlackBlocks.review(notification())

            blocks.render() shouldContain "<!date^${SUBMITTED.epochSeconds}^{date_short} {time}|"
            blocks.render() shouldContain "14:30"
        }

        test("dlouhá hlavička se ořízne na slackový limit") {
            val long: ReviewNotification = notification(review(author = "A".repeat(300)))

            val header =
                SlackBlocks
                    .review(long)
                    .ofType("header")
                    .single()
                    .at("text", "text")!!
                    .jsonPrimitive.content
            header.length shouldBeLessThanOrEqual 150
        }
    })
