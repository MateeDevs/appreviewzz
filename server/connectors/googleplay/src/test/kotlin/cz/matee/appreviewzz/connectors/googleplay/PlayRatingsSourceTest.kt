package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.RatingsContext
import cz.matee.appreviewzz.core.port.ReportingBucketStatus
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant

private const val PACKAGE = "com.stonecutters.islegrow"
private const val BUCKET = "pubsite_prod_8713874562713668954"

private val NOW = Instant.parse("2026-08-21T06:30:00Z")
private val fixedClock =
    object : Clock {
        override fun now(): Instant = NOW
    }

private fun context(
    bucket: String? = BUCKET,
    withCredential: Boolean = true,
) = RatingsContext(
    appIdentifier = PACKAGE,
    credential = if (withCredential) TestServiceAccount.payload() else null,
    reportingBucket = bucket,
)

class PlayRatingsSourceTest :
    FunSpec({
        val csvHeaders = headersOf(HttpHeaders.ContentType, "text/csv")
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")

        context("oficiální reporting z Play Console") {
            fun engine() =
                RecordingEngine { request ->
                    when {
                        request.url.toString().contains("/o?") || request.url.encodedPath.endsWith("/o") ->
                            respond(fixture("gcs-listing.json"), headers = jsonHeaders)

                        request.url.toString().contains("alt=media") ->
                            respond(content = fixtureBytes("ratings-overview.csv"), headers = csvHeaders)

                        else -> null
                    }
                }

            test("bere nejnovější den z exportu a nese jeho datum") {
                val engine = engine()

                val ratings =
                    PlayReportingRatingsSource(engine.client(), clock = fixedClock).fetchRatings(context()).single()

                ratings.platform shouldBe Platform.ANDROID
                ratings.territory shouldBe ObservedRatings.GLOBAL
                ratings.source shouldBe RatingSource.GP_CSV
                ratings.average!! shouldBe (4.3210 plusOrMinus 0.0001)
                // Export je den až dva pozadu — datum se musí nést dál, jinak se předevčerejší
                // průměr v kartě tváří jako dnešní (přesně to dělá dnešní n8n).
                ratings.asOf shouldBe LocalDate(2026, 8, 19)
                ratings.histogram.shouldBeEmpty()
            }

            test("stahuje aktuální i předchozí měsíc, ať přelom nic nerozbije") {
                val engine = engine()

                PlayReportingRatingsSource(engine.client(), clock = fixedClock).fetchRatings(context())

                val prefixes = engine.requests.mapNotNull { it.url.parameters["prefix"] }
                prefixes shouldBe
                    listOf(
                        "stats/ratings/ratings_${PACKAGE}_202607_overview",
                        "stats/ratings/ratings_${PACKAGE}_202608_overview",
                    )
            }

            test("bez bucketu se zdroj tiše přeskočí, ať může nastoupit scrape") {
                val engine = engine()

                PlayReportingRatingsSource(engine.client(), clock = fixedClock)
                    .fetchRatings(context(bucket = null))
                    .shouldBeEmpty()
                PlayReportingRatingsSource(engine.client(), clock = fixedClock)
                    .fetchRatings(context(withCredential = false))
                    .shouldBeEmpty()
                engine.requests.shouldBeEmpty()
            }

            test("prázdný bucket na začátku měsíce není chyba") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) {
                            respond(fixture("gcs-listing-empty.json"), headers = jsonHeaders)
                        } else {
                            null
                        }
                    }

                PlayReportingRatingsSource(engine.client(), clock = fixedClock).fetchRatings(context()).shouldBeEmpty()
            }

            test("chybějící právo na bucket řekne, co s tím") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) respondError(HttpStatusCode.Forbidden) else null
                    }

                val error =
                    shouldThrow<StoreConnectorException> {
                        PlayReportingRatingsSource(engine.client(), clock = fixedClock).fetchRatings(context())
                    }

                error.kind shouldBe StoreErrorKind.AUTH
                error.message!! shouldContain "Storage Object Viewer"
            }

            test("gs:// prefix i koncové lomítko v bucketu se snesou") {
                val engine = engine()

                PlayReportingRatingsSource(engine.client(), clock = fixedClock)
                    .fetchRatings(context(bucket = "gs://$BUCKET/"))
                    .single()
                    .average!! shouldBe (4.3210 plusOrMinus 0.0001)
            }
        }

        context("zkouška bucketu při onboardingu") {
            suspend fun probe(engine: RecordingEngine) =
                PlayReportingRatingsSource(engine.client(), clock = fixedClock)
                    .checkAccess(BUCKET, PACKAGE, TestServiceAccount.payload())

            test("export pro appku v bucketu leží — hotovo") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) respond(fixture("gcs-listing.json"), headers = jsonHeaders) else null
                    }

                val outcome = probe(engine)

                outcome.status shouldBe ReportingBucketStatus.OK
                outcome.worthSaving shouldBe true
                // Ptáme se rovnou na prefix téhle appky; obecný výpis stojí druhé volání navíc.
                engine.requests.mapNotNull { it.url.parameters["prefix"] } shouldBe listOf("stats/ratings/ratings_${PACKAGE}_")
            }

            test("cizí bucket pozná podle toho, že exporty tam jsou, ale ne pro tuhle appku") {
                val engine =
                    RecordingEngine { request ->
                        when (request.url.parameters["prefix"]) {
                            "stats/ratings/ratings_${PACKAGE}_" -> respond(fixture("gcs-listing-empty.json"), headers = jsonHeaders)
                            "stats/ratings/" -> respond(fixture("gcs-listing.json"), headers = jsonHeaders)
                            else -> null
                        }
                    }

                val outcome = probe(engine)

                outcome.status shouldBe ReportingBucketStatus.NO_EXPORT
                // Hodnotu má smysl uložit i tak: export může přibýt, tohle je varování, ne stopka.
                outcome.worthSaving shouldBe true
                outcome.message shouldContain "jinému vývojářskému účtu"
            }

            test("prázdný bucket mluví o čekání na export, ne o cizím účtu") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) {
                            respond(fixture("gcs-listing-empty.json"), headers = jsonHeaders)
                        } else {
                            null
                        }
                    }

                val outcome = probe(engine)

                outcome.status shouldBe ReportingBucketStatus.NO_EXPORT
                outcome.message shouldContain "jednou denně"
            }

            test("chybějící role řekne, kterému účtu ji přidat") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) respondError(HttpStatusCode.Forbidden) else null
                    }

                val outcome = probe(engine)

                outcome.status shouldBe ReportingBucketStatus.DENIED
                outcome.worthSaving shouldBe false
                outcome.message shouldContain "Storage Object Viewer"
                outcome.message shouldContain TestServiceAccount.CLIENT_EMAIL
            }

            test("neexistující bucket je překlep, ne chybějící právo") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) respondError(HttpStatusCode.NotFound) else null
                    }

                probe(engine).status shouldBe ReportingBucketStatus.MISSING
            }

            test("výpadek Cloud Storage o nastavení nic neříká") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.endsWith("/o")) respondError(HttpStatusCode.ServiceUnavailable) else null
                    }

                probe(engine).status shouldBe ReportingBucketStatus.UNAVAILABLE
            }
        }

        context("veřejný listing jako fallback") {
            test("dá průměr, počet i rozpad po hvězdách") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond(fixture("play-listing.html"), headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                val ratings = PlayStoreScrapeRatingsSource(engine.client()).fetchRatings(context()).single()

                ratings.source shouldBe RatingSource.GP_SCRAPE
                ratings.average!! shouldBe (4.312 plusOrMinus 0.001)
                ratings.totalCount shouldBe 18422
                // Histogram je to jediné, co oficiální overview CSV nedává.
                ratings.histogram shouldBe mapOf(1 to 600L, 2 to 622L, 3 to 1200L, 4 to 3100L, 5 to 12900L)
            }

            test("dotaz je vždy stejný jazyk a země, aby se graf neměnil podle serveru") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond(fixture("play-listing.html"), headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                PlayStoreScrapeRatingsSource(engine.client()).fetchRatings(context())

                val request = engine.requests.single()
                request.url.parameters["id"] shouldBe PACKAGE
                request.url.parameters["hl"] shouldBe "en"
                request.url.parameters["gl"] shouldBe "US"
            }

            test("umí i starší tvar popisků, který Google používal dřív") {
                // Google popisek už jednou přeházel („5 stars 12,345" → „12,345 reviews for star
                // rating 5"). Změna tvaru nesmí znamenat tiše chybějící rozpad.
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond(fixture("play-listing-legacy.html"), headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                PlayStoreScrapeRatingsSource(engine.client()).fetchRatings(context()).single().histogram shouldBe
                    mapOf(1 to 600L, 2 to 622L, 3 to 1200L, 4 to 3100L, 5 to 12900L)
            }

            test("neexistující aplikace je chyba pro člověka, ne k opakování") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respondError(HttpStatusCode.NotFound)
                        } else {
                            null
                        }
                    }

                shouldThrow<StoreConnectorException> { PlayStoreScrapeRatingsSource(engine.client()).fetchRatings(context()) }
                    .kind shouldBe StoreErrorKind.NOT_FOUND
            }

            test("jméno appky se přečte ze stejné stránky jako čísla") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond(fixture("play-listing.html"), headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                PlayStoreListingLookup(engine.client()).fetchName(PACKAGE) shouldBe "IsleGrow"
            }

            test("bez jména v listingu se vrátí prázdno — klient si název napíše sám") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond("<html><body>Google něco přestavěl</body></html>", headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                PlayStoreListingLookup(engine.client()).fetchName(PACKAGE).shouldBeNull()
            }

            test("změněný layout znamená prázdno, ne pád") {
                val engine =
                    RecordingEngine { request ->
                        if (request.url.encodedPath.contains("/store/apps/details")) {
                            respond("<html><body>Google něco přestavěl</body></html>", headers = htmlHeaders)
                        } else {
                            null
                        }
                    }

                PlayStoreScrapeRatingsSource(engine.client()).fetchRatings(context()).shouldBeEmpty()
            }
        }

        context("parsování exportu") {
            test("UTF-16LE s BOM se přečte i s desetinnými čísly") {
                val rows = PlayOverviewCsv.parse(fixtureBytes("ratings-overview.csv"))

                rows.size shouldBe 4
                rows.first().date shouldBe LocalDate(2026, 8, 1)
                rows.first().dailyAverage!! shouldBe (4.5 plusOrMinus 0.001)
                // Den bez nových hodnocení má prázdný sloupec — nula by lhala.
                rows[1].dailyAverage.shouldBeNull()
                rows[1].totalAverage!! shouldBe (4.3125 plusOrMinus 0.0001)
            }

            test("prázdný soubor nevrátí nic a nespadne") {
                PlayOverviewCsv.parse(ByteArray(0)).shouldBeEmpty()
                PlayOverviewCsv.parse("Date,Package Name\n".toByteArray()).shouldBeEmpty()
            }
        }
    })
