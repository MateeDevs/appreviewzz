package cz.matee.appreviewzz.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Zmrazený rozbor za období (F8/C1).
 *
 * Vlastní tvar, ne `AnalysisAggregates`: tohle je **serializovaný snímek**, který přežije
 * změnu taxonomie, změnu prahů i přepočet výkladů. Doménové typy se vyvíjejí, uložený
 * report se měnit nesmí — proto tu jsou samé prosté hodnoty a žádný enum z taxonomie.
 */
@Serializable
data class ReportSnapshot(
    val appName: String,
    val organizationName: String,
    val periodStart: String,
    val periodEnd: String,
    val reviews: Int,
    val avgStars: Double?,
    val positiveShare: Double,
    val neutralShare: Double,
    val negativeShare: Double,
    val previousNegativeShare: Double?,
    val byPlatform: Map<String, Int>,
    val weekly: List<ReportWeek> = emptyList(),
    val topics: List<ReportTopic> = emptyList(),
    val improved: List<ReportImproved> = emptyList(),
    val territories: List<ReportTerritory> = emptyList(),
    val versions: List<ReportVersion> = emptyList(),
    val alerts: List<ReportAlert> = emptyList(),
    val replies: ReportReplies,
    /** Od kdy o aplikaci máme data. Bez toho je srovnání období lež, ne přehled. */
    val dataSince: String?,
    /** Jazyk textů na veřejné stránce; report se generuje v jazyce aplikace. */
    val locale: MessageLocale = MessageLocale.CS,
)

@Serializable
data class ReportWeek(
    val weekStart: String,
    val positive: Int,
    val neutral: Int,
    val negative: Int,
    val reviews: Int,
    val avgStars: Double?,
)

@Serializable
data class ReportTopic(
    val name: String,
    val count: Int,
    val share: Double,
    val negativeShare: Double,
    val avgStars: Double?,
    val status: String,
    /** Ověřený úryvek recenze; nikdy ne parafráze. */
    val quote: String? = null,
)

@Serializable
data class ReportImproved(
    val name: String,
    val before: Int,
    val after: Int,
)

@Serializable
data class ReportTerritory(
    val territory: String,
    val reviews: Int,
    val negativeShare: Double,
)

@Serializable
data class ReportVersion(
    val version: String,
    val platform: String,
    val reviews: Int,
    val avgStars: Double?,
    val negativeShare: Double,
    val newTopics: List<String> = emptyList(),
)

@Serializable
data class ReportAlert(
    val date: String,
    val what: String,
    val observed: Int,
    val expected: Double,
)

@Serializable
data class ReportReplies(
    val total: Int,
    val replied: Int,
    val medianHours: Double?,
    val uplifted: Int,
)

/**
 * Report v databázi. `shareToken` je jediné, co odemyká veřejnou stránku — zrušení sdílení
 * token maže, takže starý odkaz přestane platit okamžitě a natrvalo.
 */
data class InsightReport(
    val id: InsightReportId,
    val orgId: OrganizationId,
    val appId: AppId,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val snapshot: ReportSnapshot,
    val shareToken: String?,
    val createdAt: Instant,
)
