package cz.matee.appreviewzz.channels.slack

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private fun payload(
    actionId: String = SlackBlocks.SUBMIT_ACTION_ID,
    blockId: String = SlackBlocks.REPLY_BLOCK_ID,
    value: String = "Díky za zpětnou vazbu, opravujeme to.",
    type: String = "block_actions",
): String =
    """
    {
      "type": "$type",
      "user": { "id": "U0123", "name": "tadeas", "username": "tadeas" },
      "team": { "id": "T0123" },
      "container": { "type": "message", "message_ts": "1755600000.000100", "channel_id": "C0123" },
      "actions": [ { "action_id": "$actionId", "value": "11111111-1111-1111-1111-111111111111" } ],
      "state": { "values": { "$blockId": { "${SlackBlocks.REPLY_ACTION_ID}": { "type": "plain_text_input", "value": "$value" } } } },
      "message": { "ts": "1755600000.000100", "metadata": { "event_type": "appreviewzz_review" } }
    }
    """.trimIndent()

class SlackInteractionTest :
    FunSpec({
        test("kliknutí na Odeslat nese text, kanál, ts i uživatele") {
            val submission = SlackInteraction.parse(payload())

            submission shouldBe
                SlackReplySubmission(
                    conversationId = "C0123",
                    messageTs = "1755600000.000100",
                    text = "Díky za zpětnou vazbu, opravujeme to.",
                    userId = "U0123",
                    userName = "tadeas",
                    teamId = "T0123",
                )
        }

        test("jiná akce se ignoruje, ne zaloguje jako chyba") {
            SlackInteraction.parse(payload(actionId = "neco_jineho")).shouldBeNull()
            SlackInteraction.parse(payload(type = "view_submission")).shouldBeNull()
        }

        test("text se čte z pojmenovaného bloku, ne z prvního, který má hodnotu") {
            // n8n bralo „první block_id"; jakýkoli další vstup ve zprávě by mu podstrčil cizí text.
            SlackInteraction.parse(payload(blockId = "cizi_blok"))?.text shouldBe ""
        }

        test("rozbitý payload nespadne") {
            SlackInteraction.parse("tohle není JSON").shouldBeNull()
            SlackInteraction.parse("""{"type":"block_actions"}""").shouldBeNull()
        }
    })
