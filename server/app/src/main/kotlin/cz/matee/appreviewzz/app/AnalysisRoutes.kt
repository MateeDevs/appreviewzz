package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.AlertKind
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.AnalysisFilter
import cz.matee.appreviewzz.core.usecase.AnalysisAlertView
import cz.matee.appreviewzz.core.usecase.AnalysisCalendarPeriod
import cz.matee.appreviewzz.core.usecase.AnalysisOverview
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.SentimentShare
import cz.matee.appreviewzz.core.usecase.TopicStatus
import cz.matee.appreviewzz.core.usecase.VersionImpact
import cz.matee.appreviewzz.core.usecase.VersionSlice
import cz.matee.appreviewzz.core.usecase.VersionTopicShare
import cz.matee.appreviewzz.core.usecase.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class SentimentShareResponse(
    val positive: Double,
    val neutral: Double,
    val negative: Double,
)

@Serializable
data class SentimentWeekResponse(
    val weekStart: String,
    val positive: Int,
    val neutral: Int,
    val negative: Int,
    val reviews: Int,
    val avgStars: Double?,
)

@Serializable
data class TopicBreakdownResponse(
    val key: String,
    val name: String,
    val count: Int,
    val previousCount: Int,
    val share: Double,
    val negativeShare: Double,
    val avgStars: Double?,
    val status: TopicStatus,
    /** Osm bodů pro sparkline; poslední je nejnovější úsek období. */
    val trend: List<Int>,
)

@Serializable
data class ImprovedTopicResponse(
    val key: String,
    val name: String,
    val before: Int,
    val after: Int,
)

@Serializable
data class TerritoryResponse(
    val territory: String,
    val reviews: Int,
    val negativeShare: Double,
    val avgStars: Double?,
)

@Serializable
data class LanguageResponse(
    val language: String,
    val reviews: Int,
    val negativeShare: Double,
)

@Serializable
data class RepliesResponse(
    val total: Int,
    val replied: Int,
    val share: Double,
    val medianHours: Double?,
    /** Kolik recenzí po odpovědi přidalo, resp. ubralo hvězdy (B5). */
    val uplifted: Int,
    val dropped: Int,
)

/**
 * Rozbor za období pro stránku Rozbory. Čísla jsou tatáž jako ve zprávě do kanálu —
 * pochází z jednoho výpočtu, ne ze dvou dotazů, které se časem rozejdou.
 */
@Serializable
data class AnalysisOverviewResponse(
    val periodStart: String,
    val periodEnd: String,
    val dataSince: String?,
    val reviews: Int,
    val byPlatform: Map<String, Int>,
    val avgStars: Double?,
    val sentiment: SentimentShareResponse,
    val previousSentiment: SentimentShareResponse?,
    val weekly: List<SentimentWeekResponse>,
    val topics: List<TopicBreakdownResponse>,
    val improved: List<ImprovedTopicResponse>,
    val territories: List<TerritoryResponse>,
    val languages: List<LanguageResponse>,
    val replies: RepliesResponse,
    val analyzed: Int,
    val missing: Int,
    val minReviews: Int,
    val tooFewReviews: Boolean,
)

@Serializable
data class VersionTopicResponse(
    val key: String,
    val name: String,
    val count: Int,
    val share: Double,
)

@Serializable
data class VersionSliceResponse(
    val reviews: Int,
    val avgStars: Double?,
    val negativeShare: Double,
    val topics: List<VersionTopicResponse>,
)

@Serializable
data class VersionImpactResponse(
    val version: String,
    val platform: Platform,
    val firstSeen: String,
    val after: VersionSliceResponse,
    val before: VersionSliceResponse,
    val newTopics: List<VersionTopicResponse>,
    val goneTopics: List<VersionTopicResponse>,
    val starsDelta: Double?,
    val negativeDelta: Double,
)

/** Výkyv tak, jak ho vidí konzole. `expected` jde ven schválně — bez baseline se alert nedá číst. */
@Serializable
data class AnalysisAlertResponse(
    val id: String,
    val kind: AlertKind,
    val topicKey: String?,
    val topicName: String?,
    val windowDate: String,
    val observed: Int,
    val expected: Double,
    val zScore: Double,
    val createdAt: String,
)

/**
 * Rozbory recenzí (F8): stav výkladů, agregace pro stránku *Rozbory*, dopad verzí,
 * ruční běh rozboru a doplnění výkladů za historii.
 *
 * Odděleno od `reviewRoutes` schválně: inbox vrací recenze, tohle vrací **čísla o nich**
 * a nikdy ani jednu recenzi.
 */
