package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.App
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.usecase.AppDraft
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class CreateAppRequest(
    val name: String,
    val gpPackageName: String? = null,
    /** Bucket s reportingem Play Console (`pubsite_prod_…`) — bez něj se Android čísla scrapují. */
    val gpReportingBucket: String? = null,
    val ascAppId: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /** `now` nebo ISO-8601. Při onboardingu existující appky se posílá `now`. */
    val notifyFrom: String? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int? = null,
    val dailyDigestAt: String? = null,
)

@Serializable
data class UpdateAppRequest(
    val name: String,
    val gpReportingBucket: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val notifyFrom: String? = null,
    val aiInstructions: String? = null,
    val ingestIntervalMinutes: Int? = null,
    val dailyDigestAt: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class AppResponse(
    val id: String,
    val name: String,
    val gpPackageName: String?,
    val gpReportingBucket: String?,
    val ascAppId: String?,
    val platforms: List<Platform>,
    val locale: MessageLocale,
    val timezone: String,
    val notifyFrom: String?,
    val aiInstructions: String?,
    val ingestIntervalMinutes: Int,
    val dailyDigestAt: String,
    val enabled: Boolean,
)

/**
 * Sledované aplikace (F3.3) — druhý krok onboardingu, hned po založení organizace.
 *
 * Store identifikátory jde zadat jen při zakládání. Přepnutí appky na jiný balíček by
 * zdědilo cizí recenze i watermark, takže od toho je nová appka; `PATCH` proto mění
 * jen nastavení.
 */
fun Route.appRoutes(console: ConsoleWiring) {
    val apps = console.apps

    route("/orgs/{org}/apps") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { apps.list(context.organization.id).map { it.toResponse() } })
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<CreateAppRequest>()
            val app =
                io {
                    apps.create(
                        organization = context.organization,
                        actor = context.actor,
                        draft =
                            AppDraft(
                                name = request.name,
                                gpPackageName = request.gpPackageName,
                                gpReportingBucket = request.gpReportingBucket,
                                ascAppId = request.ascAppId,
                                locale = request.locale,
                                timezone = request.timezone,
                                notifyFrom = request.notifyFrom,
                                aiInstructions = request.aiInstructions,
                                ingestIntervalMinutes = request.ingestIntervalMinutes,
                                dailyDigestAt = request.dailyDigestAt,
                            ),
                    )
                }
            call.respond(HttpStatusCode.Created, app.toResponse())
        }

        get("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { apps.get(context.organization.id, call.appIdParam()).toResponse() })
        }

        patch("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<UpdateAppRequest>()
            val app =
                io {
                    apps.update(
                        organization = context.organization,
                        actor = context.actor,
                        id = call.appIdParam(),
                        draft =
                            AppDraft(
                                name = request.name,
                                gpReportingBucket = request.gpReportingBucket,
                                locale = request.locale,
                                timezone = request.timezone,
                                notifyFrom = request.notifyFrom,
                                aiInstructions = request.aiInstructions,
                                ingestIntervalMinutes = request.ingestIntervalMinutes,
                                dailyDigestAt = request.dailyDigestAt,
                                enabled = request.enabled,
                            ),
                    )
                }
            call.respond(app.toResponse())
        }

        delete("/{app}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { apps.delete(context.organization, context.actor, call.appIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun App.toResponse() =
    AppResponse(
        id = id.toString(),
        name = name,
        gpPackageName = gpPackageName,
        gpReportingBucket = gpReportingBucket,
        ascAppId = ascAppId,
        platforms = platforms().toList(),
        locale = locale,
        timezone = timezone,
        notifyFrom = notifyFrom?.toString(),
        aiInstructions = aiInstructions,
        ingestIntervalMinutes = ingestIntervalMinutes,
        dailyDigestAt = dailyDigestAt.toString(),
        enabled = enabled,
    )
