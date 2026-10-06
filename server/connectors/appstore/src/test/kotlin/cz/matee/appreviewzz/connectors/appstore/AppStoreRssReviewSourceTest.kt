package cz.matee.appreviewzz.connectors.appstore

import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Instant

/** RSS feed je jediný veřejný zdroj recenzí Applu; tvar odpovědi má tři podoby a všechny se musí přečíst. */
class AppStoreRssReviewSourceTest :
    StringSpec({
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")

        "přečte pole recenzí, přeskočí řádek bez hvězd a sloučí storefronty" {
            val engine =
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.startsWith("/cz/rss/customerreviews/page=1/") ->
                            respond(fixture("rss-reviews.json"), HttpStatusCode.OK, jsonHeaders)
                        request.url.encodedPath.startsWith("/sk/rss/customerreviews/page=1/") ->
                            respond(fixture("rss-reviews-single.json"), HttpStatusCode.OK, jsonHeaders)
                        // Storefront, kde appka není, vrací 4xx — to je prázdná země, ne chyba.
                        else -> respond("", HttpStatusCode.NotFound, jsonHeaders)
                    }
                }
            val source = AppStoreRssReviewSource(engine.client(), defaultTerritories = listOf("CZ", "SK"), pages = 1)

            val reviews = source.fetchPublicReviews("1477172642")

            reviews shouldHaveSize 3
            val first = reviews.first { it.storeReviewId == "rss:11111" }
            first.starRating shouldBe 2
            first.authorName shouldBe "Jana N."
            first.title shouldBe "Padá"
            first.body shouldBe "Po aktualizaci padá při startu."
            first.appVersion shouldBe "3.7.0"
            first.territory shouldBe "CZ"
            first.submittedAt shouldBe Instant.parse("2026-10-01T17:15:00Z")
            reviews.first { it.storeReviewId == "rss:44444" }.territory shouldBe "SK"
            engine.requests shouldHaveSize 2
        }

        "limit tempa se hlásí jako chyba, kterou má smysl zopakovat" {
            val engine = RecordingEngine { respond("", HttpStatusCode.TooManyRequests, jsonHeaders) }
            val source = AppStoreRssReviewSource(engine.client(), defaultTerritories = listOf("CZ"), pages = 1)

            shouldThrow<StoreConnectorException> { source.fetchPublicReviews("1477172642") }.kind shouldBe
                StoreErrorKind.RATE_LIMITED
        }
    })