fun Route.analysisRoutes(console: ConsoleWiring) {
    val inbox = console.reviews

    route("/orgs/{org}/apps/{app}/analysis") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val period = call.analysisCalendarPeriod()
            val days = call.analysisDays()
            val appId = call.appIdParam()
            val filter = call.analysisFilter()
            val overview =
                io {
                    if (period != null) {
                        console.analysis.overview(context.organization.id, appId, period, filter)
                    } else {
                        console.analysis.overview(context.organization.id, appId, days, filter)
                    }
                }
            call.respond(overview.toResponse())
        }

        get("/versions") {
            val context = call.orgContext(console.organizations, console.memberships)
            val versions = io { console.analysis.versions(context.organization.id, call.appIdParam()) }
            call.respond(versions.map { it.toResponse() })
        }

        get("/alerts") {
            val context = call.orgContext(console.organizations, console.memberships)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: ALERT_DAYS
            val found = io { console.analysis.alerts(context.organization.id, call.appIdParam(), days) }
            call.respond(found.map { it.toResponse() })
        }

        get("/status") {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { inbox.analysisStatus(context.organization.id, call.appIdParam()).toStatusResponse() })
        }

        /**
         * Ruční odeslání rozboru. Běží v požadavku, ne ve frontě: klient na to klikl proto,
         * aby hned viděl, co do kanálu dorazilo.
         */
        post("/weekly/run") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val weekly =
                console.weeklyAnalysis
                    ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Rozbory nejsou v tomhle procesu zapnuté")
            val report = weekly.run(context.organization.id, call.appIdParam())
            call.respond(
                WeeklyAnalysisRunResponse(
                    skipped = report.skipped?.name,
                    reviews = report.aggregates?.reviews ?: 0,
                    sent = report.deliveries.count { it.sent },
                    alreadySent = report.deliveries.count { it.alreadySent },
                    errors = report.deliveries.mapNotNull { it.error },
                ),
            )
        }

        /**
         * Doplnění výkladů za historii. Zařadí se do fronty a odpoví hned — backfill jede
         * v dávkách po desítkách vteřin a klient u toho nemá čekat s otevřeným requestem.
         */
        post("/backfill") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val appId = call.appIdParam()
            val status = io { inbox.analysisStatus(context.organization.id, appId) }
            val enqueue =
                console.enqueueAnalysis
                    ?: throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Doplňování rozborů není v tomhle procesu zapnuté",
                    )
            val queued = io { enqueue(context.organization.id.toString(), appId.toString()) }
            call.respond(HttpStatusCode.Accepted, status.copy(queued = queued).toStatusResponse())
        }
    }
}

/**
 * Zúžení na platformu nebo trh. Neznámá hodnota je chyba požadavku, ne tichý prázdný
 * výsledek — stejné pravidlo jako u filtrů inboxu.
 */
private fun ApplicationCall.analysisFilter(): AnalysisFilter {
    val platform =
        request.queryParameters["platform"]?.takeIf { it.isNotBlank() }?.let { raw ->
            Platform.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámá platforma '$raw'")
        }
    val territory =
        request.queryParameters["territory"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase()
    return AnalysisFilter(platform = platform, territory = territory)
}

private fun ApplicationCall.analysisCalendarPeriod(): AnalysisCalendarPeriod? {
    val raw = request.queryParameters["period"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!request.queryParameters["days"].isNullOrBlank()) {
        throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Vyberte kalendářní období, nebo počet dní, ne obojí")
    }
    return AnalysisCalendarPeriod.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámé období '$raw'")
}

private fun ApplicationCall.analysisDays(): Int {
    val raw = request.queryParameters["days"]?.trim()?.takeIf { it.isNotEmpty() }
    if (raw == null) return DEFAULT_DAYS
    return raw.toIntOrNull()
        ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Počet dní musí být celé číslo")
}

private fun SentimentShare.toResponse() = SentimentShareResponse(positive, neutral, negative)

private fun VersionTopicShare.toResponse() = VersionTopicResponse(key, name, count, share)

private fun VersionSlice.toResponse() =
    VersionSliceResponse(
        reviews = reviews,
        avgStars = avgStars,
        negativeShare = negativeShare,
        topics = topics.map { it.toResponse() },
    )

private fun VersionImpact.toResponse() =
    VersionImpactResponse(
        version = version,
        platform = platform,
        firstSeen = firstSeen.toString(),
        after = after.toResponse(),
        before = before.toResponse(),
        newTopics = newTopics.map { it.toResponse() },
        goneTopics = goneTopics.map { it.toResponse() },
        starsDelta = starsDelta,
        negativeDelta = negativeDelta,
    )

private fun AnalysisOverview.toResponse() =
    AnalysisOverviewResponse(
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        dataSince = dataSince?.toString(),
        reviews = reviews,
        byPlatform = byPlatform.mapKeys { (platform, _) -> platform.name },
        avgStars = avgStars,
        sentiment = sentiment.toResponse(),
        previousSentiment = previousSentiment?.toResponse(),
        weekly =
            weekly.map {
                SentimentWeekResponse(
                    weekStart = it.weekStart.toString(),
                    positive = it.positive,
                    neutral = it.neutral,
                    negative = it.negative,
                    reviews = it.reviews,
                    avgStars = it.avgStars,
                )
            },
        topics =
            topics.map {
                TopicBreakdownResponse(
                    key = it.key,
                    name = it.name,
                    count = it.count,
                    previousCount = it.previousCount,
                    share = it.share,
                    negativeShare = it.negativeShare,
                    avgStars = it.avgStars,
                    status = it.status,
                    trend = it.trend,
                )
            },
        improved = improved.map { ImprovedTopicResponse(it.key, it.name, it.before, it.after) },
        territories =
            territories.map { TerritoryResponse(it.territory, it.reviews, it.negativeShare, it.avgStars) },
        languages = languages.map { LanguageResponse(it.language, it.reviews, it.negativeShare) },
        replies =
            RepliesResponse(
                total = replies.total,
                replied = replies.replied,
                share = replies.share,
                medianHours = replies.medianHours,
                uplifted = replies.uplifted,
                dropped = replies.dropped,
            ),
        analyzed = analyzed,
        missing = missing,
        minReviews = thresholds.minReviews,
        tooFewReviews = tooFewReviews,
    )

private fun AnalysisAlertView.toResponse() =
    AnalysisAlertResponse(
        id = alert.id.toString(),
        kind = alert.kind,
        topicKey = alert.topicKey,
        topicName = topicName,
        windowDate = alert.windowDate.toString(),
        observed = alert.observed,
        expected = alert.expected,
        zScore = alert.zScore,
        createdAt = alert.createdAt.toString(),
    )

private const val DEFAULT_DAYS = 30
private const val ALERT_DAYS = 90
