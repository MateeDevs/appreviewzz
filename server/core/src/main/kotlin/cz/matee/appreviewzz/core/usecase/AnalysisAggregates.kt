package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.port.AnalysisPeriod
import cz.matee.appreviewzz.core.port.ReplyStats
import cz.matee.appreviewzz.core.port.TopicAggregate
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Prahy rozboru. Nejsou to preference klienta, ale statistika: podíl ze tří recenzí je
 * náhoda a jedna zmínka není téma. Výchozí hodnoty drží platforma
 * ([cz.matee.appreviewzz.core.model.PlatformSettings]), aplikace si od prvních dvou může
 * udělat výjimku — appky se objemem liší o řád.
 */
data class AnalysisThresholds(
    /** Kolik recenzí s textem se musí nasbírat, aby rozbor odešel. */
    val minReviews: Int = DEFAULT_MIN_REVIEWS,
    /** Od kolika zmínek je téma tématem. */
    val minTopicCount: Int = DEFAULT_MIN_TOPIC_COUNT,
    /** Kolik problémů se vejde do zprávy do kanálu. */
    val topIssues: Int = DEFAULT_TOP_ISSUES,
) {
    /** Výjimky konkrétní aplikace; `null` znamená „drž se platformy". */
    fun forApp(app: App): AnalysisThresholds =
        copy(
            minReviews = app.analysisMinReviews ?: minReviews,
            minTopicCount = app.analysisMinTopicCount ?: minTopicCount,
        )

    companion object {
        const val DEFAULT_MIN_REVIEWS = 10
        const val DEFAULT_MIN_TOPIC_COUNT = 3
        const val DEFAULT_TOP_ISSUES = 3
    }
}

/** Jak se téma vyvíjí proti minulému období. Rozhoduje o tom, co se dostane do rozboru. */
enum class TopicStatus {
    /** V minulém období skoro nebylo. */
    NEW,

    /** Nejméně dvojnásobek a rozdíl aspoň tři zmínky. */
    GROWING,
    STABLE,
    FALLING,
}

data class TopicInsight(
    val key: String,
    /** Název v jazyce kanálu; u smazaného vlastního tématu zůstane klíč. */
    val name: String,
    val count: Int,
    val previousCount: Int,
    val negativeShare: Double,
    val avgStars: Double?,
    val status: TopicStatus,
    /** Podíl na počtu recenzí období — součet přes témata přesahuje 100 %, a je to správně. */
    val share: Double,
) {
    /**
     * Pořadí problémů v rozboru: velikost krát bolestivost. Deset zmínek, ze kterých je devět
     * záporných, je zpráva; deset zmínek samé chvály není problém.
     */
    val severity: Double get() = count * negativeShare
}

/** Téma, kterého ubylo. Zlepšení se v rozboru ukazuje zvlášť — jinak zapadne mezi problémy. */
data class ImprovedTopic(
    val key: String,
    val name: String,
    val before: Int,
    val after: Int,
)

data class SentimentShare(
    val positive: Double,
    val neutral: Double,
    val negative: Double,
) {
    companion object {
        val EMPTY = SentimentShare(0.0, 0.0, 0.0)

        /**
         * Podíly nálady. `MIXED` se počítá do záporných: recenze, která chválí a zároveň si
         * stěžuje, je pro tým práce, ne pochvala.
         */
        fun of(counts: Map<OverallSentiment, Int>): SentimentShare {
            val total = counts.values.sum()
            if (total == 0) return EMPTY
            val positive = counts[OverallSentiment.POSITIVE] ?: 0
            val neutral = counts[OverallSentiment.NEUTRAL] ?: 0
            val negative = (counts[OverallSentiment.NEGATIVE] ?: 0) + (counts[OverallSentiment.MIXED] ?: 0)
            return SentimentShare(
                positive = positive.toDouble() / total,
                neutral = neutral.toDouble() / total,
                negative = negative.toDouble() / total,
            )
        }
    }
}

data class VersionInsight(
    val version: String,
    val platform: Platform,
    val count: Int,
    val avgStars: Double?,
    val negativeShare: Double,
)

/**
 * Rozbor recenzí za období — všechno, co jde spočítat z databáze.
 *
 * Tohle je jediný zdroj čísel pro týdenní zprávu, stránku Rozbory i klientský report.
 * Model do něj nesahá: kdyby čísla psala AI, nešlo by je ověřit ani zopakovat.
 */
