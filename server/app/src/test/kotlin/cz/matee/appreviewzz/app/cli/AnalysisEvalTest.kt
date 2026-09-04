package cz.matee.appreviewzz.app.cli

import cz.matee.appreviewzz.core.model.Platform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun gold(
    id: String,
    topics: List<String>,
    topicsB: List<String>? = null,
    sentiment: String = "NEGATIVE",
    type: String = "BUG",
    urgency: String = "HIGH",
) = GoldReview(
    id = id,
    platform = Platform.ANDROID,
    stars = 2,
    body = "Po updatu to padá",
    topics = topics,
    topicsB = topicsB,
    sentiment = sentiment,
    type = type,
    urgency = urgency,
)

private fun predicted(
    id: String,
    topics: Set<String>,
    sentiment: String = "NEGATIVE",
    type: String = "BUG",
    urgency: String = "HIGH",
) = EvalPrediction(id = id, topics = topics, sentiment = sentiment, type = type, urgency = urgency)

/**
 * Metriky evaluace na fixture se známým výsledkem. Rozhoduje se podle nich o modelu,
 * takže špatně spočítané F1 by stálo buď peníze, nebo kvalitu — a nikdo by nevěděl proč.
 */
class AnalysisEvalTest :
    FunSpec({
        test("přesnost, úplnost a F1 sedí na ručně spočítaném příkladu") {
            val gold =
                listOf(
                    gold("1", listOf("crash")),
                    gold("2", listOf("crash", "update")),
                    gold("3", listOf("pricing")),
                )
            val predictions =
                listOf(
                    predicted("1", setOf("crash")),
                    predicted("2", setOf("crash")),
                    predicted("3", setOf("crash")),
                )

            val result = AnalysisEval.evaluate(gold, predictions)

            val crash = result.topics.single { it.key == "crash" }
            crash.truePositives shouldBe 2
            crash.falsePositives shouldBe 1
            crash.falseNegatives shouldBe 0
            crash.precision shouldBe (2.0 / 3 plusOrMinus 0.001)
            crash.recall shouldBe (1.0 plusOrMinus 0.001)
            crash.f1 shouldBe (0.8 plusOrMinus 0.001)

            // update a pricing model neuhodl vůbec: dvě falešně negativní.
            result.topics.single { it.key == "update" }.falseNegatives shouldBe 1
            result.topics.single { it.key == "pricing" }.falseNegatives shouldBe 1
            result.microF1 shouldBe (2 * 2.0 / (3 + 4) plusOrMinus 0.001)
        }

        test("přesnost sentimentu, typu a naléhavosti se počítá jen z anotovaných hodnot") {
            val result =
                AnalysisEval.evaluate(
                    listOf(
                        gold("1", listOf("crash"), sentiment = "NEGATIVE", type = "BUG", urgency = "HIGH"),
                        gold("2", listOf("crash"), sentiment = "MIXED", type = "COMPLAINT", urgency = "LOW"),
                    ),
                    listOf(
                        predicted("1", setOf("crash"), sentiment = "NEGATIVE", type = "BUG", urgency = "HIGH"),
                        predicted("2", setOf("crash"), sentiment = "NEGATIVE", type = "COMPLAINT", urgency = "HIGH"),
                    ),
                )

            result.sentimentAccuracy shouldBe (0.5 plusOrMinus 0.001)
            result.typeAccuracy shouldBe (1.0 plusOrMinus 0.001)
            result.urgencyAccuracy shouldBe (0.5 plusOrMinus 0.001)
        }

        test("recenze, kterou model nevrátil, se počítá jako chybějící, ne jako chyba") {
            val result =
                AnalysisEval.evaluate(
                    listOf(gold("1", listOf("crash")), gold("2", listOf("crash"))),
                    listOf(predicted("1", setOf("crash"))),
                )

            result.items shouldBe 1
            result.missing shouldBe 1
        }

        test("neanotovaná recenze se do metrik nepočítá") {
            val result =
                AnalysisEval.evaluate(
                    listOf(gold("1", listOf("crash")), gold("2", emptyList())),
                    listOf(predicted("1", setOf("crash")), predicted("2", setOf("pricing"))),
                )

            result.items shouldBe 1
            result.topics shouldHaveSize 1
        }

        test("úplná shoda anotátorů je κ = 1, bez druhého anotátora κ chybí") {
            val agreeing = listOf(gold("1", listOf("crash"), topicsB = listOf("crash")))
            AnalysisEval.kappa(agreeing).shouldNotBeNull() shouldBe (1.0 plusOrMinus 0.001)

            AnalysisEval.kappa(listOf(gold("1", listOf("crash")))).shouldBeNull()
        }

        test("neshoda anotátorů κ snižuje") {
            val disagreeing =
                listOf(
                    gold("1", listOf("crash"), topicsB = listOf("crash")),
                    gold("2", listOf("crash"), topicsB = listOf("pricing")),
                )

            val kappa = AnalysisEval.kappa(disagreeing).shouldNotBeNull()
            (kappa < 1.0) shouldBe true
        }

        test("stratifikace bere z každé přihrádky, ne prvních N z jedné") {
            val items = List(10) { "A$it" } + List(10) { "B$it" }

            val picked = AnalysisEval.stratify(items, limit = 6) { it.first().toString() }

            picked shouldHaveSize 6
            picked.count { it.startsWith("A") } shouldBe 3
            picked.count { it.startsWith("B") } shouldBe 3
        }

        test("kratší seznam než limit se vrátí celý") {
            AnalysisEval.stratify(listOf("A1", "B1"), limit = 10) { it.first().toString() } shouldHaveSize 2
        }

        test("řádky se čtou i zapisují jako JSONL") {
            val line = AnalysisEval.toJsonLine(gold("1", listOf("crash")))

            AnalysisEval.parse(sequenceOf(line, "", "  ")).single().topics shouldBe listOf("crash")
        }
    })
