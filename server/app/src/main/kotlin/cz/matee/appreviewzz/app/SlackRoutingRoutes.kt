package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.channels.slack.SlackEnvironment
import cz.matee.appreviewzz.channels.slack.SlackMessageRouting
import cz.matee.appreviewzz.channels.slack.SlackRelayAuthentication
import cz.matee.appreviewzz.channels.slack.SlackReplySubmission
import cz.matee.appreviewzz.jobs.SlackRelayJobData
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private val relayJson = Json { ignoreUnknownKeys = true }

class SlackRoutingException(
    val status: HttpStatusCode,
    message: String,
) : RuntimeException(message)

class SlackInteractionDispatcher(
    val environment: SlackEnvironment,
    val routing: SlackMessageRouting,
    val authentication: SlackRelayAuthentication,
    private val intake: SlackReplyIntake,
    private val remoteTargets: Set<SlackEnvironment>,
    private val enqueueRelay: (SlackRelayJobData) -> Boolean,
) {
    fun accept(
        submission: SlackReplySubmission,
        deliveryId: String,
    ): IntakeResult {
        if (submission.text.isBlank()) return IntakeResult.EMPTY
        val value = submission.routeValue
        // Zprávy vytvořené před zavedením routingu se dohledávají jen v místní DB.
        if (!SlackMessageRouting.isRouted(value)) return intake.accept(submission)
        val route =
            routing.verify(value.orEmpty())
                ?: throw SlackRoutingException(HttpStatusCode.BadRequest, "Neplatné směrování Slack zprávy")
        if (route.environment == environment) return intake.accept(submission, route)
        if (route.environment !in remoteTargets) {
            throw SlackRoutingException(HttpStatusCode.ServiceUnavailable, "Cíl Slack interakce není nastavený")
        }
        val queued = enqueueRelay(SlackRelayJobData(route.environment.wireName, deliveryId, relayJson.encodeToString(submission)))
        return if (queued) IntakeResult.FORWARDED else IntakeResult.DUPLICATE
    }

    fun acceptRelay(payload: String): IntakeResult {
        val submission =
            runCatching { relayJson.decodeFromString<SlackReplySubmission>(payload) }.getOrNull()
                ?: throw SlackRoutingException(HttpStatusCode.BadRequest, "Neplatná relay odpověď")
        val route =
            routing.verify(submission.routeValue.orEmpty())
                ?: throw SlackRoutingException(HttpStatusCode.BadRequest, "Chybí platné směrování")
        if (route.environment != environment) throw SlackRoutingException(HttpStatusCode.BadRequest, "Nesprávné cílové prostředí")
        // Relayed požadavek se znovu nepřeposílá: chybná konfigurace nemůže vytvořit smyčku.
        return intake.accept(submission, route)
    }
}

fun Application.slackRelayRoutes(
    dispatcher: SlackInteractionDispatcher,
    limits: RateLimits = RateLimits.disabled(),
    replay: ReplayGuard = ReplayGuard("slack-relay", ReplayGuard.SLACK_RETENTION),
) {
    routing {
        route(SlackRelayAuthentication.PATH) {
            rateLimited(limits.webhook)
            post {
                val body = call.receiveText()
                val id = call.request.header(SlackRelayAuthentication.DELIVERY_ID)
                if (call.request.header(SlackRelayAuthentication.ENVIRONMENT) != dispatcher.environment.wireName ||
                    !dispatcher.authentication.verify(
                        dispatcher.environment,
                        id,
                        call.request.header(SlackRelayAuthentication.TIMESTAMP),
                        body,
                        call.request.header(SlackRelayAuthentication.SIGNATURE),
                    )
                ) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                if (!replay.firstSighting(id.orEmpty())) {
                    call.respond(HttpStatusCode.OK)
                    return@post
                }
                try {
                    when (dispatcher.acceptRelay(body)) {
                        IntakeResult.UNKNOWN_MESSAGE -> {
                            replay.forget(id.orEmpty())
                            call.respond(HttpStatusCode.UnprocessableEntity)
                        }
                        IntakeResult.EMPTY -> call.respond(HttpStatusCode.BadRequest)
                        else -> call.respond(HttpStatusCode.OK)
                    }
                } catch (error: SlackRoutingException) {
                    replay.forget(id.orEmpty())
                    call.respond(error.status)
                } catch (error: Exception) {
                    replay.forget(id.orEmpty())
                    throw error
                }
            }
        }
    }
}
