package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.ReplySuggestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val API_KEY = "AIza-testovaci-klic"

class GeminiSuggestReplyProviderTest :
    FunSpec({
        test("pošle systémovou instrukci s limitem a vrátí návrh") {
            val engine = RecordingEngine { respond(geminiResponse("Mrzí nás to, chybu už opravujeme."), headers = jsonHeaders) }
            val provider = GeminiSuggestReplyProvider(engine.client(), SecretPayload(API_KEY))

            val suggestion = provider.suggest(request(instructions = "Vždy se podepiš jako tým IsleGrow."))

            suggestion shouldBe ReplySuggestion.Suggested("Mrzí nás to, chybu už opravujeme.", "gemini-2.5-flash")
            val sent = engine.requests.single()
            sent.url.toString() shouldContain "/models/gemini-2.5-flash:generateContent"
            sent.headers["x-goog-api-key"] shouldBe API_KEY

            val body = Json.parseToJsonElement(String(sent.body.toByteArray())).jsonObject
            val system =
                body["systemInstruction"]!!
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            system shouldContain "350 characters"
            system shouldContain "locale cs"
            system shouldContain "Vždy se podepiš jako tým IsleGrow."
            val user =
                body["contents"]!!
                    .jsonArray[0]
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            user shouldContain "Rating: 2/5"
            user shouldContain "Po updatu se nedostanu dál."
            // Přemýšlení nad odpovědí na recenzi je jen účet navíc.
            body["generationConfig"]!!
                .jsonObject["thinkingConfig"]!!
                .jsonObject["thinkingBudget"]!!
                .jsonPrimitive.content shouldBe "0"
        }

        test("delší návrh než limit storu se ořízne, ne odešle") {
            val long = "Děkujeme za zpětnou vazbu. " + "Pracujeme na tom. ".repeat(50)
            val engine = RecordingEngine { respond(geminiResponse(long), headers = jsonHeaders) }
            val provider = GeminiSuggestReplyProvider(engine.client(), SecretPayload(API_KEY))

            val suggestion = provider.suggest(request(platform = Platform.ANDROID, maxLength = 350))

            suggestion.shouldBeInstanceOf<ReplySuggestion.Suggested>().text.length shouldBeLessThanOrEqual 350
        }

        test("chyba providera nezhatí doručení, vrátí se jako Failed") {
            val engine = RecordingEngine { respondError(HttpStatusCode.TooManyRequests, """{"error":{"message":"quota"}}""") }
            val provider = GeminiSuggestReplyProvider(engine.client(), SecretPayload(API_KEY))

            val suggestion = provider.suggest(request())

            suggestion.shouldBeInstanceOf<ReplySuggestion.Failed>().message shouldContain "429"
        }

        test("zablokované zadání je Failed, ne prázdný návrh") {
            val engine =
                RecordingEngine {
                    respond("""{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}""", headers = jsonHeaders)
                }
            val provider = GeminiSuggestReplyProvider(engine.client(), SecretPayload(API_KEY))

            provider.suggest(request()).shouldBeInstanceOf<ReplySuggestion.Failed>().message shouldContain "SAFETY"
        }

        test("recenze bez textu má v promptu řečeno, že text chybí") {
            val engine = RecordingEngine { respond(geminiResponse("Díky za hodnocení!"), headers = jsonHeaders) }
            val provider = GeminiSuggestReplyProvider(engine.client(), SecretPayload(API_KEY))

            provider.suggest(request(body = null, stars = 5))

            val body =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .single()
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject
            val user =
                body["contents"]!!
                    .jsonArray[0]
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            user shouldContain "(no text, rating only)"
        }

        test("klíč se nedostane do logu ani do toString providera") {
            SecretPayload(API_KEY).toString() shouldNotContain API_KEY
        }
    })
