package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.usecase.AuthException
import cz.matee.appreviewzz.core.usecase.AuthFailure
import cz.matee.appreviewzz.core.usecase.AuthenticatedUser
import cz.matee.appreviewzz.core.usecase.LoginResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class EmailRequest(
    val email: String,
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val password: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class SecondFactorRequest(
    val challenge: String,
    val code: String,
)

@Serializable
data class CodeRequest(
    val code: String,
)

@Serializable
data class DisableTotpRequest(
    val password: String,
    val code: String,
)

/**
 * Odpověď na přihlášení, které ještě není hotové. Vrací se s `202 Accepted` — console podle
 * kódu pozná, že má ukázat pole na kód, aniž by musela hádat z těla.
 */
@Serializable
data class SecondFactorChallenge(
    val challenge: String,
    val expiresAt: String,
)

@Serializable
data class TotpSetupResponse(
    /** Base32 tajemství pro ruční opsání; QR kód si console vyrobí z [provisioningUri]. */
    val secret: String,
    val provisioningUri: String,
)

@Serializable
data class RecoveryCodesResponse(
    val codes: List<String>,
)

@Serializable
data class MfaStatusResponse(
    val enabled: Boolean,
    val setupPending: Boolean,
    val remainingRecoveryCodes: Int,
)

@Serializable
data class CsrfResponse(
    val token: String,
)

@Serializable
data class OrganizationSummary(
    val id: String,
    val slug: String,
    val name: String,
    val role: OrgRole,
)

/** Co o sobě přihlášený vidí. Nikdy tu není hash hesla ani nic z vaultu. */
@Serializable
data class MeResponse(
    val id: String,
    val email: String,
    val displayName: String?,
    val emailVerified: Boolean,
    val mfaEnabled: Boolean,
    val organizations: List<OrganizationSummary>,
)

/**
 * Přihlášení do console (F3.1).
 *
 * Session je náhodný token v `httpOnly` cookie — ne JWT. Důvod je provozní: odhlášení
 * a „zruš všechny relace po změně hesla" musí platit okamžitě, což se s podepsaným
 * tokenem bez serverového seznamu nedá udělat jinak než blocklistem, tedy stejnou
 * tabulkou, jen složitěji.
 */
fun Route.authRoutes(
    console: ConsoleWiring,
    limits: RateLimits = RateLimits.disabled(),
) {
    val auth = console.auth
    val cookies = console.cookies
    val organizations = console.organizations
    val memberships = console.memberships

    route("/auth") {
        // Console si o token řekne dřív, než ukáže formulář — tím je proti CSRF chráněné
        // i samotné přihlášení (útočník by jinak uměl přihlásit oběť na svůj účet).
        // Zůstává mimo přísný limit: patří ke každému formuláři a nic neprozradí.
        get("/csrf") {
            val existing = call.request.cookies[CSRF_COOKIE]?.takeIf { it.isNotBlank() }
            val token = existing?.let(::SecretPayload) ?: newCsrfToken()
            cookies.issueCsrf(call, token)
            call.respond(CsrfResponse(token.value))
        }

        // Přísnější limit než na zbytku API: tudy se hádají hesla a každý pokus stojí
        // jeden argon2.
        rateLimitedGroup(limits.auth) {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                val registration =
                    io {
                        auth.register(
                            email = request.email,
                            displayName = request.displayName,
                            password = SecretPayload(request.password),
                            userAgent = call.request.header("User-Agent"),
                            clientIp = call.clientIp(),
                        )
                    }
                // Registrace rovnou přihlašuje; potvrzení e-mailu si console vyžádá až u kroku,
                // který ho opravdu potřebuje (založení organizace).
                cookies.issue(call, registration.token)
                cookies.issueCsrf(call, newCsrfToken())
                call.respond(
                    HttpStatusCode.Created,
                    me(registration.user, emailVerified = false, organizations, memberships),
                )
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                // Druhý klíč vedle adresy: cílový účet. Bez něj by pomalý pokus rozprostřený
                // po adresách prošel, protože každá adresa má vlastní kbelík.
                if (!call.allowedBy(limits.authIdentity, identityKey("login", request.email))) return@post
                val result =
                    io {
                        auth.login(
                            email = request.email,
                            password = SecretPayload(request.password),
                            userAgent = call.request.header("User-Agent"),
                            clientIp = call.clientIp(),
                        )
                    }

                when (result) {
                    is LoginResult.Success -> call.respondSignedIn(result, console)

                    // Heslo sedí, chybí kód z appky. Relace ani cookie zatím nevzniká — 202
                    // říká „přijato, ale ještě to není hotové", což je přesně tenhle stav.
                    is LoginResult.SecondFactorRequired ->
                        call.respond(
                            HttpStatusCode.Accepted,
                            SecondFactorChallenge(result.challenge.value, result.expiresAt.toString()),
                        )

                    LoginResult.InvalidCredentials ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("invalid_credentials", call.callId, "E-mail nebo heslo nesouhlasí"),
                        )

                    is LoginResult.Locked ->
                        call.respond(
                            HttpStatusCode.Locked,
                            ErrorResponse(
                                error = "account_locked",
                                requestId = call.callId,
                                message = "Po sérii špatných hesel je účet dočasně zamčený, zkus to za chvíli",
                            ),
                        )
                }
            }

            /**
             * Druhá půlka přihlášení. Klíč limitu je challenge, ne adresa: bez toho by hádání
             * šestimístného kódu jelo pod společným stropem s běžnými pokusy o heslo.
             */
            post("/mfa/verify") {
                val request = call.receive<SecondFactorRequest>()
                if (!call.allowedBy(limits.authIdentity, identityKey("mfa", request.challenge))) return@post
                val result =
                    io {
                        auth.completeSecondFactor(
                            challenge = SecretPayload(request.challenge),
                            code = request.code,
                            userAgent = call.request.header("User-Agent"),
                            clientIp = call.clientIp(),
                        )
                    }
                when (result) {
                    is LoginResult.Success -> call.respondSignedIn(result, console)
                    else ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("invalid_code", call.callId, "Kód nesouhlasí nebo přihlášení vypršelo"),
                        )
                }
            }

            post("/logout") {
                call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }?.let { token ->
                    io { auth.logout(SecretPayload(token)) }
                }
                cookies.clear(call)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/email/verify") {
                val request = call.receive<TokenRequest>()
                io { auth.verifyEmail(SecretPayload(request.token)) }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/password/forgot") {
                val request = call.receive<EmailRequest>()
                // Limit na e-mail, ne jen na adresu: jinak by šlo cizí schránku zaplavit odkazy.
                if (!call.allowedBy(limits.authIdentity, identityKey("forgot", request.email))) return@post
                io { auth.requestPasswordReset(request.email) }
                // Vždy stejná odpověď: jinak by formulář prozradil, které e-maily u nás jsou.
                call.respond(HttpStatusCode.Accepted)
            }

            post("/password/reset") {
                val request = call.receive<ResetPasswordRequest>()
                io { auth.resetPassword(SecretPayload(request.token), SecretPayload(request.password)) }
                // Relace padly všechny, včetně té v tomhle prohlížeči — ať se člověk přihlásí nanovo.
                cookies.clear(call)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        requireSession(auth) {
            get("/me") {
                val user = call.authenticatedUser
                call.respond(
                    me(
                        user = user.account.user,
                        emailVerified = user.account.emailVerified,
                        organizations = organizations,
                        memberships = memberships,
                        mfaEnabled = io { console.mfa?.isEnabled(user.account.user.id) } == true,
                    ),
                )
            }

            post("/email/resend") {
                io { auth.resendVerification(call.authenticatedUser.account.user.id) }
                call.respond(HttpStatusCode.Accepted)
            }

            get("/totp") {
                call.respond(mfaStatus(console, call.authenticatedUser.account.user.id))
            }

            /**
             * Nové tajemství. Tajemství tu opouští server naposledy v otevřené podobě —
             * console ho po potvrzení zahodí a znovu ho nikdo nezjistí.
             */
            post("/totp/setup") {
                val service = console.mfa ?: return@post call.respondMfaUnavailable()
                val setup = io { service.startSetup(call.authenticatedUser.account.user) }
                call.respond(TotpSetupResponse(setup.secret.value, setup.provisioningUri))
            }

            post("/totp/confirm") {
                val service = console.mfa ?: return@post call.respondMfaUnavailable()
                val request = call.receive<CodeRequest>()
                val user = call.authenticatedUser.account.user
                val codes = io { service.confirmSetup(user.id, request.code) }
                call.respond(RecoveryCodesResponse(codes))
            }

            /** Nové záchranné kódy. Ty staré padají — proto se chce kód z appky, ne jen relace. */
            post("/totp/recovery-codes") {
                val service = console.mfa ?: return@post call.respondMfaUnavailable()
                val request = call.receive<CodeRequest>()
                val user = call.authenticatedUser.account.user
                val codes =
                    io {
                        if (!service.verify(user.id, request.code)) {
                            throw AuthException(AuthFailure.MFA_INVALID_CODE, "Kód nesouhlasí")
                        }
                        service.regenerateRecoveryCodes(user.id)
                    }
                call.respond(RecoveryCodesResponse(codes))
            }

            /**
             * Vypnutí. Chce heslo **i** platný kód: ukradená relace tak druhý faktor nesundá,
             * a to je jediný důvod, proč tu vůbec je.
             */
            post("/totp/disable") {
                val service = console.mfa ?: return@post call.respondMfaUnavailable()
                val request = call.receive<DisableTotpRequest>()
                val user: AuthenticatedUser = call.authenticatedUser
                io {
                    auth.reauthenticate(user, SecretPayload(request.password))
                    if (!service.verify(user.account.user.id, request.code)) {
                        throw AuthException(AuthFailure.MFA_INVALID_CODE, "Kód nesouhlasí")
                    }
                    service.disable(user.account.user.id)
                }
                logger.info { "Uživatel ${user.account.user.id} vypnul druhý faktor" }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/password/change") {
                val request = call.receive<ChangePasswordRequest>()
                val user: AuthenticatedUser = call.authenticatedUser
                io {
                    auth.changePassword(
                        user = user,
                        currentPassword = SecretPayload(request.currentPassword),
                        newPassword = SecretPayload(request.newPassword),
                    )
                }
                logger.info { "Uživatel ${user.account.user.id} si změnil heslo" }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun me(
    user: User,
    emailVerified: Boolean,
    organizations: OrganizationRepository,
    memberships: MembershipRepository,
    mfaEnabled: Boolean = false,
): MeResponse {
    val summaries =
        io {
            memberships.listByUser(user.id).mapNotNull { membership ->
                organizations.findById(membership.orgId)?.let { organization ->
                    OrganizationSummary(
                        id = organization.id.toString(),
                        slug = organization.slug,
                        name = organization.name,
                        role = membership.role,
                    )
                }
            }
        }
    return MeResponse(
        id = user.id.toString(),
        email = user.email,
        displayName = user.displayName,
        emailVerified = emailVerified,
        mfaEnabled = mfaEnabled,
        organizations = summaries,
    )
}

/**
 * Klíč limitu pro konkrétní účet. Hashuje se, aby se e-maily neválely v paměti procesu ani
 * v případném výpisu — na porovnávání to stačí a zpátky se z toho nic nepřečte.
 */
private fun identityKey(
    scope: String,
    email: String,
): String {
    val normalized = email.trim().lowercase()
    val digest = MessageDigest.getInstance("SHA-256").digest("$scope:$normalized".toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
}

/** Vydání relace po úspěšném přihlášení — jedno místo pro obě jeho půlky. */
private suspend fun ApplicationCall.respondSignedIn(
    result: LoginResult.Success,
    console: ConsoleWiring,
) {
    console.cookies.issue(this, result.token)
    console.cookies.issueCsrf(this, newCsrfToken())
    respond(
        me(
            user = result.account.user,
            emailVerified = result.account.emailVerified,
            organizations = console.organizations,
            memberships = console.memberships,
            mfaEnabled = io { console.mfa?.isEnabled(result.account.user.id) } == true,
        ),
    )
}

private suspend fun mfaStatus(
    console: ConsoleWiring,
    userId: UserId,
): MfaStatusResponse {
    val status = io { console.mfa?.status(userId) }
    return MfaStatusResponse(
        enabled = status?.enabled == true,
        setupPending = status?.setupPending == true,
        remainingRecoveryCodes = status?.remainingRecoveryCodes ?: 0,
    )
}

/**
 * Instalace bez správce klíčů (a tedy bez místa, kam tajemství bezpečně uložit). Vlastní kód,
 * ne 404 — console tak umí říct proč, místo aby tlačítko jen nefungovalo.
 */
private suspend fun ApplicationCall.respondMfaUnavailable() {
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(
            error = "mfa_unavailable",
            requestId = callId,
            message = "Druhý faktor potřebuje nastavený správce klíčů (VAULT_KEK_URI)",
        ),
    )
}
