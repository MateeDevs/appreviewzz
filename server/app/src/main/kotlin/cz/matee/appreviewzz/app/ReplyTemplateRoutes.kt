package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.ReplyTemplate
import cz.matee.appreviewzz.core.model.ReplyTemplateId
import cz.matee.appreviewzz.core.usecase.ConsoleException
import cz.matee.appreviewzz.core.usecase.ConsoleFailure
import cz.matee.appreviewzz.core.usecase.ReplyTemplateDraft
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
data class ReplyTemplateRequest(
    val name: String,
    val body: String,
)

@Serializable
data class ReplyTemplateResponse(
    val id: String,
    val name: String,
    val body: String,
    val updatedAt: String,
)

/** Šablony odpovědí per appka (C2). Čte každý člen, spravuje správce. */
fun Route.replyTemplateRoutes(console: ConsoleWiring) {
    val templates = console.replyTemplates

    route("/orgs/{org}/apps/{app}/reply-templates") {
        get {
            val context = call.orgContext(console.organizations, console.memberships)
            call.respond(io { templates.list(context.organization.id, call.appIdParam()).map { it.toResponse() } })
        }

        post {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ReplyTemplateRequest>()
            val created =
                io {
                    templates.create(
                        organization = context.organization,
                        actor = context.actor,
                        appId = call.appIdParam(),
                        draft = ReplyTemplateDraft(request.name, request.body),
                    )
                }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        patch("/{template}") {
            val context = call.orgContext(console.organizations, console.memberships)
            val request = call.receive<ReplyTemplateRequest>()
            val updated =
                io {
                    templates.update(
                        organization = context.organization,
                        actor = context.actor,
                        id = call.templateIdParam(),
                        draft = ReplyTemplateDraft(request.name, request.body),
                    )
                }
            call.respond(updated.toResponse())
        }

        delete("/{template}") {
            val context = call.orgContext(console.organizations, console.memberships)
            io { templates.delete(context.organization, context.actor, call.templateIdParam()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ReplyTemplate.toResponse() = ReplyTemplateResponse(id.toString(), name, body, updatedAt.toString())

private fun ApplicationCall.templateIdParam(): ReplyTemplateId =
    parameters["template"]?.let { runCatching { ReplyTemplateId.parse(it) }.getOrNull() }
        ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková šablona tu není")
