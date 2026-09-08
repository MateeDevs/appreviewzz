package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.NarrativeQuote
import cz.matee.appreviewzz.core.port.NarrativeRequest
import cz.matee.appreviewzz.core.port.NarrativeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode

private const val API_KEY = "AIza-testovaci-klic"

private val NARRATIVE =
    """{"summary":"Za období přišlo 20 recenzí, převažují stížnosti na pády.","citedReviewIds":["r1"]}"""

private fun narrativeRequest() =
    NarrativeRequest(
        appName = "IsleGrow",
        locale = MessageLocale.CS,
        aggregatesJson = """{"reviews":20,"negativePercent":40}""",
        quotes = listOf(NarrativeQuote("r1", "pořád to padá")),
    )

/**
 * Provider jen mluví s API — ověřování citací a čísel dělá use case. Testuje se tedy to,
 * co je vidět na drátě: schema, agregáty i kandidátské citáty v požadavku a překlad
 * odpovědi. Selhání nesmí být výjimka: rozbor odejde i bez odstavce.
 */
class GeminiNarrativeProviderTest :
    FunSpec({
        test("pošle agregáty i kandidátské citáty a vrátí shrnutí s citovanými id") {
            val engine = RecordingEngine { respond(geminiResponse(NARRATIVE), headers = jsonHeaders) }
            val provider = GeminiNarrativeProvider(engine.client(), SecretPayload(API_KEY))

            val result = provider.narrate(narrativeRequest())

            val written = result.shouldBeInstanceOf<NarrativeResult.Written>()
            written.narrative.summary shouldContain "20 recenzí"
            written.narrative.citedReviewIds shouldBe listOf("r1")

            val sent =
                String(
                    engine.requests
                        .single()
                        .body
                        .toByteArray(),
                )
            sent shouldContain "negativePercent"
            sent shouldContain "pořád to padá"
            // Bez schematu by model klidně vrátil odstavec v prostém textu a parsování by padlo.
            sent shouldContain "responseJsonSchema"
            sent shouldContain "citedReviewIds"
        }

        test("chyba modelu neshodí rozbor, jen vrátí Failed") {
            val engine = RecordingEngine { respondError(HttpStatusCode.ServiceUnavailable) }
            val provider = GeminiNarrativeProvider(engine.client(), SecretPayload(API_KEY))

            val result = provider.narrate(narrativeRequest())

            result.shouldBeInstanceOf<NarrativeResult.Failed>()
        }

        test("prázdné shrnutí je selhání, ne prázdný odstavec ve zprávě") {
            val engine =
                RecordingEngine { respond(geminiResponse("""{"summary":"  ","citedReviewIds":[]}"""), headers = jsonHeaders) }
            val provider = GeminiNarrativeProvider(engine.client(), SecretPayload(API_KEY))

            provider.narrate(narrativeRequest()).shouldBeInstanceOf<NarrativeResult.Failed>()
        }
    })
