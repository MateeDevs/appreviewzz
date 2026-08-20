package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.MembershipRepository
import cz.matee.appreviewzz.core.port.OrganizationRepository
import cz.matee.appreviewzz.core.usecase.AppService
import cz.matee.appreviewzz.core.usecase.AuthenticationService
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.OrgActor
import cz.matee.appreviewzz.core.usecase.OrganizationService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Co console potřebuje. Pohromadě schválně: routy si dokládají závislosti odsud, takže
 * `apiModule` nemá deset volitelných parametrů a přibývání sekcí nemění jeho podpis.
 */
class ConsoleWiring(
    val auth: AuthenticationService,
    val orgs: OrganizationService,
    val apps: AppService,
    val cookies: SessionCookies,
    val organizations: OrganizationRepository,
    val memberships: MembershipRepository,
)

/**
 * Celé API console pod `/api`.
 *
 * Ochrana je nasazená **na stromě cest**, ne v jednotlivých handlerech: CSRF na všem, co
 * mění stav, a session na všem kromě přihlašovacích endpointů. Nová sekce tak nemůže
 * zapomenout zeptat se, kdo volá.
 */
fun Application.consoleRoutes(console: ConsoleWiring) {
    routing {
        route("/api") {
            requireCsrf()
            authRoutes(console)

            requireSession(console.auth) {
                orgRoutes(console)
                appRoutes(console)
            }
        }
    }
}

/** Organizace z adresy plus role přihlášeného v ní. Nečlen tu končí na 404. */
class OrgContext(
    val organization: Organization,
    val actor: OrgActor,
) {
    val role: OrgRole get() = actor.role
}

/**
 * Organizace se v adrese identifikuje **slugem**, ne UUID — je to část odkazu, kterou lidé
 * posílají mezi sebou. Kdo v organizaci není, dostane 404, ne 403: jinak by se dalo hádáním
 * adres zjistit, kdo je náš zákazník.
 */
suspend fun ApplicationCall.orgContext(
    organizations: OrganizationRepository,
    memberships: MembershipRepository,
): OrgContext {
    val slug = parameters["org"].orEmpty()
    val user = authenticatedUser.account.user
    return io {
        val organization =
            organizations.findBySlug(slug)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Organizace '$slug' neexistuje")
        val role =
            memberships.roleOf(organization.id, user.id)
                ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Organizace '$slug' neexistuje")
        OrgContext(organization, OrgActor(user.id, role, user.displayName ?: user.email))
    }
}

fun ApplicationCall.userIdParam(): UserId = UserId(uuidParam("userId", "Takový člen tu není"))

fun ApplicationCall.appIdParam(): AppId = AppId(uuidParam("app", "Taková aplikace tu není"))

private fun ApplicationCall.uuidParam(
    name: String,
    message: String,
): Uuid =
    runCatching { Uuid.parse(parameters[name].orEmpty()) }
        .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, message) }

/** Sdílené tělo požadavku, které nese jen jednorázový token z e-mailu. */
@Serializable
data class TokenRequest(
    val token: String,
)
