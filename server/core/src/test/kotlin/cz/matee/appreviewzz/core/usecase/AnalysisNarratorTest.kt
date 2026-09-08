package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisNarrativeProvider
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.Narrative
import cz.matee.appreviewzz.core.port.NarrativeQuote
import cz.matee.appreviewzz.core.port.NarrativeResult
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

private val START = LocalDate(2026, 8, 31)
private val END = LocalDate(2026, 9, 6)
private const val CITED_REVIEW = "11111111-1111-1111-1111-111111111111"

/** Dvacet recenzí, 60 % spokojených, devět zmínek o pádech, Ø 3,50 ★. */
private val AGGREGATES =
    AnalysisAggregates.of(
        periodStart = START,
        periodEnd = END,
        current =
            AnalysisPeriod(
                reviews = 20,
                byPlatform = mapOf(Platform.ANDROID to 20),
                starSum = 70,
                sentiments = mapOf(OverallSentiment.POSITIVE to 12, OverallSentiment.NEGATIVE to 8),
                topics = listOf(TopicAggregate(Topic.CRASH.key, count = 9, negative = 9, positive = 0, starSum = 18)),
                versions = emptyList(),
            ),
        previous = AnalysisPeriod.EMPTY,
        replies = ReplyStats(total = 20, replied = 8, medianHours = 5.0, uplifted = 3),
        dataSince = null,
        locale = MessageLocale.CS,
    )

private fun narrator(result: NarrativeResult) = AnalysisNarrator(AnalysisNarrativeProvider { result })

/**
 * Shrnutí je jediné místo, kde do zprávy sahá model — a proto jediné, kde se ověřuje
 * každé slovo. Číslo, které v agregátech není, znamená zahodit celý odstavec: špatné
 * číslo v úvodu zpochybní i všechna správná pod ním.
 */
class AnalysisNarratorTest :
    FunSpec({
        suspend fun write(
            result: NarrativeResult,
            quotes: List<NarrativeQuote> = listOf(NarrativeQuote(CITED_REVIEW, "pořád to padá")),
        ) = narrator(result).write("IsleGrow", MessageLocale.CS, AGGREGATES, quotes)

        test("shrnutí jen s čísly z agregátů projde") {
            val summary =
                write(
                    NarrativeResult.Written(
                        Narrative("Za období přišlo 20 recenzí, 60 % spokojených. Pády se objevily 9×.", listOf(CITED_REVIEW)),
                    ),
                )

            summary.shouldNotBeNull() shouldContain "9×"
        }

        test("číslo, které v agregátech není, shrnutí zahodí") {
            // 37 % nikde není. Vypadá to jako drobnost, ale je to tvrzení o datech, které
            // klient nemá jak ověřit — a přesně tím se z rozboru stane dojem.
            val summary =
                write(
                    NarrativeResult.Written(
                        Narrative("Za období přišlo 20 recenzí a 37 % z nich si stěžovalo na pády.", emptyList()),
                    ),
                )

            summary shouldBe null
        }

        test("citace recenze, která nebyla mezi kandidáty, shrnutí zahodí") {
            val summary =
                write(
                    NarrativeResult.Written(
                        Narrative("Za období přišlo 20 recenzí.", listOf("22222222-2222-2222-2222-222222222222")),
                    ),
                )

            summary shouldBe null
        }

        test("zaokrouhlení podílu se toleruje") {
            // 60 % i 60 vyjde stejně; kdyby validace trvala na jediném tvaru, neprošlo by
            // skoro žádné shrnutí a vypínač by byl zbytečný — rovnou by se to nepoužívalo.
            val summary = write(NarrativeResult.Written(Narrative("Spokojených bylo 60 %, průměr 3.5 ★.", emptyList())))

            summary.shouldNotBeNull() shouldContain "60 %"
        }

        test("selhání providera znamená šablonu, ne chybu") {
            write(NarrativeResult.Failed("timeout")) shouldBe null
            write(NarrativeResult.Unavailable) shouldBe null
        }
    })
