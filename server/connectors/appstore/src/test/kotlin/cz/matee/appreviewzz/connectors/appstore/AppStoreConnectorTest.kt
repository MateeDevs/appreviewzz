package cz.matee.appreviewzz.connectors.appstore

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

private const val APP_ID = "1234567890"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class AppStoreConnectorTest :
    FunSpec({
        val context = StoreContext(appIdentifier = APP_ID, credential = TestAscKey.teamKey())

        test("stáhne recenze včetně odpovědí vývojáře a projde stránkování") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.parameters["cursor"] == "DALEJ") {
                        respond(fixture("customer-reviews-page2.json"), headers = jsonHeaders)
                    } else {
                        respond(fixture("customer-reviews-page1.json"), headers = jsonHeaders)
                    }
                }
            val connector = AppStoreConnector(engine.client())

            val reviews = connector.fetchReviews(context)

            reviews shouldHaveSize 3
            val first = reviews.first()
            first.platform shouldBe Platform.IOS
            first.storeReviewId shouldBe "00000000-1111-2222-3333-444444444444"
            first.starRating shouldBe 2
            first.title shouldBe "Pády po aktualizaci"
            first.authorName shouldBe "Honza"
            first.territory shouldBe "CZE"
            first.submittedAt shouldBe Instant.parse("2026-08-19T14:12:44Z")
            first.developerResponseBody shouldBe null

            // Tohle je ten bug dnešního n8n: bez include=response se odpověď nikdy nenačte
            // a „už odpovězeno" u iOS proto nefunguje.
            val answered = reviews[1]
            answered.developerResponseBody shouldBe "Díky! Rádi to slyšíme."
            answered.developerResponseAt shouldBe Instant.parse("2026-08-19T13:00:00Z")

            val firstRequest = engine.requests.first()
            firstRequest.url.parameters["include"] shouldBe "response"
            firstRequest.url.parameters["sort"] shouldBe "-createdDate"
        }

        test("token je ES256 podepsaný klíčem, s kid a aud podle Applu") {
            val engine = RecordingEngine { respond(fixture("customer-reviews-page2.json"), headers = jsonHeaders) }
            AppStoreConnector(engine.client()).fetchReviews(context)

            val authorization =
                requireNotNull(engine.requests.first().headers[HttpHeaders.Authorization]).removePrefix("Bearer ")
            val (header, claims) = authorization.split(".")
            val headerJson = Json.parseToJsonElement(String(base64UrlDecode(header))).jsonObject
            val claimsJson = Json.parseToJsonElement(String(base64UrlDecode(claims))).jsonObject

            headerJson["alg"]?.jsonPrimitive?.content shouldBe "ES256"
            headerJson["kid"]?.jsonPrimitive?.content shouldBe TestAscKey.KEY_ID
            claimsJson["iss"]?.jsonPrimitive?.content shouldBe TestAscKey.ISSUER_ID
            claimsJson["aud"]?.jsonPrimitive?.content shouldBe AscTokens.AUDIENCE
            verifySignature(authorization) shouldBe true

            // Apple povoluje nejvýš 20 minut.
            val lifetime =
                requireNotNull(claimsJson["exp"]).jsonPrimitive.content.toLong() -
                    requireNotNull(claimsJson["iat"]).jsonPrimitive.content.toLong()
            (lifetime <= 20 * 60) shouldBe true
        }

        test("individuální klíč posílá sub=user místo iss") {
            val engine = RecordingEngine { respond(fixture("customer-reviews-page2.json"), headers = jsonHeaders) }
            AppStoreConnector(engine.client())
                .fetchReviews(StoreContext(APP_ID, TestAscKey.individualKey()))

            val authorization =
                requireNotNull(engine.requests.first().headers[HttpHeaders.Authorization]).removePrefix("Bearer ")
            val claimsJson =
                Json.parseToJsonElement(String(base64UrlDecode(authorization.split(".")[1]))).jsonObject

            claimsJson["sub"]?.jsonPrimitive?.content shouldBe "user"
            claimsJson["iss"] shouldBe null
        }

        test("token se podepíše jednou a používá se z cache") {
            val engine = RecordingEngine { respond(fixture("customer-reviews-page2.json"), headers = jsonHeaders) }
            val connector = AppStoreConnector(engine.client())

            connector.fetchReviews(context)
            connector.fetchReviews(context)

            engine.requests
                .map { it.headers[HttpHeaders.Authorization] }
                .distinct() shouldHaveSize 1
        }

        test("odpověď se publikuje ve formátu JSON:API a váže se na recenzi") {
            val engine =
                RecordingEngine { request ->
                    if (request.method == HttpMethod.Post) {
                        respond(fixture("create-response.json"), HttpStatusCode.Created, jsonHeaders)
                    } else {
                        null
                    }
                }
            val connector = AppStoreConnector(engine.client())

            val published =
                connector.publishReply(context, "00000000-1111-2222-3333-444444444444", "Mrzí nás to, oprava je už v testování.")

            published.publishedAt shouldBe Instant.parse("2026-08-19T16:15:00Z")
            val request = engine.requests.first { it.method == HttpMethod.Post }
            request.url.encodedPath shouldContain "/v1/customerReviewResponses"
            val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            val data = requireNotNull(sent["data"]).jsonObject
            data["type"]?.jsonPrimitive?.content shouldBe "customerReviewResponses"
            requireNotNull(data["relationships"])
                .jsonObject["review"]
                ?.jsonObject
                ?.get("data")
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content shouldBe "00000000-1111-2222-3333-444444444444"
        }

        test("odpověď delší než limit Applu se ořízne") {
            val engine =
                RecordingEngine { request ->
                    if (request.method == HttpMethod.Post) {
                        respond(fixture("create-response.json"), HttpStatusCode.Created, jsonHeaders)
                    } else {
                        null
                    }
                }
            val connector = AppStoreConnector(engine.client())

            connector.publishReply(context, "recenze", "a".repeat(7_000))

            val sent =
                Json
                    .parseToJsonElement(
                        String(
                            engine.requests
                                .first { it.method == HttpMethod.Post }
                                .body
                                .toByteArray(),
                        ),
                    ).jsonObject
            requireNotNull(sent["data"])
                .jsonObject["attributes"]
                ?.jsonObject
                ?.get("responseBody")
                ?.jsonPrimitive
                ?.content
                ?.length shouldBe connector.replyMaxLength
        }

        test("403 se mapuje na AUTH a validace poradí, jakou roli klíč potřebuje") {
            val engine =
                RecordingEngine { respond(fixture("error-forbidden.json"), HttpStatusCode.Forbidden, jsonHeaders) }
            val connector = AppStoreConnector(engine.client())

            val error = shouldThrow<StoreConnectorException> { connector.fetchReviews(context) }
            error.kind shouldBe StoreErrorKind.AUTH
            error.message shouldContain "does not allow this request"

            val outcome = connector.validate(context)
            outcome.valid shouldBe false
            outcome.message.shouldNotBeNull() shouldContain "Customer Support"
        }

        test("404 je špatné App ID, 429 a 5xx se dají zkusit znovu") {
            fun connector(status: HttpStatusCode) =
                AppStoreConnector(RecordingEngine { respond("""{"errors":[]}""", status, jsonHeaders) }.client())

            shouldThrow<StoreConnectorException> {
                connector(HttpStatusCode.NotFound).fetchReviews(context)
            }.kind shouldBe StoreErrorKind.NOT_FOUND

            shouldThrow<StoreConnectorException> {
                connector(HttpStatusCode.TooManyRequests).fetchReviews(context)
            }.isRetryable shouldBe true

            shouldThrow<StoreConnectorException> {
                connector(HttpStatusCode.ServiceUnavailable).fetchReviews(context)
            }.isRetryable shouldBe true
        }
    })

private fun base64UrlDecode(value: String): ByteArray =
    java.util.Base64
        .getUrlDecoder()
        .decode(value)

private fun verifySignature(token: String): Boolean {
    val parts = token.split(".")
    return java.security.Signature
        .getInstance("SHA256withECDSAinP1363Format")
        .run {
            initVerify(TestAscKey.publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
            verify(base64UrlDecode(parts[2]))
        }
}
