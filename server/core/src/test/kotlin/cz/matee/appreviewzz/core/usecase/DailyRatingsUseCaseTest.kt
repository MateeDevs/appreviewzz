package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.ObservedRatings
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.RatingSource
import cz.matee.appreviewzz.core.port.NewRatingSnapshot
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-08-21T06:30:00Z")
private val TODAY = LocalDate(2026, 8, 21)
private val YESTERDAY = LocalDate(2026, 8, 20)

private val fixedClock =
    object : Clock {
        override fun now(): Instant = NOW
    }

private fun ratings(
    platform: Platform,
    territory: String = ObservedRatings.GLOBAL,
    average: Double? = 4.5,
    total: Long? = 1000,
    histogram: Map<Int, Long> = emptyMap(),
    source: RatingSource = RatingSource.ITUNES_LOOKUP,
    asOf: LocalDate? = null,
) = ObservedRatings(platform, territory, average, total, histogram, source, asOf)

class DailyRatingsUseCaseTest :
    FunSpec({
        val orgId = OrganizationId(Uuid.random())

        class Fixture(
            sources: List<FakeRatingsSource>,
            gpPackage: String? = null,
            ascAppId: String? = "id1490577875",
            deliverRatings: Boolean = true,
            enabled: Boolean = true,
        ) {
            val apps = FakeAppRepository()
            val app = apps.put(Ingest.app(orgId, gpPackageName = gpPackage, ascAppId = ascAppId, enabled = enabled))
            val slack = FakeNotificationChannel(ChannelType.SLACK)
            val channel =
                Delivery.channel(orgId, app.id).copy(deliverReviews = false).let { base ->
                    base.copy(
                        locale = MessageLocale.CS,
                        deliverRatings = deliverRatings,
                    )
                }
            val channels = FakeChannelRepository(mutableListOf(channel))
            val snapshots = FakeRatingSnapshotRepository()
            val digests = FakeRatingsDigestRepository()

            val useCase =
                DailyRatingsUseCase(
                    apps = apps,
                    channels = channels,
                    credentials = FakeCredentialRepository(),
                    snapshots = snapshots,
                    digests = digests,
                    secrets = secretResolver(),
                    ratingsSources = sources,
                    notificationChannels = listOf(slack),
                    clock = fixedClock,
                )
        }

        test("první běh uloží snapshot, ale nic nepředstírá o změně") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            result = listOf(ratings(Platform.IOS, territory = "CZ", average = 4.5, total = 1000)),
                        ),
                    ),
                )

            val report = fixture.useCase.run(orgId, fixture.app.id)

            val part = report.platforms.single()
            part.platform shouldBe Platform.IOS
            part.average!! shouldBe (4.5 plusOrMinus 0.001)
            // Dnešní n8n bez včerejšího řádku prohlásí celý kumulativní histogram za „nová
            // hodnocení" a vyrobí čísla v řádu tisíců. Tady se prostě řekne, že není s čím srovnat.
            part.isFirstRun shouldBe true
            part.delta.shouldBeNull()
            part.newRatings.shouldBeEmpty()
            report.deliveries.single().sent shouldBe true
        }

        test("delta se počítá proti poslednímu staršímu snapshotu, ne proti celkovému průměru") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            result = listOf(ratings(Platform.IOS, average = 4.62, total = 1050)),
                        ),
                    ),
                )
            fixture.snapshots.seed(
                orgId,
                NewRatingSnapshot(
                    appId = fixture.app.id,
                    platform = Platform.IOS,
                    date = YESTERDAY,
                    average = 4.5,
                    totalCount = 1000,
                    source = RatingSource.ITUNES_LOOKUP,
                ),
                NOW,
            )

            val part =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .platforms
                    .single()

            part.isFirstRun shouldBe false
            part.delta!! shouldBe (0.12 plusOrMinus 0.001)
            part.previousAsOf shouldBe YESTERDAY
            // Bez histogramu se přírůstek dopočítá z počtů — jinak by nebylo co ukázat.
            part.newTotal shouldBe 50
        }

        test("vynechaný den nevadí: srovnává se s posledním, co v databázi je") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(Platform.IOS, priority = 100, result = listOf(ratings(Platform.IOS, average = 4.4))),
                    ),
                )
            fixture.snapshots.seed(
                orgId,
                NewRatingSnapshot(
                    appId = fixture.app.id,
                    platform = Platform.IOS,
                    date = LocalDate(2026, 8, 10),
                    average = 4.2,
                    totalCount = 900,
                    source = RatingSource.ITUNES_LOOKUP,
                ),
                NOW,
            )

            // Dnešní pipeline hledá výhradně řádek z včerejška; když job den neproběhl, spadne.
            fixture.useCase
                .run(orgId, fixture.app.id)
                .platforms
                .single()
                .previousAsOf shouldBe LocalDate(2026, 8, 10)
        }

        test("zdroje se slučují: průměr z oficiálních dat, rozpad po hvězdách ze scrapu") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            result = listOf(ratings(Platform.IOS, territory = "CZ", average = 4.5, total = 1000)),
                        ),
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 50,
                            result =
                                listOf(
                                    ratings(
                                        Platform.IOS,
                                        territory = "CZ",
                                        average = null,
                                        total = null,
                                        histogram = mapOf(1 to 50L, 2 to 50L, 3 to 100L, 4 to 300L, 5 to 500L),
                                        source = RatingSource.ASC_LISTING,
                                    ),
                                ),
                        ),
                    ),
                )

            val part =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .platforms
                    .single()

            part.average!! shouldBe (4.5 plusOrMinus 0.001)
            fixture.snapshots
                .all()
                .single { it.territory == "CZ" }
                .histogram shouldBe mapOf(1 to 50L, 2 to 50L, 3 to 100L, 4 to 300L, 5 to 500L)
        }

        test("iOS se ukládá po storefrontech i jako globální součet") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            result =
                                listOf(
                                    ratings(Platform.IOS, territory = "CZ", average = 4.0, total = 100),
                                    ratings(Platform.IOS, territory = "US", average = 5.0, total = 900),
                                ),
                        ),
                    ),
                )

            val part =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .platforms
                    .single()

            // Vážený průměr, ne průměr průměrů: jinak by Česko vážilo stejně jako Spojené státy.
            part.average!! shouldBe (4.9 plusOrMinus 0.001)
            part.totalCount shouldBe 1000
            fixture.snapshots
                .all()
                .map { it.territory }
                .sorted() shouldContainExactly listOf("CZ", "GLOBAL", "US")
        }

        test("přírůstek po hvězdách nejde do minusu, i když store hodnocení smaže") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            result =
                                listOf(
                                    ratings(
                                        Platform.IOS,
                                        average = 4.5,
                                        total = 1000,
                                        histogram = mapOf(1 to 40L, 2 to 60L, 3 to 100L, 4 to 300L, 5 to 500L),
                                    ),
                                ),
                        ),
                    ),
                )
            fixture.snapshots.seed(
                orgId,
                NewRatingSnapshot(
                    appId = fixture.app.id,
                    platform = Platform.IOS,
                    date = YESTERDAY,
                    average = 4.4,
                    totalCount = 980,
                    // Včera bylo jedniček víc — někdo je mezitím smazal.
                    histogram = mapOf(1 to 50L, 2 to 50L, 3 to 100L, 4 to 290L, 5 to 490L),
                    source = RatingSource.ITUNES_LOOKUP,
                ),
                NOW,
            )

            val part =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .platforms
                    .single()

            part.newRatings shouldBe mapOf(1 to 0L, 2 to 10L, 3 to 0L, 4 to 10L, 5 to 10L)
            part.newTotal shouldBe 30
        }

        test("snapshot nese datum zdroje, ne dnešek") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.ANDROID,
                            priority = 100,
                            result = listOf(ratings(Platform.ANDROID, source = RatingSource.GP_CSV, asOf = LocalDate(2026, 8, 19))),
                        ),
                    ),
                    gpPackage = "cz.matee.islegrow",
                    ascAppId = null,
                )

            val part =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .platforms
                    .single()

            // Play export je den až dva pozadu; dnešní řešení to zamlčí a tváří se jako dnešek.
            part.asOf shouldBe LocalDate(2026, 8, 19)
            fixture.snapshots
                .all()
                .single()
                .date shouldBe LocalDate(2026, 8, 19)
        }

        test("druhý běh téhož dne přehled znovu neposílá") {
            val fixture =
                Fixture(
                    listOf(FakeRatingsSource(Platform.IOS, priority = 100, result = listOf(ratings(Platform.IOS)))),
                )

            fixture.useCase
                .run(orgId, fixture.app.id)
                .deliveries
                .single()
                .sent shouldBe true
            val second =
                fixture.useCase
                    .run(orgId, fixture.app.id)
                    .deliveries
                    .single()

            second.sent shouldBe false
            second.alreadySent shouldBe true
            fixture.slack.digests.size shouldBe 1
        }

        test("kanál bez přehledů dostane recenze, ne digest") {
            val fixture =
                Fixture(
                    listOf(FakeRatingsSource(Platform.IOS, priority = 100, result = listOf(ratings(Platform.IOS)))),
                    deliverRatings = false,
                )

            val report = fixture.useCase.run(orgId, fixture.app.id)

            report.skipped shouldBe RatingsSkipReason.NO_CHANNEL
            // Snapshot se přesto uloží — historie se nemá vázat na to, jestli má kdo číst.
            fixture.snapshots.all().size shouldBe 1
        }

        test("selhání jedné platformy nezruší druhou") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.ANDROID,
                            priority = 100,
                            failWith = StoreConnectorException(StoreErrorKind.AUTH, "chybí právo na bucket"),
                        ),
                        FakeRatingsSource(Platform.IOS, priority = 100, result = listOf(ratings(Platform.IOS))),
                    ),
                    gpPackage = "cz.matee.islegrow",
                )

            val report = fixture.useCase.run(orgId, fixture.app.id)

            report.platforms.map { it.platform } shouldContainExactly listOf(Platform.IOS)
            report.failures.single().platform shouldBe Platform.ANDROID
            report.isRetryable shouldBe false
            report.deliveries.single().sent shouldBe true
        }

        test("výpadek storu se dá zkusit znovu") {
            val fixture =
                Fixture(
                    listOf(
                        FakeRatingsSource(
                            Platform.IOS,
                            priority = 100,
                            failWith = StoreConnectorException(StoreErrorKind.TRANSIENT, "iTunes je nedostupné"),
                        ),
                    ),
                )

            val report = fixture.useCase.run(orgId, fixture.app.id)

            report.skipped shouldBe RatingsSkipReason.NO_DATA
            report.isRetryable shouldBe true
        }

        test("vypnutá aplikace se nesbírá") {
            val fixture =
                Fixture(
                    listOf(FakeRatingsSource(Platform.IOS, priority = 100, result = listOf(ratings(Platform.IOS)))),
                    enabled = false,
                )

            fixture.useCase.run(orgId, fixture.app.id).skipped shouldBe RatingsSkipReason.APP_DISABLED
        }
    })
