package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReviewArchiveContext
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Instant

private const val PACKAGE = "cz.matee.testapp"
private const val BUCKET = "pubsite_prod_8713874562713668954"

private fun context(
    since: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    until: Instant = Instant.parse("2026-08-20T00:00:00Z"),
    bucket: String? = BUCKET,
) = ReviewArchiveContext(
    appIdentifier = PACKAGE,
    credential = TestServiceAccount.payload(),
    reportingBucket = bucket,
    since = since,
    until = until,
)

class PlayReviewsSourceTest :
    FunSpec({
        val csvHeaders = headersOf(HttpHeaders.ContentType, "text/csv")
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        fun engine() =
            RecordingEngine { request ->
                when {
                    request.url.toString().contains("alt=media") ->
                        respond(content = fixtureBytes("reviews-export.csv"), headers = csvHeaders)

                    request.url.encodedPath.endsWith("/o") ->
                        respond(fixture("gcs-listing.json"), headers = jsonHeaders)

                    else -> null
                }
            }

        test("recenze z exportu má ID z odkazu, jazyk, verzi i odpověď vývojáře") {
            val reviews = PlayReportingReviewSource(engine().client()).fetchArchive(context())

            reviews shouldHaveSizeOf 4
            val answered = reviews.first { it.storeReviewId == "csv:8c4103d5-db86-42aa-98b8-749e9169efa6" }
            answered.platform shouldBe Platform.ANDROID
            answered.starRating shouldBe 2
            answered.body shouldBe "pouze pro jeden druh zvířat"
            answered.locale shouldBe "cs"
            answered.developerResponseBody!!.startsWith("Dobrý den") shouldBe true
            answered.developerResponseAt shouldBe Instant.parse("2026-08-11T11:12:38Z")
            // Export jméno recenzenta ani trh nenese — historie je mít nebude.
            answered.authorName.shouldBeNull()
            answered.territory.shouldBeNull()
            // Prázdný sloupec verze nesmí skončit prázdným řetězcem.
            answered.appVersion.shouldBeNull()
        }

        test("text v uvozovkách unese čárku i konec řádku") {
            val reviews = PlayReportingReviewSource(engine().client()).fetchArchive(context())

            val edited = reviews.first { it.storeReviewId == "csv:647ff9fe-5dd2-4cca-a863-4d10bad953de" }
            edited.title shouldBe "Skvělé"
            edited.body shouldBe "První řádek, s čárkou.\nDruhý řádek."
            edited.appVersion shouldBe "1.6.1"
            // Čas poslední změny se nese jen tehdy, když se od odeslání liší.
            edited.storeUpdatedAt shouldBe Instant.parse("2026-08-14T05:00:00Z")
        }

        /**
         * Hodnocení bez textu jsou v exportu, ale **nemají odkaz**, tedy ani `reviewId`.
         * Přes API nepřijdou nikdy, takže tenhle řádek je jediná cesta, jak se k nim dostat.
         */
        test("hodnocení bez textu dostane ID z času odeslání") {
            val reviews = PlayReportingReviewSource(engine().client()).fetchArchive(context())

            val rating = reviews.first { it.body == null }
            rating.storeReviewId shouldBe "csv:1785635935000"
            rating.starRating shouldBe 4
            rating.submittedAt shouldBe Instant.parse("2026-08-02T01:58:55Z")
            rating.storeUpdatedAt.shouldBeNull()
        }

        test("stahuje jen měsíce, které období protíná") {
            val engine = engine()

            PlayReportingReviewSource(engine.client()).fetchArchive(
                context(since = Instant.parse("2026-06-15T00:00:00Z"), until = Instant.parse("2026-08-20T00:00:00Z")),
            )

            engine.requests.mapNotNull { it.url.parameters["prefix"] } shouldBe
                listOf(
                    "reviews/reviews_${PACKAGE}_202606",
                    "reviews/reviews_${PACKAGE}_202607",
                    "reviews/reviews_${PACKAGE}_202608",
                )
        }

        /** Soubor je měsíční, období skoro nikdy — okraje se musí ořezat po parsování. */
        test("recenze mimo období se zahodí") {
            val reviews =
                PlayReportingReviewSource(engine().client()).fetchArchive(
                    context(since = Instant.parse("2026-08-12T00:00:00Z"), until = Instant.parse("2026-08-20T00:00:00Z")),
                )

            reviews.map { it.starRating } shouldBe listOf(5, 4)
        }

        test("bez bucketu se nikam nesahá") {
            val engine = engine()

            PlayReportingReviewSource(engine.client()).fetchArchive(context(bucket = null)).shouldBeEmpty()

            engine.requests.shouldBeEmpty()
        }

        test("prázdný výpis není chyba, jen prázdná historie") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.encodedPath.endsWith("/o")) {
                        respond(fixture("gcs-listing-empty.json"), headers = jsonHeaders)
                    } else {
                        null
                    }
                }

            PlayReportingReviewSource(engine.client()).fetchArchive(context()).shouldBeEmpty()
        }

        test("odepřený přístup k bucketu chce člověka, ne retry") {
            val engine =
                RecordingEngine { request ->
                    if (request.url.encodedPath.endsWith("/o")) {
                        respondError(HttpStatusCode.Forbidden)
                    } else {
                        null
                    }
                }

            val error =
                shouldThrow<StoreConnectorException> {
                    PlayReportingReviewSource(engine.client()).fetchArchive(context())
                }
            error.kind shouldBe StoreErrorKind.AUTH
            error.isRetryable shouldBe false
        }
    })

private infix fun <T> List<T>.shouldHaveSizeOf(expected: Int) {
    size shouldBe expected
}
