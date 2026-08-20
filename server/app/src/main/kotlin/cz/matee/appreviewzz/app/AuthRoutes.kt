package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.usecase.AuthenticatedUser
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.LoginResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

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
data class TokenRequest(
    val token: String,
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
fun Application.authRoutes(
    auth: AuthenticationService,
    cookies: SessionCookies,
    organizations: OrganizationRepository,
    memberships: MembershipRepository,
) {
    routing {
        route("/api/auth") {
            requireCsrf()

            // Console si o token řekne dřív, než ukáže formulář — tím je proti CSRF chráněné
            // i samotné přihlášení (útočník by jinak uměl přihlásit oběť na svůj účet).
            get("/csrf") {
                val existing = call.request.cookies[CSRF_COOKIE]?.takeIf { it.isNotBlank() }
                val token = existing?.let(::SecretPayload) ?: newCsrfToken()
                cookies.issueCsrf(call, token)
                call.respond(CsrfResponse(token.value))
            }

            post("/register") {
                val request = call.receive<RegisterRequest>()
                val user =
                    io {
                        auth.register(
                            email = request.email,
                            displayName = request.displayName,
                            password = SecretPayload(request.password),
                        )
                    }
                call.respond(HttpStatusCode.Created, me(user, emailVerified = false, organizations, memberships))
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
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
                    is LoginResult.Success -> {
                        cookies.issue(call, result.token)
                        cookies.issueCsrf(call, newCsrfToken())
                        call.respond(
                            me(
                                user = result.account.user,
                                emailVerified = result.account.emailVerified,
                                organizations = organizations,
                                memberships = memberships,
                            ),
                        )
                    }

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

            requireSession(auth) {
                get("/me") {
                    val user = call.authenticatedUser
                    call.respond(
                        me(user.account.user, user.account.emailVerified, organizations, memberships),
                    )
                }

                post("/email/resend") {
                    io { auth.resendVerification(call.authenticatedUser.account.user.id) }
                    call.respond(HttpStatusCode.Accepted)
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
}

private suspend fun me(
    user: User,
    emailVerified: Boolean,
    organizations: OrganizationRepository,
    memberships: MembershipRepository,
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
        organizations = summaries,
    )
}
