package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReplyRendering
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

class TeamsCardsTest :
    FunSpec({
        test("karta recenze má hvězdy, text, vstup s návrhem a tlačítko") {
            val card = TeamsCards.review(notification(review(stars = 2)))

            card.text("version") shouldBe TeamsCards.ADAPTIVE_CARD_VERSION
            val rendered = card.render()
            rendered shouldContain "★★☆☆☆"
            rendered shouldContain "Po updatu se nedostanu dál."
            rendered shouldContain "Návrh odpovědi (max 350 znaků)"

            val action =
                card
                    .getValue("actions")
                    .jsonArray
                    .single()
                    .jsonObject
            action.text("type") shouldBe "Action.Submit"
            action.at("data", "verb")?.jsonPrimitive?.content shouldBe TeamsCards.SEND_VERB
            action.at("data", "reviewId")?.jsonPrimitive?.content shouldBe "11111111-1111-1111-1111-111111111111"
        }

        test("do payloadu tlačítka se nebalí obsah recenze — routing jede přes databázi") {
            val action =
                TeamsCards
                    .review(notification())
                    .getValue("actions")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("data")
                    .jsonObject

            // Dnešní n8n sem cpe celé customerTexts/developerTexts a listener jim věří.
            action.keys shouldBe setOf("verb", "reviewId")
        }

        test("iOS recenze nese jiný limit odpovědi než Android") {
            val ios = TeamsCards.review(notification(review(platform = Platform.IOS))).render()

            ios shouldContain "🍎"
            ios shouldContain "Návrh odpovědi (max 5970 znaků)"
        }

        test("recenze s odpovědí vývojáře varuje, co další odpověď udělá") {
            val android = TeamsCards.review(notification(review(developerResponse = "Díky, díváme se na to."))).render()
            val ios =
                TeamsCards
                    .review(notification(review(platform = Platform.IOS, developerResponse = "Díky, díváme se na to.")))
                    .render()

            android shouldContain "Další odpověď bude přidána do konverzace."
            ios shouldContain "Nová odpověď přepíše tu předchozí."
        }

        test("aktualizovaná recenze je odlišená od první notifikace") {
            TeamsCards.review(notification(isUpdate = true)).render() shouldContain "Aktualizovaná recenze"
        }

        test("anglický kanál dostane anglickou kartu") {
            val card = TeamsCards.review(notification(locale = MessageLocale.EN)).render()

            card shouldContain "Suggested reply (max 350 characters)"
            card shouldNotContain "Návrh odpovědi"
        }

        test("karta po odeslání ukazuje odpověď a autora, ne formulář") {
            val card =
                TeamsCards
                    .replied(
                        ReplyRendering(
                            notification = notification(),
                            replyText = "Opravíme to v příští verzi.",
                            authorDisplayName = "Tadeáš",
                            repliedAt = Instant.parse("2026-08-21T09:00:00Z"),
                        ),
                    ).render()

            card shouldContain "Odpověděli jste"
            card shouldContain "Opravíme to v příští verzi."
            card shouldContain "Tadeáš"
            card shouldNotContain TeamsCards.REPLY_INPUT_ID
        }

        test("chybová karta nese důvod, ne jen že se to nepovedlo") {
            val card = TeamsCards.failure(notification(), "Google Play: 400 reply too long").render()

            card shouldContain "Odpověď se nepodařilo odeslat"
            card shouldContain "reply too long"
        }
    })
