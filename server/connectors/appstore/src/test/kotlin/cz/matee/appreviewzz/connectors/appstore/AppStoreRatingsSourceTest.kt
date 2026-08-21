package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

private const val APP_ID = "1490577875"

private fun context(
    identifier: String = "id$APP_ID",
    territories: List<String> = listOf("CZ", "US"),
) = RatingsContext(appIdentifier = identifier, territories = territories)

class AppStoreRatingsSourceTest :
    FunSpec({
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")

        context("iTunes lookup") {
            fun engine() =
                RecordingEngine { request ->
                    if (request.url.encodedPath.endsWith("/lookup")) {
                        respond(fixture("itunes-lookup.json"), headers = jsonHeaders)
                    } else {
                        null
                    }
                }

            test("vrací řádek na storefront, ať je vidět, z čeho se globální číslo skládá") {
                val engine = engine()

                val ratings = ITunesRatingsSource(engine.client()).fetchRatings(context())

                ratings.map { it.territory } shouldContainExactly listOf("CZ", "US")
                ratings.first().platform shouldBe Platform.IOS
                ratings.first().source shouldBe RatingSource.ITUNES_LOOKUP
                ratings.first().average!! shouldBe (4.51724 plusOrMinus 0.00001)
                ratings.first().totalCount shouldBe 2465
                // Histogram lookup nedává — od toho je listing.
                ratings.first().histogram.shouldBeEmpty()
            }

            test("ID se snese ve tvaru id123 i 123") {
                val engine = engine()

                ITunesRatingsSource(engine.client()).fetchRatings(context(identifier = APP_ID))

                engine.requests
                    .first()
                    .url.parameters["id"] shouldBe APP_ID
                engine.requests
                    .first()
                    .url.parameters["country"] shouldBe "cz"
            }

            test("nesmyslné ID se pozná dřív, než se někam zavolá") {
                val engine = engine()

                shouldThrow<StoreConnectorException> {
                    ITunesRatingsSource(engine.client()).fetchRatings(context(identifier = "com.example.app"))
                }.kind shouldBe StoreErrorKind.INVALID_REQUEST
                engine.requests.shouldBeEmpty()
            }

            test("storefront, kde appka není vydaná, se přeskočí") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.parameters["country"] == "cz") {
                            respond(fixture("itunes-lookup-empty.json"), headers = jsonHeaders)
                        } else {
                            respond(fixture("itunes-lookup.json"), headers = jsonHeaders)
                        }
                    }

                ITunesRatingsSource(engine.client()).fetchRatings(context()).map { it.territory } shouldContainExactly listOf("US")
            }

            test("jeden nefunkční storefront neshodí celý přehled, limit ano") {
                val flaky =
                    RecordingEngine { request ->
                        if (request.url.parameters["country"] == "cz") {
                            respondError(HttpStatusCode.BadGateway)
                        } else {
                            respond(fixture("itunes-lookup.json"), headers = jsonHeaders)
                        }
                    }
                val limited = RecordingEngine { respondError(HttpStatusCode.TooManyRequests) }

                ITunesRatingsSource(flaky.client()).fetchRatings(context()).map { it.territory } shouldContainExactly listOf("US")
                shouldThrow<StoreConnectorException> { ITunesRatingsSource(limited.client()).fetchRatings(context()) }
                    .kind shouldBe StoreErrorKind.RATE_LIMITED
            }

            test("výchozí seznam storefrontů je ten, na kterém stojí dnešní čísla") {
                // Kdyby se seznam změnil, průměry po migraci skočí a nikdo nebude vědět proč.
                ITunesRatingsSource.DEFAULT_TERRITORIES.size shouldBe 20
                ITunesRatingsSource.DEFAULT_TERRITORIES.first() shouldBe "US"
                ITunesRatingsSource.DEFAULT_TERRITORIES.last() shouldBe "SK"
            }
        }

        context("histogram z listingu") {
            test("rozpad po hvězdách se otočí do pořadí 1★→5★") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/app/id")) {
                            respond(fixture("app-store-listing.html"), headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                val ratings = AppStoreListingRatingsSource(engine.client()).fetchRatings(context(territories = listOf("CZ")))

                val single = ratings.single()
                single.source shouldBe RatingSource.ASC_LISTING
                // Apple posílá 5★→1★; obrácené pořadí by z pětky udělalo jedničku.
                single.histogram shouldBe mapOf(1 to 50L, 2 to 65L, 3 to 150L, 4 to 400L, 5 to 1800L)
                single.histogramAverage()!! shouldBe (4.5558 plusOrMinus 0.001)
                // Autoritativní průměr je z lookupu; odsud jde jen rozpad.
                single.average.shouldBeNull()
            }

            test("změněný layout znamená prázdno, ne pád celého přehledu") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/app/id")) {
                            respond("<html><body>Apple něco přestavěl</body></html>", headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                AppStoreListingRatingsSource(engine.client())
                    .fetchRatings(context(territories = listOf("CZ")))
                    .shouldBeEmpty()
            }
        }

        context("parser histogramu") {
            test("orientaci pozná podle průměru, který je vedle") {
                // Vzestupné pořadí s průměrem 1.5 — parser nesmí otáčet naslepo.
                val ascending =
                    """<script id="serialized-server-data" type="application/json">""" +
                        """[{"data":{"productRatings":{"items":[{"ratingAverage":1.6,"ratingCounts":[600,300,50,30,20]}]}}}]""" +
                        "</script>"

                AppStoreHistogramParser.parse(ascending) shouldBe mapOf(1 to 600L, 2 to 300L, 3 to 50L, 4 to 30L, 5 to 20L)
            }

            test("bez blobu sáhne po regexu v HTML") {
                val html = """<html><body>{"ratingCounts": [1800, 400, 150, 65, 50]}</body></html>"""

                AppStoreHistogramParser.parse(html) shouldBe mapOf(1 to 50L, 2 to 65L, 3 to 150L, 4 to 400L, 5 to 1800L)
            }

            test("samé nuly nejsou histogram, ale prázdná appka") {
                val html = """<html><body>{"ratingCounts": [0, 0, 0, 0, 0]}</body></html>"""

                AppStoreHistogramParser.parse(html).shouldBeNull()
            }
        }
    })
