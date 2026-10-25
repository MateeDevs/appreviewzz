package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.port.OnboardingProgress
import cz.matee.appreviewzz.core.port.OnboardingSync
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.PlatformIngest
import cz.matee.appreviewzz.core.usecase.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class OnboardingResponse(
    val appId: String? = null,
    val step: String = "app",
    val preferencesConfirmed: Boolean = false,
    val completed: Boolean = false,
    val started: Boolean = false,
)

@Serializable
data class OnboardingSyncResponse(
    val platform: String,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val fetched: Int?,
    val message: String?,
)

fun Route.onboardingRoutes(console: ConsoleWiring) {
    val repository = console.onboarding ?: return
    route("/orgs/{org}/onboarding") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            val progress = io { repository.find(context.organization.id, context.actor.userId) }
            call.respond(progress?.response() ?: OnboardingResponse())
        }
        put {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val request = call.receive<OnboardingResponse>()
            if (request.step !in setOf("app", "connect", "preferences", "extras", "sync")) {
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neznámý krok průvodce")
            }
            val appId =
                request.appId?.let { value ->
                    runCatching {
                        AppId.parse(
                            value,
                        )
                    }.getOrElse { throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Neplatná aplikace") }
                }
            val app = appId?.let { io { console.apps.get(context.organization.id, it) } }
            if (app == null &&
                (request.step != "app" || request.preferencesConfirmed)
            ) {
                throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Nejdřív vyberte aplikaci")
            }
            if (app?.competitor == true) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Vyberte vlastní aplikaci")
            if (request.completed) {
                val setup = app?.let { io { console.appSetup.of(it) } }
                val connected =
                    app != null &&
                        setup != null &&
                        app.platforms().any {
                            it !in setup.platformsWithoutKey && it !in setup.platformsWaitingForKey
                        }
                if (!connected || !app.enabled || !request.preferencesConfirmed) {
                    throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Nejdřív ověřte alespoň jeden store a potvrďte preference")
                }
            }
            val progress = OnboardingProgress(appId, request.step, request.preferencesConfirmed, request.completed)
            io { repository.save(context.organization.id, context.actor.userId, progress) }
            call.respond(progress.response())
        }
        get("/{app}/sync") {
            val context = call.orgContext(console.organizations, console.memberships)
            val app = call.appIdParam()
            io { console.apps.get(context.organization.id, app) }
            call.respond(io { repository.syncs(context.organization.id, app).map { it.response() } })
        }
        post("/{app}/sync") {
            val context = call.orgContext(console.organizations, console.memberships)
            requireRole(context.actor, OrgRole.ADMIN)
            val app = io { console.apps.get(context.organization.id, call.appIdParam()) }
            if (app.competitor || !app.enabled) throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Vyberte zapnutou vlastní aplikaci")
            val ingest =
                console.onboardingIngest
                    ?: throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Synchronizace v tomto procesu není dostupná")
            val started = console.clock.now()
            if (!io { repository.startSync(context.organization.id, app.id, app.platforms(), started) }) {
                call.respond(HttpStatusCode.Accepted, io { repository.syncs(context.organization.id, app.id).map { it.response() } })
                return@post
            }
            // Odchod z browseru nesmí nechat trvale RUNNING ani přerušit právě ukládané recenze.
            withContext(NonCancellable) {
                try {
                    val report = withTimeout(120.seconds) { ingest.ingest(context.organization.id, app.id) }
                    io {
                        report.platforms.forEach { result ->
                            val sync =
                                when (result) {
                                    is PlatformIngest.Ingested ->
                                        OnboardingSync(
                                            result.platform,
                                            "SUCCEEDED",
                                            started,
                                            console.clock.now(),
                                            result.fetched,
                                        )
                                    is PlatformIngest.Failed ->
                                        OnboardingSync(
                                            result.platform,
                                            "FAILED",
                                            started,
                                            console.clock.now(),
                                            message = result.message,
                                        )
                                    is PlatformIngest.Skipped ->
                                        OnboardingSync(
                                            result.platform,
                                            "BLOCKED",
                                            started,
                                            console.clock.now(),
                                            message = "Chybí klíč nebo konektor storu.",
                                        )
                                }
                            repository.finishSync(context.organization.id, app.id, sync)
                        }
                    }
                } catch (error: Exception) {
                    io {
                        app.platforms().forEach {
                            repository.finishSync(
                                context.organization.id,
                                app.id,
                                OnboardingSync(
                                    it,
                                    "FAILED",
                                    started,
                                    console.clock.now(),
                                    message = "Synchronizace se nepodařila. Zkuste ji znovu.",
                                ),
                            )
                        }
                    }
                }
            }
            call.respond(io { repository.syncs(context.organization.id, app.id).map { it.response() } })
        }
    }
}

private fun OnboardingProgress.response() = OnboardingResponse(appId?.toString(), step, preferencesConfirmed, completed, started = true)

private fun OnboardingSync.response() =
    OnboardingSyncResponse(platform.name, status, startedAt.toString(), finishedAt?.toString(), fetched, message)
