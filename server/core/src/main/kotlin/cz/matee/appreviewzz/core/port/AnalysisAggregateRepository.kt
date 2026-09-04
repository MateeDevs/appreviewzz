package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewId
import kotlin.time.Instant

/** Jedno téma za období, jak ho spočítala databáze. Podíly a trendy dopočítá use case. */
data class TopicAggregate(
    val key: String,
    /** Počet **zmínek**, ne recenzí — jedna recenze mluví o víc tématech. */
    val count: Int,
    val negative: Int,
    val positive: Int,
    /** Součet hvězd recenzí, ve kterých se téma objevilo; průměr se dopočítá. */
    val starSum: Int,
)

data class VersionAggregate(
    val version: String,
    val platform: Platform,
    val count: Int,
    val starSum: Int,
    val negative: Int,
)

/**
 * Čísla za jedno období. Počítá je **databáze**, ne model: rozbor má být opakovatelný
 * a ověřitelný, a jazykový model se v aritmetice mýlí způsobem, který nejde poznat.
 */
data class AnalysisPeriod(
    val reviews: Int,
    val byPlatform: Map<Platform, Int>,
    val starSum: Int,
    /** Kolik recenzí má jakou celkovou náladu. */
    val sentiments: Map<OverallSentiment, Int>,
    val topics: List<TopicAggregate>,
    val versions: List<VersionAggregate>,
) {
    val avgStars: Double? get() = if (reviews > 0) starSum.toDouble() / reviews else null

    companion object {
        val EMPTY =
            AnalysisPeriod(
                reviews = 0,
                byPlatform = emptyMap(),
                starSum = 0,
                sentiments = emptyMap(),
                topics = emptyList(),
                versions = emptyList(),
            )
    }
}

/** Jak se v období odpovídalo. Medián, ne průměr — jedna zapomenutá recenze jinak utrhne osu. */
data class ReplyStats(
    val total: Int,
    val replied: Int,
    val medianHours: Double?,
) {
    val share: Double get() = if (total > 0) replied.toDouble() / total else 0.0
}

/** Citát k tématu i s tím, odkud je — do rozboru se pouští jen ověřené úryvky. */
data class TopicQuote(
    val reviewId: ReviewId,
    val quote: String,
    val starRating: Int,
    val platform: Platform,
    val appVersion: String?,
)

/**
 * Agregace nad výklady recenzí (F8). Vlastní port, ne metody na [ReviewInsightRepository]:
 * tohle jsou dotazy, které nikdy nevrací entity, jenom čísla — a jako jediné se počítají
 * v databázi, ne v paměti.
 */
interface AnalysisAggregateRepository {
    fun aggregate(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
    ): AnalysisPeriod

    fun replyStats(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
    ): ReplyStats

    /**
     * Od kdy o aplikaci vůbec máme data. Google Play vrací jen týden zpět, takže rozbor musí
     * říct, od kdy čísla platí — jinak vypadá krátká historie jako propad zájmu.
     */
    fun dataSince(
        orgId: OrganizationId,
        appId: AppId,
    ): Instant?

    /**
     * Nejlepší citát k tématu: nejnaléhavější, pak nejnovější. Jen ověřené úryvky —
     * `quote` v databázi je vždy doslovný podřetězec recenze.
     */
    fun topQuote(
        orgId: OrganizationId,
        appId: AppId,
        topicKey: String,
        from: Instant,
        to: Instant,
    ): TopicQuote?
}