data class AnalysisAggregates(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val reviews: Int,
    val byPlatform: Map<Platform, Int>,
    val avgStars: Double?,
    val sentiment: SentimentShare,
    val previousSentiment: SentimentShare?,
    /** Seřazeno podle [TopicInsight.severity] — první je to, co nejvíc bolí. */
    val topics: List<TopicInsight>,
    val improved: List<ImprovedTopic>,
    val replies: ReplyStats,
    val versions: List<VersionInsight>,
    /** Od kdy o aplikaci máme data; u Androidu bývá historie krátká a rozbor to musí říct. */
    val dataSince: Instant?,
    /** Prahy, se kterými se čísla počítala — zpráva i konzole se ptají na tentýž údaj. */
    val thresholds: AnalysisThresholds = AnalysisThresholds(),
) {
    /** Pod tímhle počtem se z čísel nedá nic vyčíst a rozbor se neposílá. */
    val tooFewReviews: Boolean get() = reviews < thresholds.minReviews

    /** Problémy, které se vejdou do zprávy do kanálu. */
    val issues: List<TopicInsight> get() = topics.take(thresholds.topIssues)

    val previousNegativeDelta: Double?
        get() = previousSentiment?.let { sentiment.negative - it.negative }

    companion object {
        /** „Nové" téma: minulé období mělo nejvýš tolik zmínek. */
        private const val NEW_TOPIC_CEILING = 1

        /** „Roste": aspoň dvojnásobek a zároveň rozdíl aspoň tři zmínky. */
        private const val GROWTH_FACTOR = 2
        private const val GROWTH_DIFFERENCE = 3

        /** Zlepšení: téma ubylo aspoň o třetinu a rozdíl je aspoň tři zmínky. */
        private const val IMPROVEMENT_RATIO = 0.66

        /**
         * Složení rozboru z čísel dvou období. Čistá funkce schválně: testuje se bez databáze
         * a stejná pravidla platí pro zprávu do kanálu i pro stránku v konzoli.
         *
         * @param names názvy vlastních témat aplikace; taxonomii přeloží [Topic] sám
         */
        fun of(
            periodStart: LocalDate,
            periodEnd: LocalDate,
            current: AnalysisPeriod,
            previous: AnalysisPeriod,
            replies: ReplyStats,
            dataSince: Instant?,
            locale: MessageLocale,
            thresholds: AnalysisThresholds = AnalysisThresholds(),
            names: Map<String, String> = emptyMap(),
        ): AnalysisAggregates {
            val previousByKey = previous.topics.associateBy { it.key }
            val topics =
                current.topics
                    .filter { it.count >= thresholds.minTopicCount }
                    .map { topic ->
                        val before = previousByKey[topic.key]?.count ?: 0
                        TopicInsight(
                            key = topic.key,
                            name = nameOf(topic.key, locale, names),
                            count = topic.count,
                            previousCount = before,
                            negativeShare = topic.negativeShare(),
                            avgStars = topic.avgStars(),
                            status = statusOf(topic.count, before),
                            share = if (current.reviews > 0) topic.count.toDouble() / current.reviews else 0.0,
                        )
                    }.sortedByDescending { it.severity }

            val improved =
                previous.topics
                    .filter { it.count >= thresholds.minTopicCount }
                    .mapNotNull { before ->
                        val after = current.topics.firstOrNull { it.key == before.key }?.count ?: 0
                        val dropped = before.count - after
                        if (after <= before.count * IMPROVEMENT_RATIO && dropped >= GROWTH_DIFFERENCE) {
                            ImprovedTopic(before.key, nameOf(before.key, locale, names), before.count, after)
                        } else {
                            null
                        }
                    }.sortedByDescending { it.before - it.after }

            return AnalysisAggregates(
                periodStart = periodStart,
                periodEnd = periodEnd,
                reviews = current.reviews,
                byPlatform = current.byPlatform,
                avgStars = current.avgStars,
                sentiment = SentimentShare.of(current.sentiments),
                previousSentiment = if (previous.reviews > 0) SentimentShare.of(previous.sentiments) else null,
                topics = topics,
                improved = improved,
                replies = replies,
                versions =
                    current.versions.map {
                        VersionInsight(
                            version = it.version,
                            platform = it.platform,
                            count = it.count,
                            avgStars = if (it.count > 0) it.starSum.toDouble() / it.count else null,
                            negativeShare = if (it.count > 0) it.negative.toDouble() / it.count else 0.0,
                        )
                    },
                dataSince = dataSince,
                thresholds = thresholds,
            )
        }

        fun nameOf(
            key: String,
            locale: MessageLocale,
            names: Map<String, String>,
        ): String = Topic.ofKey(key)?.label(locale) ?: names[key] ?: key

        private fun statusOf(
            count: Int,
            before: Int,
        ): TopicStatus =
            when {
                before <= NEW_TOPIC_CEILING -> TopicStatus.NEW
                count >= before * GROWTH_FACTOR && count - before >= GROWTH_DIFFERENCE -> TopicStatus.GROWING
                count <= before * IMPROVEMENT_RATIO -> TopicStatus.FALLING
                else -> TopicStatus.STABLE
            }

        private fun TopicAggregate.negativeShare(): Double = if (count > 0) negative.toDouble() / count else 0.0

        private fun TopicAggregate.avgStars(): Double? = if (count > 0) starSum.toDouble() / count else null
    }
}
