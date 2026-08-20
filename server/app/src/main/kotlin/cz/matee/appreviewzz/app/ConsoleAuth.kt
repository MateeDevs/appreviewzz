package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.OpaqueTokens
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.usecase.AuthenticatedUser
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import kotlin.time.Duration

const val SESSION_COOKIE = "arz_session"
const val CSRF_COOKIE = "arz_csrf"
const val CSRF_HEADER = "X-CSRF-Token"

private val AuthenticatedUserKey = AttributeKey<AuthenticatedUser>("appreviewzz.user")

/**
 * Nastavení cookies. `secure` je vypnuté jen pro lokální běh na http — v produkci se
 * odvozuje z toho, že veřejná adresa je https, takže se na to nedá zapomenout.
 */
class SessionCookies(
    private val secure: Boolean,
    private val lifetime: Duration,
) {
    fun issue(
        call: ApplicationCall,
        token: SecretPayload,
    ) {
        call.response.cookies.append(
            name = SESSION_COOKIE,
            value = token.value,
            httpOnly = true,
            secure = secure,
            path = "/",
            maxAge = lifetime.inWholeSeconds,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    /** CSRF token čte JavaScript console a posílá ho zpátky v hlavičce (double submit). */
    fun issueCsrf(
        call: ApplicationCall,
        token: SecretPayload,
    ) {
        call.response.cookies.append(
            name = CSRF_COOKIE,
            value = token.value,
            httpOnly = false,
            secure = secure,
            path = "/",
            maxAge = lifetime.inWholeSeconds,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    fun clear(call: ApplicationCall) {
        listOf(SESSION_COOKIE, CSRF_COOKIE).forEach { name ->
            call.response.cookies.append(
                name = name,
                value = "",
                httpOnly = name == SESSION_COOKIE,
                secure = secure,
                path = "/",
                maxAge = 0,
                extensions = mapOf("SameSite" to "Lax"),
            )
        }
    }
}

/** Uživatel z session cookie. Volá se až uvnitř [requireSession], jinde je to chyba programátora. */
val ApplicationCall.authenticatedUser: AuthenticatedUser
    get() =
        attributes.getOrNull(AuthenticatedUserKey)
            ?: error("Cesta ${request.local.uri} běží mimo requireSession, ale ptá se po uživateli")

/**
 * Ochrana proti CSRF pro měnící metody: hodnota z cookie se musí shodovat s hlavičkou.
 *
 * Cizí stránka umí prohlížeč donutit poslat naši cookie, ale hlavičku nastavit neumí
 * (same-origin policy) a hodnotu cookie si nepřečte. `SameSite=Lax` je druhá vrstva —
 * tohle je ta, která drží i u starých prohlížečů a u `POST` z podomény.
 */
fun Route.requireCsrf() {
    (this as RoutingNode).intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.httpMethod !in SAFE_METHODS) {
            val cookie = call.request.cookies[CSRF_COOKIE]
            val header = call.request.header(CSRF_HEADER)
            if (cookie.isNullOrBlank() || header.isNullOrBlank() || !constantTimeEquals(cookie, header)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("csrf_failed", call.callId))
                return@intercept finish()
            }
        }
    }
}

/**
 * Podstrom cest, který bez platné session neexistuje. Nepřihlášený dostane 401 dřív,
 * než se doběhne k obsluze — takže se nemůže stát, že by nějaký handler zapomněl zeptat.
 */
fun Route.requireSession(
    auth: AuthenticationService,
    build: Route.() -> Unit,
): Route {
    val child = (this as RoutingNode).createChild(TransparentSelector)
    child.intercept(ApplicationCallPipeline.Plugins) {
        val token =
            call.request.cookies[SESSION_COOKIE]
                ?.takeIf { it.isNotBlank() }
                ?.let(::SecretPayload)
        val user = token?.let { io { auth.authenticate(it) } }
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", call.callId))
            return@intercept finish()
        }
        call.attributes.put(AuthenticatedUserKey, user)
    }
    child.build()
    return child
}

/** Nový CSRF token. Náhoda z téhož zdroje jako session token — nic slabšího tu nemá co dělat. */
fun newCsrfToken(): SecretPayload = OpaqueTokens.generate()

private object TransparentSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Adresa klienta pro výpis relací. Za reverzní proxy je pravda v `X-Forwarded-For`;
 * bereme první položku, tedy toho, kdo se opravdu připojil k proxy.
 */
fun ApplicationCall.clientIp(): String? {
    val forwarded =
        request
            .header("X-Forwarded-For")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
    return forwarded?.takeIf { it.isNotBlank() } ?: request.origin.remoteHost
}

/** Porovnání, které neprozradí délku shody dobou běhu — u CSRF tokenu je to zvyk, ne luxus. */
private fun constantTimeEquals(
    left: String,
    right: String,
): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8),
    )
