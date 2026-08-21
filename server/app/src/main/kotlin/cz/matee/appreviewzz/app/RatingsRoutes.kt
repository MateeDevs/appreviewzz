package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.usecase.RatingsSeries
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class RatingsPointResponse(
    val date: String,
    val average: Double?,
    val totalCount: Long?,
    /** Přírůstek proti předchozímu bodu; `null` u nejstaršího, kde není co odečíst. */
    val newCount: Long?,
    /** Počty po hvězdách 1..5, když je zdroj dává. */
    val histogram: Map<String, Long>,
    val source: String,
)

@Serializable
data class RatingsSeriesResponse(
    val platform: Platform,
    val territory: String,
    val points: List<RatingsPointResponse>,
    /** Změna průměru za zobrazené období — číslo, kvůli kterému se na graf lidi dívají. */
    val change: Double?,
)

@Serializable
data class RatingsRunResponse(
    val platforms: Int,
    val sent: Int,
    val alreadySent: Int,
    val errors: List<String>,
)

/**
 * Vývoj hodnocení a ruční spuštění přehledu (F4.5).
 *
 * `POST …/ratings/run` je tu ze stejného důvodu jako `channel test`: při onboardingu je
 * potřeba vidět, že přehled opravdu chodí a jak vypadá, ne čekat do zítřejších 8:30.
 */
fun Route.ratingsRoutes(console: ConsoleWiring) {
    route("/orgs/{org}/apps/{app}/ratings") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: DEFAULT_DAYS
            val history =
                io { console.ratings.history(context.organization.id, call.appIdParam(), days) }
            call.respond(history.map { it.toResponse() })
        }

        post("/run") {
            val context = call.orgContext(console.organizations, console.memberships)
            val report = console.dailyRatings.run(context.organization.id, call.appIdParam())
            report.skipped?.let {
                // Přeskočení není chyba serveru: je to stav, který má klient vidět jako větu.
                call.respond(HttpStatusCode.OK, RatingsRunResponse(0, 0, 0, listOf(describe(it.name))))
                return@post
            }
            call.respond(
                RatingsRunResponse(
                    platforms = report.platforms.size,
                    sent = report.deliveries.count { it.sent },
                    alreadySent = report.deliveries.count { it.alreadySent },
                    errors =
                        report.failures.map { "${it.platform}: ${it.message}" } +
                            report.deliveries.mapNotNull { it.error },
                ),
            )
        }
    }
}

private fun RatingsSeries.toResponse() =
    RatingsSeriesResponse(
        platform = platform,
        territory = territory,
        points =
            points.map { point ->
                RatingsPointResponse(
                    date = point.snapshot.date.toString(),
                    average = point.snapshot.average,
                    totalCount = point.snapshot.totalCount,
                    newCount = point.newCount,
                    histogram = point.snapshot.histogram.mapKeys { (stars, _) -> stars.toString() },
                    source = point.snapshot.source.name,
                )
            },
        change = change,
    )

/** Důvody přeskočení jsou enum; klient má dostat větu, ne konstantu. */
private fun describe(reason: String): String =
    when (reason) {
        "APP_DISABLED" -> "Aplikace je vypnutá"
        "NO_CHANNEL" -> "Aplikace nemá kanál, do kterého by přehled chodil"
        "NO_DATA" -> "Ze storů se nepodařilo načíst žádná hodnocení"
        else -> "Přehled se neposlal ($reason)"
    }

private const val DEFAULT_DAYS = 30
