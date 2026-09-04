package cz.matee.appreviewzz.app.cli

import cz.matee.appreviewzz.core.model.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Jedna položka zlatého setu. Prázdná pole vyplní člověk — proto je všechno nepovinné
 * a soubor jde exportovat i anotovat po částech.
 *
 * `topicsB` je druhý anotátor. Bez něj se dá spočítat, jak si vede model; teprve s ním se
 * dá spočítat, jak si vedou **lidé mezi sebou** — a to je jediné poctivé měřítko, proti
 * kterému má smysl model poměřovat.
 */
@Serializable
data class GoldReview(
    val id: String,
    val platform: Platform,
    val stars: Int,
    val title: String? = null,
    val body: String? = null,
    val version: String? = null,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    @SerialName("topics_b") val topicsB: List<String>? = null,
    val sentiment: String? = null,
    val type: String? = null,
    val urgency: String? = null,
) {
    /** Anotovaná položka: bez témat není co porovnávat. */
    val annotated: Boolean get() = topics.isNotEmpty()
}

/** Přesnost, úplnost a F1 pro jedno téma. */
data class TopicScore(
    val key: String,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
) {
    val precision: Double get() = ratio(truePositives, truePositives + falsePositives)

    val recall: Double get() = ratio(truePositives, truePositives + falseNegatives)

    val f1: Double
        get() {
            val sum = precision + recall
            return if (sum == 0.0) 0.0 else 2 * precision * recall / sum
        }

    val support: Int get() = truePositives + falseNegatives

    private fun ratio(
        part: Int,
        whole: Int,
    ): Double = if (whole == 0) 0.0 else part.toDouble() / whole
}

/**
 * Výsledek evaluace. Mikro-F1 přes všechna témata, ne makro: zajímá nás, kolik štítků
 * celkem sedí, ne jak si model vede na tématu, které se objevilo třikrát.
 */
data class EvalResult(
    val items: Int,
    val topics: List<TopicScore>,
    val sentimentAccuracy: Double,
    val typeAccuracy: Double,
    val urgencyAccuracy: Double,
    /** Cohenovo κ mezi dvěma anotátory; `null`, když druhý anotátor v souboru není. */
    val annotatorKappa: Double? = null,
    /** Položky, které model v odpovědi vůbec nevrátil. */
    val missing: Int = 0,
) {
    val microF1: Double
        get() {
            val tp = topics.sumOf { it.truePositives }
            val fp = topics.sumOf { it.falsePositives }
            val fn = topics.sumOf { it.falseNegatives }
            val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            return if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        }
}

/** Co model vrátil pro jednu recenzi — vstup do porovnání, nezávislý na provideru. */
data class EvalPrediction(
    val id: String,
    val topics: Set<String>,
    val sentiment: String?,
    val type: String?,
    val urgency: String?,
)

/**
 * Metriky evaluace. Čistá funkce schválně: jsou to čísla, na kterých stojí rozhodnutí
 * o modelu, takže musí jít ověřit na fixture se známým výsledkem.
 */
object AnalysisEval {
    fun evaluate(
        gold: List<GoldReview>,
        predictions: List<EvalPrediction>,
    ): EvalResult {
        val annotated = gold.filter { it.annotated }
        val byId = predictions.associateBy { it.id }
        val matched = annotated.mapNotNull { item -> byId[item.id]?.let { item to it } }

        val keys = (annotated.flatMap { it.topics } + matched.flatMap { it.second.topics }).toSortedSet()
        val topics =
            keys.map { key ->
                var truePositives = 0
                var falsePositives = 0
                var falseNegatives = 0
                matched.forEach { (expected, actual) ->
                    val inGold = key in expected.topics
                    val inPrediction = key in actual.topics
                    when {
                        inGold && inPrediction -> truePositives++
                        inPrediction -> falsePositives++
                        inGold -> falseNegatives++
                    }
                }
                TopicScore(key, truePositives, falsePositives, falseNegatives)
            }

        return EvalResult(
            items = matched.size,
            topics = topics.filter { it.support > 0 || it.falsePositives > 0 },
            sentimentAccuracy = accuracy(matched) { expected, actual -> expected.sentiment to actual.sentiment },
            typeAccuracy = accuracy(matched) { expected, actual -> expected.type to actual.type },
            urgencyAccuracy = accuracy(matched) { expected, actual -> expected.urgency to actual.urgency },
            annotatorKappa = kappa(annotated),
            missing = annotated.size - matched.size,
        )
    }

    /**
     * Cohenovo κ nad multi-label anotací: každé (recenze, téma) je jedno binární rozhodnutí
     * „patří / nepatří". Bez toho by se dvě anotace daly porovnat jen jako celé množiny,
     * a jeden štítek navíc by shodil celou položku na nulu.
     */
    fun kappa(gold: List<GoldReview>): Double? {
        val pairs = gold.filter { it.topicsB != null }
        if (pairs.isEmpty()) return null
        val keys = pairs.flatMap { it.topics + it.topicsB.orEmpty() }.toSortedSet()
        if (keys.isEmpty()) return null

        var agree = 0
        var total = 0
        var aYes = 0
        var bYes = 0
        pairs.forEach { item ->
            keys.forEach { key ->
                val a = key in item.topics
                val b = key in item.topicsB.orEmpty()
                if (a == b) agree++
                if (a) aYes++
                if (b) bYes++
                total++
            }
        }
        if (total == 0) return null

        val observed = agree.toDouble() / total
        val pYes = (aYes.toDouble() / total) * (bYes.toDouble() / total)
        val pNo = (1 - aYes.toDouble() / total) * (1 - bYes.toDouble() / total)
        val expected = pYes + pNo
        return if (expected == 1.0) 1.0 else (observed - expected) / (1 - expected)
    }

    /**
     * Stratifikovaný výběr recenzí do zlatého setu: rovnoměrně po hvězdách a platformách.
     * Náhodný vzorek by z appky se čtyřkovým průměrem vytáhl samé čtyřky a o jedničkách,
     * kvůli kterým se to celé dělá, by neřekl nic.
     */
    fun <T> stratify(
        items: List<T>,
        limit: Int,
        bucketOf: (T) -> String,
    ): List<T> {
        if (items.size <= limit) return items
        val buckets = items.groupBy(bucketOf).values.map { it.toMutableList() }
        val picked = mutableListOf<T>()
        // Kolo po kole z každé přihrádky po jedné, dokud se nenaplní limit — malá přihrádka
        // se vyčerpá a zbytek si rozeberou ostatní.
        while (picked.size < limit && buckets.any { it.isNotEmpty() }) {
            buckets.forEach { bucket ->
                if (picked.size < limit && bucket.isNotEmpty()) picked += bucket.removeAt(0)
            }
        }
        return picked
    }

    fun parse(lines: Sequence<String>): List<GoldReview> =
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.decodeFromString<GoldReview>(it) }
            .toList()

    fun toJsonLine(review: GoldReview): String = json.encodeToString(GoldReview.serializer(), review)

    private fun accuracy(
        matched: List<Pair<GoldReview, EvalPrediction>>,
        field: (GoldReview, EvalPrediction) -> Pair<String?, String?>,
    ): Double {
        val comparable = matched.mapNotNull { (expected, actual) -> field(expected, actual).takeIf { it.first != null } }
        if (comparable.isEmpty()) return 0.0
        return comparable.count { it.first.equals(it.second, ignoreCase = true) }.toDouble() / comparable.size
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }
}
