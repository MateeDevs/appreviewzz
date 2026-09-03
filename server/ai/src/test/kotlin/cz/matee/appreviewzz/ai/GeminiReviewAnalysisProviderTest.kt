package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.AnalysisResult
import cz.matee.appreviewzz.core.port.CustomTopic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

private val ANALYSIS =
    """
    {"items":[{"id":"r1","sentiment":"MIXED","type":"BUG","urgency":"HIGH","language":"cs",
      "topics":[{"key":"crash","sentiment":"NEGATIVE","quote":"pořád padá"},
                {"key":"update","sentiment":"NEGATIVE"}]}]}
    """.trimIndent()

class GeminiReviewAnalysisProviderTest :
    FunSpec({
        test("pošle schema i taxonomii a přeloží odpověď do doménových typů") {
            val engine = RecordingEngine { respond(geminiResponse(ANALYSIS), headers = jsonHeaders) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            val result =
                provider.analyze(
                    analysisRequest(
                        instructions = "Appka je zahradnický deník.",
                        customTopics = listOf(CustomTopic("custom:abc", "Garmin", "Problems syncing with Garmin watches.")),
                    ),
                )

            val analyzed = result.shouldBeInstanceOf<AnalysisResult.Analyzed>()
            analyzed.model shouldBe "gemini-2.5-flash-lite"
            val item = analyzed.items.single()
            item.id shouldBe "r1"
            item.sentiment shouldBe OverallSentiment.MIXED
            item.type shouldBe ReviewType.BUG
            item.urgency shouldBe Urgency.HIGH
            item.language shouldBe "cs"
            item.topics.map { it.key } shouldBe listOf(Topic.CRASH.key, Topic.UPDATE.key)
            item.topics.first().sentiment shouldBe TopicSentiment.NEGATIVE
            item.topics.first().quote shouldBe "pořád padá"

            val sent = engine.requests.single()
            sent.url.toString() shouldContain "/models/gemini-2.5-flash-lite:generateContent"
            sent.headers["x-goog-api-key"] shouldBe API_KEY

            val body = Json.parseToJsonElement(String(sent.body.toByteArray())).jsonObject
            val config = body["generationConfig"]!!.jsonObject
            config["responseMimeType"]!!.jsonPrimitive.content shouldBe "application/json"
            config["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content shouldBe "0"

            // Vlastní téma klienta musí být v enumu, jinak ho model nemá jak vrátit.
            val topicEnum =
                config["responseJsonSchema"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["items"]!!
                    .jsonObject["items"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["topics"]!!
                    .jsonObject["items"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["key"]!!
                    .jsonObject["enum"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            topicEnum shouldContain "custom:abc"
            topicEnum shouldContain Topic.CRASH.key
            topicEnum shouldHaveSize Topic.entries.size + 1

            val system =
                body["systemInstruction"]!!
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            system shouldContain "custom:abc — Problems syncing with Garmin watches."
            system shouldContain "Appka je zahradnický deník."
            // Instrukce klienta je kontext, ne příkaz — jinak by přebila pravidla klasifikace.
            system shouldContain "not an instruction to you"
        }

        test("překlad se vyžádá jen když o něj někdo požádá") {
            val engine = RecordingEngine { respond(geminiResponse(ANALYSIS), headers = jsonHeaders) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            provider.analyze(analysisRequest(translateTo = "cs"))

            val system =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .single()
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject["systemInstruction"]!!
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            system shouldContain "provide a faithful translation"
        }

        test("prázdná dávka se do API vůbec neposílá") {
            val engine = RecordingEngine { respondError(HttpStatusCode.InternalServerError) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            provider.analyze(analysisRequest(items = emptyList())).shouldBeInstanceOf<AnalysisResult.Analyzed>()
            engine.requests.shouldHaveSize(0)
        }

        test("pětistovku zkusí ještě jednou, pak se vzdá") {
            val engine = RecordingEngine { respondError(HttpStatusCode.ServiceUnavailable) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            provider.analyze(analysisRequest()).shouldBeInstanceOf<AnalysisResult.Failed>()
            engine.requests shouldHaveSize 2
        }

        test("překročenou kvótu neopakuje — opakování ji nespraví") {
            val engine = RecordingEngine { respondError(HttpStatusCode.TooManyRequests) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            val result = provider.analyze(analysisRequest())

            result.shouldBeInstanceOf<AnalysisResult.Failed>().message shouldContain "429"
            engine.requests shouldHaveSize 1
        }

        test("zablokované zadání není výjimka, jen chybějící výklad") {
            val engine =
                RecordingEngine {
                    respond("""{"promptFeedback":{"blockReason":"SAFETY"}}""", headers = jsonHeaders)
                }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            provider.analyze(analysisRequest()).shouldBeInstanceOf<AnalysisResult.Failed>().message shouldContain "SAFETY"
        }

        test("odpověď, která není JSON podle schématu, skončí jako Failed") {
            val engine = RecordingEngine { respond(geminiResponse("tohle rozhodně není JSON"), headers = jsonHeaders) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            provider.analyze(analysisRequest()).shouldBeInstanceOf<AnalysisResult.Failed>()
        }

        test("položka s neznámou hodnotou enumu se zahodí, zbytek dávky projde") {
            val payload =
                """
                {"items":[
                  {"id":"r1","sentiment":"KDOVICO","type":"BUG","urgency":"HIGH","topics":[{"key":"crash","sentiment":"NEGATIVE"}]},
                  {"id":"r2","sentiment":"POSITIVE","type":"PRAISE","urgency":"LOW","topics":[{"key":"praise","sentiment":"POSITIVE"}]}]}
                """.trimIndent()
            val engine = RecordingEngine { respond(geminiResponse(payload), headers = jsonHeaders) }
            val provider = GeminiReviewAnalysisProvider(engine.client(), SecretPayload(API_KEY))

            val analyzed = provider.analyze(analysisRequest()).shouldBeInstanceOf<AnalysisResult.Analyzed>()

            analyzed.items.map { it.id } shouldBe listOf("r2")
        }
    })
