package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.ReviewId
import kotlinx.datetime.LocalDate
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

/** Které recenze vstupují do čísel. Témata standardně pracují jen s tím, co lidé napsali. */
enum class AnalysisReviewScope {
    WITH_TEXT,
    ALL,
}

/**
 * Zúžení rozboru na část recenzí. Stránka Rozbory se ptá „a co jenom Android?" nebo
 * „a co jenom Německo?"; zpráva do kanálu filtr nepoužívá — ta je vždycky za celou appku.
 */
data class AnalysisFilter(
    val platform: Platform? = null,
    /** Kód země ze storu (`CZ`, `DE`); `null` = všechny trhy. */
    val territory: String? = null,
    /** Verze aplikace; používá ji jen dopad verze (B3), stránka a zpráva nikdy. */
    val version: String? = null,
    /** `ALL` se používá pro volitelný pohled na náladu včetně samotných hvězdiček. */
    val reviewScope: AnalysisReviewScope = AnalysisReviewScope.WITH_TEXT,
) {
    val isEmpty: Boolean
        get() =
            platform == null &&
                territory == null &&
                version == null &&
                reviewScope == AnalysisReviewScope.WITH_TEXT

    companion object {
        val ALL = AnalysisFilter()
    }
}

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
    /**
     * Kolik recenzí po naší odpovědi **přidalo hvězdy** a kolik jich ubralo (B5). Počítá se
     * z revizí recenze, ne z dojmu: člověk recenzi po odpovědi přepsal a store nám ji vrátil
     * s jiným hodnocením. `0/0` znamená „nemáme dost revizí", ne „odpovídání nefunguje".
     */
    val uplifted: Int = 0,
    val dropped: Int = 0,
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
 * Jeden den v zóně aplikace. Základ týdenní řady nálady na stránce Rozbory i vstup
 * detekce výkyvu (B4) — proto se počítá po dnech, ne po týdnech.
 */
data class DayCounts(
    val date: LocalDate,
    val reviews: Int,
    val starSum: Int,
    val sentiments: Map<OverallSentiment, Int>,
) {
    /** `MIXED` se počítá k záporným, stejně jako všude jinde v rozboru. */
    val negative: Int
        get() = (sentiments[OverallSentiment.NEGATIVE] ?: 0) + (sentiments[OverallSentiment.MIXED] ?: 0)
}

/** Kolikrát se téma objevilo v jeden den. Vstup detekce výkyvu tématu. */
data class DayTopicCount(
    val date: LocalDate,
    val topicKey: String,
    val count: Int,
)

data class TerritoryAggregate(
    val territory: String,
    val reviews: Int,
    val negative: Int,
    val starSum: Int,
)

/** Jazyk podle výkladu, ne podle storu — store hlásí jazyk zařízení, model čte text. */
data class LanguageAggregate(
    val language: String,
    val reviews: Int,
    val negative: Int,
)

/**
 * Verze aplikace a den, kdy se poprvé objevila v recenzích. „Před vydáním" se počítá
 * k tomuhle okamžiku, ne ke kalendářnímu měsíci — jinak by srovnání záviselo na tom,
 * kdy se klient na stránku podíval.
 */
data class VersionWindow(
    val version: String,
    val platform: Platform,
    val firstSeen: Instant,
    val reviews: Int,
)

/**
 * Agregace nad výklady recenzí (F8). Vlastní port, ne metody na [ReviewInsightRepository]:
 * tohle jsou dotazy, které nikdy nevrací entity, jenom čísla — a jako jediné se počítají
 * v databázi, ne v paměti.
 */
@Suppress("TooManyFunctions")
interface AnalysisAggregateRepository {
    fun aggregate(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): AnalysisPeriod

    fun replyStats(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter = AnalysisFilter.ALL,
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
     * Nejlepší citáty k tématu: nejnaléhavější, pak nejnovější. Jen ověřené úryvky —
     * `quote` v databázi je vždy doslovný podřetězec recenze.
     */
    fun topQuotes(
        orgId: OrganizationId,
        appId: AppId,
        topicKey: String,
        from: Instant,
        to: Instant,
        limit: Int = 1,
    ): List<TopicQuote>

    /**
     * Denní počty v zóně aplikace. Zóna se předává jako IANA jméno, protože buckety počítá
     * Postgres (`AT TIME ZONE`) — dvanáct měsíců recenzí by se do paměti tahalo zbytečně.
     */
    fun daily(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): List<DayCounts>

    /** Denní počty zmínek témat; jen témata, která se v období vůbec objevila. */
    fun dailyTopics(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        timezone: String,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): List<DayTopicCount>

    fun territories(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): List<TerritoryAggregate>

    fun languages(
        orgId: OrganizationId,
        appId: AppId,
        from: Instant,
        to: Instant,
        filter: AnalysisFilter = AnalysisFilter.ALL,
    ): List<LanguageAggregate>

    /**
     * Verze s aspoň [minReviews] recenzemi, které se poprvé objevily po [since].
     * Seřazeno od nejnovější — klienta zajímá poslední vydání.
     */
    fun versionWindows(
        orgId: OrganizationId,
        appId: AppId,
        since: Instant,
        minReviews: Int,
    ): List<VersionWindow>
}

/** Jediný citát; zdaleka nejčastější použití [AnalysisAggregateRepository.topQuotes]. */
fun AnalysisAggregateRepository.topQuote(
    orgId: OrganizationId,
    appId: AppId,
    topicKey: String,
    from: Instant,
    to: Instant,
): TopicQuote? = topQuotes(orgId, appId, topicKey, from, to, limit = 1).firstOrNull()
