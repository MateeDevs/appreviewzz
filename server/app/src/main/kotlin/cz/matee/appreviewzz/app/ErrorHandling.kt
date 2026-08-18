package cz.matee.appreviewzz.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class ErrorResponse(
    val error: String,
    val requestId: String? = null,
)

/**
 * Ven jde vždy jen neutrální hláška + requestId; detail zůstává v logu.
 * Interní chyby nesmí prosáknout do odpovědi (mohly by nést hodnoty credentials).
 */
fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception for ${call.request.local.uri}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "internal_error", requestId = call.callId),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse(error = "not_found", requestId = call.callId))
        }
    }
}
