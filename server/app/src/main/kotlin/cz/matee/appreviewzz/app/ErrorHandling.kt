package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.usecase.AuthException
import cz.matee.appreviewzz.core.usecase.AuthFailure
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
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
        exception<ConsoleException> { call, cause ->
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
        AuthFailure.EMAIL_TAKEN, AuthFailure.MFA_ALREADY_ENABLED -> HttpStatusCode.Conflict
        AuthFailure.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized
        AuthFailure.ACCOUNT_LOCKED -> HttpStatusCode.Locked
        // Špatně opsaný kód je vstupní chyba, ne „nejsi přihlášený" — console podle toho
        // nechá formulář otevřený místo odhlášení.
        AuthFailure.MFA_INVALID_CODE, AuthFailure.MFA_NOT_SET_UP -> HttpStatusCode.BadRequest
    }

private fun ConsoleFailure.status(): HttpStatusCode =
    when (this) {
        ConsoleFailure.INVALID_INPUT -> HttpStatusCode.BadRequest
        // Ověření e-mailu chybí, ne oprávnění: 403 s vlastním kódem, ať console ví, co nabídnout.
        ConsoleFailure.EMAIL_NOT_VERIFIED, ConsoleFailure.FORBIDDEN -> HttpStatusCode.Forbidden
        ConsoleFailure.SLUG_TAKEN, ConsoleFailure.LAST_OWNER -> HttpStatusCode.Conflict
        ConsoleFailure.NOT_FOUND -> HttpStatusCode.NotFound
        ConsoleFailure.INVITATION_INVALID -> HttpStatusCode.BadRequest
    }
