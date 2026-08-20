package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.usecase.AuthException
import cz.matee.appreviewzz.core.usecase.AuthFailure
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
    /**
     * Věta pro člověka v consoli. Plní se **jen** u chyb, které jsme sami pojmenovali
     * (validace, špatné heslo) — nikdy z výjimky, ta by mohla nést hodnoty credentials.
     */
    val message: String? = null,
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
        exception<AuthException> { call, cause ->
            call.respond(
                cause.failure.status(),
                ErrorResponse(
                    error = cause.failure.name.lowercase(),
                    requestId = call.callId,
                    message = cause.message,
                ),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse(error = "not_found", requestId = call.callId))
        }
    }
}

/**
 * Mapa doménových důvodů na stavové kódy. `423 Locked` je schválně jiný kód než `401` —
 * console podle něj pozná, že nemá vyzývat k dalšímu pokusu.
 */
private fun AuthFailure.status(): HttpStatusCode =
    when (this) {
        AuthFailure.INVALID_EMAIL, AuthFailure.WEAK_PASSWORD, AuthFailure.INVALID_TOKEN -> HttpStatusCode.BadRequest
        AuthFailure.EMAIL_TAKEN -> HttpStatusCode.Conflict
        AuthFailure.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized
        AuthFailure.ACCOUNT_LOCKED -> HttpStatusCode.Locked
    }
