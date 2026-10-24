package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.InsightReport
import cz.matee.appreviewzz.core.model.InsightReportId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.ReportSnapshot
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.MonthlyReportUseCase
import cz.matee.appreviewzz.core.usecase.requireRole
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class InsightReportResponse(
    val id: String,
    val periodStart: String,
    val periodEnd: String,
    val reviews: Int,
    val createdAt: String,
    /** Veřejný odkaz; `null` = report se nesdílí. */
    val shareUrl: String?,
)

/** Zmrazené agregáty. Konzole je jen ukazuje — přepočítat se dají výhradně přegenerováním. */
@Serializable
data class InsightReportDetailResponse(
    val report: InsightReportResponse,
    val snapshot: ReportSnapshot,
)

/**
 * Měsíční reporty pro klienta (C1).
 *
 * Sdílení je jednorázový token v adrese, ne veřejné id: report je soukromý dokument
 * a `insight_report.id` by šlo uhádnout ze seznamu v konzoli.
 */
fun Route.reportRoutes(console: ConsoleWiring) {
    val reports = console.reports ?: return

    route("/orgs/{org}/apps/{app}/reports") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val found = io { reports.list(context.organization.id, call.appIdParam()) }
            call.respond(found.map { it.toResponse(console) })
        }

        /**
         * Přegenerování měsíce. Běží v požadavku: je to jeden průchod agregacemi nad jednou
         * appkou a klient na výsledek kouká hned.
         */
        post {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val month = call.request.queryParameters["month"]?.let { parseMonth(it) }
            val report =
                io { reports.generate(context.organization.id, call.appIdParam(), month) }
                    ?: throw ConsoleException(
                        ConsoleFailure.INVALID_INPUT,
                        "Report se negeneruje — organizace je na tarifu Starter. Přepni ho v sekci Organizace.",
                    )
            call.respond(HttpStatusCode.Created, report.toResponse(console))
        }

        get("/{report}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val report =
                io { reports.find(context.organization.id, call.reportIdParam()) }
                    ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový report tu není")
            call.respond(InsightReportDetailResponse(report.toResponse(console), report.snapshot))
        }

        post("/{report}/share") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val url =
                io { reports.share(context.organization.id, call.reportIdParam()) }
                    ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový report tu není")
            call.respond(ShareResponse(url))
        }

        delete("/{report}/share") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            io { reports.unshare(context.organization.id, call.reportIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class ShareResponse(
    val shareUrl: String,
)

/**
 * Veřejná stránka reportu — mimo `/api`, bez session, s limitem požadavků jako webhooky.
 *
 * Neexistující i zrušený token končí stejně: **404 s prostou větou**. Rozlišovat „token
 * nikdy nebyl" od „sdílení bylo zrušeno" by z adresy udělalo nástroj na zjišťování, kdo je
 * náš zákazník.
 */
fun Application.publicReportRoutes(
    reports: MonthlyReportUseCase,
    limits: RateLimits = RateLimits.disabled(),
) {
    routing {
        route("/r/{token}") {
            rateLimited(limits.webhook)
            get {
                val token = call.parameters["token"].orEmpty()
                val report = if (token.isBlank()) null else io { reports.findShared(token) }
                if (report == null) {
                    call.respondText(
                        NOT_FOUND_PAGE,
                        ContentType.Text.Html,
                        HttpStatusCode.NotFound,
                    )
                    return@get
                }
                call.respondText(ReportPage.render(report.snapshot), ContentType.Text.Html)
            }
        }
    }
}

private fun ApplicationCall.reportIdParam(): InsightReportId =
    runCatching { InsightReportId(Uuid.parse(parameters["report"].orEmpty())) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takový report tu není") }

private fun InsightReport.toResponse(console: ConsoleWiring) =
    InsightReportResponse(
        id = id.toString(),
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        reviews = snapshot.reviews,
        createdAt = createdAt.toString(),
        shareUrl = shareToken?.let { console.links.report(it) },
    )

/** `YYYY-MM` na první den měsíce. Chybný tvar je chyba požadavku, ne tichý minulý měsíc. */
private fun parseMonth(raw: String): kotlinx.datetime.LocalDate =
    runCatching { kotlinx.datetime.LocalDate.parse("$raw-01") }
        .getOrElse { throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Měsíc se píše jako YYYY-MM, ne '$raw'") }

private val NOT_FOUND_PAGE =
    """
    <!doctype html>
    <html lang="cs"><head><meta charset="utf-8"><meta name="robots" content="noindex, nofollow">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Report není k dispozici</title>
    <style>
    :root { color-scheme: light; --bg:#f5f4f0; --text:#172724; --muted:#58675f; --accent:#146c60; }
    @media(prefers-color-scheme:dark) {
      :root { color-scheme:dark; --bg:#101b19; --text:#e9f0e9; --muted:#afbeb2; --accent:#9dd7c1; }
    }
    * { box-sizing:border-box; }
    body { margin:0; background:var(--bg); color:var(--text); font:15px/1.7 system-ui,sans-serif; }
    main { max-width:34rem; margin:20vh auto 3rem; padding:0 1.5rem; }
    .brand { display:flex; align-items:center; gap:.6rem; font-weight:650; letter-spacing:-.04em; }
    svg { width:32px; height:32px; color:var(--accent); }
    h1 { font-size:2.3rem; line-height:1.2; letter-spacing:-.045em; font-weight:500; margin:2.5rem 0 1rem; }
    p { color:var(--muted); }
    </style></head><body><main>
    <div class="brand"><svg viewBox="0 0 40 40" aria-hidden="true">
    <path d="M8 16v8m9-14v20m9-16v12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"/>
    <circle cx="32" cy="9" r="2.5" fill="currentColor"/></svg>appreviewzz</div>
    <h1>Report není k dispozici</h1>
    <p>Odkaz buď neplatí, nebo bylo sdílení zrušeno. Zkuste si o něj říct tomu, kdo vám ho poslal.</p>
    </main></body></html>
    """.trimIndent()
