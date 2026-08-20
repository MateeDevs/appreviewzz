package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.InvitationId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationRequest(
    val name: String,
    /** Nepovinné — když chybí, odvodí se z názvu. */
    val slug: String? = null,
)

@Serializable
data class OrganizationResponse(
    val id: String,
    val slug: String,
    val name: String,
    val role: OrgRole,
)

@Serializable
data class MemberResponse(
    val userId: String,
    val email: String,
    val displayName: String?,
    val role: OrgRole,
    val since: String,
)

@Serializable
data class InviteRequest(
    val email: String,
    val role: OrgRole = OrgRole.MEMBER,
)

@Serializable
data class InvitationResponse(
    val id: String,
    val email: String,
    val role: OrgRole,
    val expiresAt: String,
    /**
     * `false` = pozvánka platí, ale e-mail neodešel (nefunkční SMTP). Console to musí
     * říct nahlas, jinak se čeká na zprávu, která nikdy nedorazí.
     */
    val delivered: Boolean = true,
)

@Serializable
data class ChangeRoleRequest(
    val role: OrgRole,
)

/**
 * Organizace, členové a pozvánky (F3.2).
 *
 * Organizace se v adrese identifikuje **slugem**, ne UUID — je to část odkazu, kterou lidé
 * posílají mezi sebou. Kdo v organizaci není, dostane 404, ne 403: jinak by se dalo hádáním
 * adres zjistit, kdo je náš zákazník.
 */
fun Route.orgRoutes(console: ConsoleWiring) {
    val orgs = console.orgs
    val organizations = console.organizations
    val memberships = console.memberships

    get("/orgs") {
        val user = call.authenticatedUser.account.user
        val result =
            io {
                memberships.listByUser(user.id).mapNotNull { membership ->
                    organizations.findById(membership.orgId)?.let { it.toResponse(membership.role) }
                }
            }
        call.respond(result)
    }

    post("/orgs") {
        val request = call.receive<CreateOrganizationRequest>()
        val organization =
            io { orgs.create(call.authenticatedUser.account, request.name, request.slug) }
        call.respond(HttpStatusCode.Created, organization.toResponse(OrgRole.OWNER))
    }

    route("/orgs/{org}") {
        get {
            val context = call.orgContext(organizations, memberships)
            call.respond(context.organization.toResponse(context.actor.role))
        }

        get("/members") {
            val context = call.orgContext(organizations, memberships)
            val members =
                io {
                    orgs.listMembers(context.organization.id).map { member ->
                        MemberResponse(
                            userId = member.user.id.toString(),
                            email = member.user.email,
                            displayName = member.user.displayName,
                            role = member.role,
                            since = member.since.toString(),
                        )
                    }
                }
            call.respond(members)
        }

        patch("/members/{userId}") {
            val context = call.orgContext(organizations, memberships)
            val request = call.receive<ChangeRoleRequest>()
            io { orgs.changeRole(context.organization, context.actor, call.userIdParam(), request.role) }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/members/{userId}") {
            val context = call.orgContext(organizations, memberships)
            io { orgs.removeMember(context.organization, context.actor, call.userIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/invitations") {
            val context = call.orgContext(organizations, memberships)
            val pending =
                io {
                    orgs.listInvitations(context.organization.id).map {
                        InvitationResponse(
                            id = it.id.toString(),
                            email = it.email,
                            role = it.role,
                            expiresAt = it.expiresAt.toString(),
                        )
                    }
                }
            call.respond(pending)
        }

        post("/invitations") {
            val context = call.orgContext(organizations, memberships)
            val request = call.receive<InviteRequest>()
            val result =
                io { orgs.invite(context.organization, context.actor, request.email, request.role) }
            call.respond(
                HttpStatusCode.Created,
                InvitationResponse(
                    id = result.invitation.id.toString(),
                    email = result.invitation.email,
                    role = result.invitation.role,
                    expiresAt = result.invitation.expiresAt.toString(),
                    delivered = result.delivered,
                ),
            )
        }

        delete("/invitations/{id}") {
            val context = call.orgContext(organizations, memberships)
            val id =
                runCatching { InvitationId.parse(call.parameters["id"].orEmpty()) }
                    .getOrElse { throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková pozvánka tu není") }
            io { orgs.revokeInvitation(context.organization, context.actor, id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // Mimo `/orgs/{org}`: kdo pozvánku přijímá, do organizace ještě nepatří.
    post("/invitations/accept") {
        val request = call.receive<TokenRequest>()
        val organization =
            io { orgs.acceptInvitation(call.authenticatedUser.account, SecretPayload(request.token)) }
        val role = io { memberships.roleOf(organization.id, call.authenticatedUser.account.user.id) }
        call.respond(organization.toResponse(role ?: OrgRole.MEMBER))
    }
}

private fun Organization.toResponse(role: OrgRole) = OrganizationResponse(id = id.toString(), slug = slug, name = name, role = role)
