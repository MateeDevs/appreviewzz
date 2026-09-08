package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.InsightReport
import cz.matee.appreviewzz.core.model.InsightReportId
import cz.matee.appreviewzz.core.model.OrgPlan
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReportAlert
import cz.matee.appreviewzz.core.model.ReportImproved
import cz.matee.appreviewzz.core.model.ReportReplies
import cz.matee.appreviewzz.core.model.ReportSnapshot
import cz.matee.appreviewzz.core.model.ReportTerritory
import cz.matee.appreviewzz.core.model.ReportTopic
import cz.matee.appreviewzz.core.model.ReportVersion
import cz.matee.appreviewzz.core.model.ReportWeek
import cz.matee.appreviewzz.core.port.AnalysisAggregateRepository
import cz.matee.appreviewzz.core.port.AnalysisAlertRepository
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.InsightReportRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Měsíční report pro klienta (C1).
 *
 * Report se **zmrazí**: agregáty se spočítají jednou a uloží jako JSON. Odkaz, který
 * agentura pošle klientovi, musí za půl roku ukázat totéž co dnes — a živý dotaz by se
 * změnil s každým dotagováním i s příští verzí taxonomie.
 *
 * Nedělá se pro plán `STARTER`: report se negeneruje pro nikoho, kdo ho neuvidí, ať se
 * databáze neplní JSONem, na který nikdo neklikne. Plán se jinak nevynucuje.
 */
@Suppress("LongParameterList")
class MonthlyReportUseCase(
    private val apps: AppRepository,
    private val organizations: OrganizationRepository,
    private val insights: AnalysisInsights,
    private val aggregates: AnalysisAggregateRepository,
    private val alerts: AnalysisAlertRepository,
    private val reports: InsightReportRepository,
    private val links: ConsoleLinks,
    private val clock: Clock = Clock.System,
) {
    /**
     * @param month první den měsíce, za který se report generuje; `null` = minulý měsíc
     * @return `null`, když appka neexistuje nebo organizace na report nemá plán
     */
    fun generate(
        orgId: OrganizationId,
        appId: AppId,
        month: LocalDate? = null,
    ): InsightReport? {
        val app = apps.findById(orgId, appId) ?: return null
        val organization = organizations.findById(orgId) ?: return null
        if (organization.plan == OrgPlan.STARTER) {
            logger.info { "Report appky $appId se negeneruje: organizace je na plánu STARTER" }
            return null
        }

        val zone = runCatching { TimeZone.of(app.timezone) }.getOrDefault(TimeZone.UTC)
        val start = month ?: previousMonth(zone)
        val end = start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        val days = start.until(end)

        // Agregáty se počítají stejným use casem jako stránka Rozbory — čísla v reportu
        // se od těch v konzoli nesmějí lišit, i když se na ně klient dívá o týden později.
        val overview = insights.overview(orgId, appId, days)
        val versions = insights.versions(orgId, appId).filter { it.firstSeen >= start.atStartOfDayIn(zone) }
        val from = start.atStartOfDayIn(zone)
        val to = end.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)

        val snapshot =
            ReportSnapshot(
                appName = app.name,
                organizationName = organization.name,
                periodStart = start.toString(),
                periodEnd = end.toString(),
                reviews = overview.reviews,
                avgStars = overview.avgStars,
                positiveShare = overview.sentiment.positive,
                neutralShare = overview.sentiment.neutral,
                negativeShare = overview.sentiment.negative,
                previousNegativeShare = overview.previousSentiment?.negative,
                byPlatform = overview.byPlatform.mapKeys { (platform, _) -> platform.name },
                weekly =
                    overview.weekly.map {
                        ReportWeek(
                            weekStart = it.weekStart.toString(),
                            positive = it.positive,
                            neutral = it.neutral,
                            negative = it.negative,
                            reviews = it.reviews,
                            avgStars = it.avgStars,
                        )
                    },
                topics =
                    overview.topics.map { topic ->
                        ReportTopic(
                            name = topic.name,
                            count = topic.count,
                            share = topic.share,
                            negativeShare = topic.negativeShare,
                            avgStars = topic.avgStars,
                            status = topic.status.name,
                            // Jen ověřený úryvek; citát se do reportu pro klienta nevymýšlí.
                            quote = aggregates.topQuotes(orgId, appId, topic.key, from, to, 1).firstOrNull()?.quote,
                        )
                    },
                improved = overview.improved.map { ReportImproved(it.name, it.before, it.after) },
                territories =
                    overview.territories.take(MAX_TERRITORIES).map {
                        ReportTerritory(it.territory, it.reviews, it.negativeShare)
                    },
                versions =
                    versions.map { impact ->
                        ReportVersion(
                            version = impact.version,
                            platform = impact.platform.name,
                            reviews = impact.after.reviews,
                            avgStars = impact.after.avgStars,
                            negativeShare = impact.after.negativeShare,
                            newTopics = impact.newTopics.map { it.name },
                        )
                    },
                alerts =
                    alerts.listByApp(orgId, appId, start, MAX_ALERTS).filter { it.windowDate <= end }.map { alert ->
                        ReportAlert(
                            date = alert.windowDate.toString(),
                            what =
                                when (alert.kind) {
                                    AlertKind.NEGATIVE_SPIKE -> "negative"
                                    AlertKind.TOPIC_SPIKE ->
                                        alert.topicKey?.let { AnalysisAggregates.nameOf(it, app.locale, emptyMap()) }.orEmpty()
                                },
                            observed = alert.observed,
                            expected = alert.expected,
                        )
                    },
                replies =
                    ReportReplies(
                        total = overview.replies.total,
                        replied = overview.replies.replied,
                        medianHours = overview.replies.medianHours,
                        uplifted = overview.replies.uplifted,
                    ),
                dataSince =
                    overview.dataSince
                        ?.toLocalDateTime(zone)
                        ?.date
                        ?.toString(),
                locale = app.locale,
            )

        return reports.upsert(orgId, appId, start, end, snapshot, clock.now())
    }

    /**
     * Zapne sdílení a vrátí veřejný odkaz. Token je 32 náhodných bajtů v base64url —
     * hádat se nedá a v adrese vypadá jako obyčejný identifikátor.
     */
    fun share(
        orgId: OrganizationId,
        id: InsightReportId,
    ): String? {
        val existing = reports.findById(orgId, id) ?: return null
        val token = existing.shareToken ?: newToken()
        reports.setShareToken(orgId, id, token)
        return links.report(token)
    }

    /** Zrušení sdílení token maže — starý odkaz přestane platit okamžitě a natrvalo. */
    fun unshare(
        orgId: OrganizationId,
        id: InsightReportId,
    ): Boolean = reports.setShareToken(orgId, id, null)

    fun list(
        orgId: OrganizationId,
        appId: AppId,
    ): List<InsightReport> = reports.listByApp(orgId, appId, MAX_REPORTS)

    fun find(
        orgId: OrganizationId,
        id: InsightReportId,
    ): InsightReport? = reports.findById(orgId, id)

    /** Veřejná stránka nemá session; token je jediné, čím se návštěvník prokazuje. */
    fun findShared(token: String): InsightReport? = reports.findByShareToken(token)

    private fun previousMonth(zone: TimeZone): LocalDate {
        val today = clock.now().toLocalDateTime(zone).date
        return LocalDate(today.year, today.month, 1).minus(1, DateTimeUnit.MONTH)
    }

    private fun LocalDate.until(other: LocalDate): Int = daysUntil(other) + 1

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val MAX_TERRITORIES = 8
        const val MAX_ALERTS = 20
        const val MAX_REPORTS = 24
    }
}
