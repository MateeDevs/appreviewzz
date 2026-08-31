package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.OpaqueTokens
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.usecase.AuthenticatedUser
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.MfaService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.application
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
    val child = (this as RoutingNode).createChild(TransparentSelector())
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

/**
 * Podstrom cest platformní správy (F7.1). Kromě session chce dvě věci navíc:
 *
 * - **roli `SUPERADMIN`** — kdo ji nemá, dostane `404`, ne `403`. Stejná úvaha jako u cizí
 *   organizace: existence sekce není nic, co by se mělo dát zjistit hádáním adres.
 * - **zapnutý druhý faktor** — účet, který drží platformní tajemství a stropy pro všechny
 *   klienty, je poslední, který má stát na samotném hesle (ADR 0015, ADR 0018). Tady `403`
 *   s vlastním kódem: uživatel má vědět, co udělat, a console ho pošle na zabezpečení.
 *
 * Ochrana visí na stromě, ne v handlerech — nová sekce se tak nemůže zapomenout zeptat.
 */
fun Route.requirePlatformAdmin(
    mfa: MfaService?,
    build: Route.() -> Unit,
): Route {
    val child = (this as RoutingNode).createChild(TransparentSelector())
    child.intercept(ApplicationCallPipeline.Plugins) {
        val user = call.authenticatedUser.account.user
        if (user.platformRole != PlatformRole.SUPERADMIN) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", call.callId))
            return@intercept finish()
        }
        if (mfa == null || !io { mfa.isEnabled(user.id) }) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(
                    error = "platform_mfa_required",
                    requestId = call.callId,
                    message = "Správa platformy potřebuje zapnutý druhý faktor — zapni si ho v zabezpečení účtu",
                ),
            )
            return@intercept finish()
        }
    }
    child.build()
    return child
}

/** Nový CSRF token. Náhoda z téhož zdroje jako session token — nic slabšího tu nemá co dělat. */
fun newCsrfToken(): SecretPayload = OpaqueTokens.generate()

/**
 * Uzel, který na cestu nesahá — je tu jen kvůli tomu, aby na něm mohl viset interceptor.
 *
 * **Instance na každé volání, ne singleton.** `createChild` vrací existující uzel se stejným
 * selektorem, takže sdílená instance by slepila dva nezávislé podstromy do jednoho: přihlašovací
 * endpointy by se ocitly pod `requireSession` a odpovídaly by `401` i na správné heslo.
 */
internal class TransparentSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Odkud vzít adresu klienta. Nastavuje se jednou při sestavení modulu, protože správná
 * odpověď závisí na tom, co běží před námi — a špatná se pozná až tím, že limity buď
 * nechytí nikoho, nebo všechny najednou.
 */
class ClientAddress(
    private val trustedProxyHops: Int,
) {
    /**
     * `X-Forwarded-For` je seznam, do kterého každý skok **připisuje na konec** adresu,
     * ze které k němu požadavek přišel. Klient si klidně pošle vlastní hlavičku, ale ovlivnit
     * umí jen její začátek — proto se bere `hops`-tá položka od konce, tedy ta, kterou tam
     * napsala naše proxy. (Do F5 se brala první; ta se dá nastavit z prohlížeče, takže limit
     * i výpis relací šlo obelhat jedním headerem.)
     */
    fun of(call: ApplicationCall): String? {
        val peer = call.request.origin.remoteHost
        if (trustedProxyHops <= 0) return peer
        val chain =
            call.request
                .header(FORWARDED_FOR)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        if (chain.isEmpty()) return peer
        return chain.getOrNull((chain.size - trustedProxyHops).coerceAtLeast(0)) ?: peer
    }

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"
    }
}

private val ClientAddressKey = AttributeKey<ClientAddress>("appreviewzz.clientAddress")

fun Application.installClientAddress(trustedProxyHops: Int) {
    attributes.put(ClientAddressKey, ClientAddress(trustedProxyHops))
}

/** Adresa klienta pro limity a pro výpis relací. */
fun ApplicationCall.clientIp(): String? =
    application.attributes
        .getOrNull(ClientAddressKey)
        ?.of(this)
        ?: request.origin.remoteHost

/** Porovnání, které neprozradí délku shody dobou běhu — u CSRF tokenu je to zvyk, ne luxus. */
private fun constantTimeEquals(
    left: String,
    right: String,
): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8),
    )
