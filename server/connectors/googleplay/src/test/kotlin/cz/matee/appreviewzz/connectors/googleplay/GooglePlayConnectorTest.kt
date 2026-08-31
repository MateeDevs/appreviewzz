package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreContext
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private const val PACKAGE_NAME = "cz.matee.islegrow"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class GooglePlayConnectorTest :
    FunSpec({
        val context = StoreContext(appIdentifier = PACKAGE_NAME, credential = TestServiceAccount.payload())

        test("dotáhne jednu recenzi po ID i s odpovědí, kterou napsal někdo v Play Console") {
            // Tohle je jediná cesta, jak se o takové odpovědi dozvědět: reviews.list vrací ~týden zpět.
            val engine =
                RecordingEngine { request ->
                    respond(fixture("review-single.json"), headers = jsonHeaders)
                        .takeIf { request.url.encodedPath.endsWith("/reviews/gp:AOqpTOFakeReviewIdentifier1") }
                }
            val connector = GooglePlayConnector(engine.client())

            val review = connector.fetchReview(context, "gp:AOqpTOFakeReviewIdentifier1")

            review.shouldNotBeNull()
            review.starRating shouldBe 1
            review.developerResponseBody shouldContain "můžete nám prosím upřesnit"
            review.developerResponseAt shouldBe Instant.fromEpochSeconds(1756211100)
            engine.requests.last().method shouldBe HttpMethod.Get
        }

        test("recenze, kterou store nezná, je null — ne výjimka") {
            val engine =
                RecordingEngine {
                    respond(
                        """{"error":{"code":404,"message":"Review not found.","status":"NOT_FOUND"}}""",
                        status = HttpStatusCode.NotFound,
                        headers = jsonHeaders,
                    )
                }

            GooglePlayConnector(engine.client()).fetchReview(context, "gp:smazana") shouldBe null
        }

        test("stáhne a normalizuje recenze napříč stránkami") {
            val engine =
                RecordingEngine { request ->
                    when (request.url.parameters["token"]) {
                        null -> respond(fixture("reviews-page1.json"), headers = jsonHeaders)
                        "stranka-2" -> respond(fixture("reviews-page2.json"), headers = jsonHeaders)
                        else -> null
                    }
                }
            val connector = GooglePlayConnector(engine.client())

            val reviews = connector.fetchReviews(context)

            // Třetí recenze na první stránce nemá userComment (smazaná) — přeskakuje se.
            reviews shouldHaveSize 3
            val first = reviews.first()
            first.platform shouldBe Platform.ANDROID
            first.storeReviewId shouldBe "gp:AOqpTOFakeReviewIdentifier1"
            first.starRating shouldBe 2
            first.authorName shouldBe "Jana N."
            first.title shouldBe null
            first.appVersion shouldBe "3.2.1"
            first.device shouldBe "Google Pixel 8 Pro"
            first.locale shouldBe "cs"
            first.submittedAt shouldBe Instant.fromEpochSeconds(1755594000)
            first.developerResponseBody shouldBe null

            val answered = reviews[1]
            answered.developerResponseBody shouldBe "Díky moc! Rádi to slyšíme."
            answered.developerResponseAt shouldBe Instant.fromEpochSeconds(1755593000)

            reviews[2].storeReviewId shouldBe "gp:AOqpTOFakeReviewIdentifier3"
        }

        test("access token se vyžádá jednou a pak se recykluje z cache") {
            val engine = RecordingEngine { respond(fixture("reviews-page2.json"), headers = jsonHeaders) }
            val connector = GooglePlayConnector(engine.client())

            connector.fetchReviews(context)
            connector.fetchReviews(context)

            engine.tokenRequests shouldBe 1
        }

        test("JWT assertion je podepsaná service accountem a míří na správný scope") {
            val engine = RecordingEngine { respond(fixture("reviews-page2.json"), headers = jsonHeaders) }
            GooglePlayConnector(engine.client()).fetchReviews(context)

            val tokenRequest = engine.requests.first { it.url.toString().startsWith(TestServiceAccount.TOKEN_URI) }
            val form = String(tokenRequest.body.toByteArray()).split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            form["grant_type"] shouldBe "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"

            val assertion = java.net.URLDecoder.decode(form.getValue("assertion"), Charsets.UTF_8)
            val (header, claims) = assertion.split(".")
            val claimsJson = Json.parseToJsonElement(String(base64UrlDecode(claims))).jsonObject
            String(base64UrlDecode(header)) shouldContain "RS256"
            claimsJson["iss"]?.jsonPrimitive?.content shouldBe TestServiceAccount.CLIENT_EMAIL
            claimsJson["scope"]?.jsonPrimitive?.content shouldBe GoogleOAuth.ANDROID_PUBLISHER_SCOPE
            claimsJson["aud"]?.jsonPrimitive?.content shouldBe TestServiceAccount.TOKEN_URI
            verifySignature(assertion) shouldBe true
        }

        test("odpověď se publikuje a delší než 350 znaků se ořízne") {
            val engine =
                RecordingEngine { request ->
                    if (request.method == HttpMethod.Post) respond(fixture("reply-response.json"), headers = jsonHeaders) else null
                }
            val connector = GooglePlayConnector(engine.client())
            val longBody = "a".repeat(400)

            val published = connector.publishReply(context, "gp:AOqpTOFakeReviewIdentifier1", longBody)

            published.publishedAt shouldBe Instant.fromEpochSeconds(1755600000)
            // Token endpoint je taky POST, takže vybíráme podle cesty.
            val request = engine.requests.first { it.url.toString().contains(":reply") }
            request.method shouldBe HttpMethod.Post
            request.url.toString() shouldContain "reviews/gp:AOqpTOFakeReviewIdentifier1:reply"
            val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            sent["replyText"]?.jsonPrimitive?.content?.length shouldBe 350
        }

        test("403 se mapuje na AUTH a validace poradí, co s tím") {
            val engine =
                RecordingEngine {
                    respond(fixture("error-permission-denied.json"), HttpStatusCode.Forbidden, jsonHeaders)
                }
            val connector = GooglePlayConnector(engine.client())

            val error = shouldThrow<StoreConnectorException> { connector.fetchReviews(context) }
            error.kind shouldBe StoreErrorKind.AUTH
            error.isRetryable shouldBe false

            val outcome = connector.validate(context)
            outcome.valid shouldBe false
            outcome.message.shouldNotBeNull() shouldContain "Play Console"
        }

        test("404 znamená špatný balíček, 429 a 5xx se dají zkusit znovu") {
            fun connector(status: HttpStatusCode) =
                GooglePlayConnector(RecordingEngine { respond("""{"error":{"message":"nope"}}""", status, jsonHeaders) }.client())

            shouldThrow<StoreConnectorException> {
                connector(HttpStatusCode.NotFound).fetchReviews(context)
            }.kind shouldBe StoreErrorKind.NOT_FOUND

            val rateLimited =
                shouldThrow<StoreConnectorException> { connector(HttpStatusCode.TooManyRequests).fetchReviews(context) }
            rateLimited.kind shouldBe StoreErrorKind.RATE_LIMITED
            rateLimited.isRetryable shouldBe true

            val transient =
                shouldThrow<StoreConnectorException> { connector(HttpStatusCode.BadGateway).fetchReviews(context) }
            transient.isRetryable shouldBe true
        }

        test("validace projde, když store odpoví") {
            val engine = RecordingEngine { respond(fixture("reviews-page2.json"), headers = jsonHeaders) }

            GooglePlayConnector(engine.client()).validate(context).valid shouldBe true
        }
    })

private fun base64UrlDecode(value: String): ByteArray =
    java.util.Base64
        .getUrlDecoder()
        .decode(value)

private fun verifySignature(assertion: String): Boolean {
    val parts = assertion.split(".")
    return java.security.Signature
        .getInstance("SHA256withRSA")
        .run {
            initVerify(TestServiceAccount.publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
            verify(base64UrlDecode(parts[2]))
        }
}
