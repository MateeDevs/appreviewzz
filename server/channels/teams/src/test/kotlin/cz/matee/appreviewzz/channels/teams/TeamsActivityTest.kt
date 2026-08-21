package cz.matee.appreviewzz.channels.teams

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun activityJson(
    type: String = "message",
    value: String? = """{"verb":"sendReply","replyText":"Díky za zpětnou vazbu!"}""",
): String =
    """
    {
      "type": "$type",
      "channelId": "msteams",
      "serviceUrl": "$SERVICE_URL",
      "conversation": { "id": "19:abc@thread.tacv2;messageid=1" },
      "replyToId": "1755600000000",
      "from": { "id": "29:user", "name": "Tadeáš Sosín" },
      "channelData": { "tenant": { "id": "$TENANT_ID" } }
      ${value?.let { ""","value": $it""" }.orEmpty()}
    }
    """.trimIndent()

class TeamsActivityTest :
    FunSpec({
        test("kliknutí na Odeslat dá všechno, co reply pipeline potřebuje") {
            val submission = TeamsActivity.parse(activityJson())?.replySubmission()

            submission shouldNotBe null
            submission!!.conversationId shouldBe "19:abc@thread.tacv2;messageid=1"
            submission.activityId shouldBe "1755600000000"
            submission.text shouldBe "Díky za zpětnou vazbu!"
            submission.serviceUrl shouldBe SERVICE_URL
            submission.userId shouldBe "29:user"
            submission.userName shouldBe "Tadeáš Sosín"
            submission.tenantId shouldBe TENANT_ID
        }

        test("aktivity, které neobsluhujeme, se potvrdí a zahodí") {
            // Přidání bota do týmu, systémové zprávy, kliknutí na jiné tlačítko.
            TeamsActivity.parse(activityJson(type = "conversationUpdate"))?.replySubmission().shouldBeNull()
            TeamsActivity.parse(activityJson(value = null))?.replySubmission().shouldBeNull()
            TeamsActivity.parse(activityJson(value = """{"verb":"somethingElse"}"""))?.replySubmission().shouldBeNull()
        }

        test("nečitelné tělo není chyba k logování jako pád, ale zahození") {
            TeamsActivity.parse("tohle není JSON").shouldBeNull()
        }

        test("prázdný vstup projde parsováním; co s ním, rozhoduje příjem odpovědi") {
            val submission = TeamsActivity.parse(activityJson(value = """{"verb":"sendReply"}"""))?.replySubmission()

            submission shouldNotBe null
            submission!!.text shouldBe ""
        }
    })
