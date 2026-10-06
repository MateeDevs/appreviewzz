package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReplyTemplate
import cz.matee.appreviewzz.core.model.ReplyTemplateId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.NewReplyTemplate
import cz.matee.appreviewzz.core.port.ReplyTemplateRepository
import cz.matee.appreviewzz.core.port.auditEntry

/** Šablona, jak ji zadává člověk v konzoli. */
data class ReplyTemplateDraft(
    val name: String,
    val body: String,
)

/**
 * Šablony odpovědí (C2). Číst je smí každý člen — používá je podpora při odpovídání;
 * spravovat je smí správce, stejně jako instrukce pro AI.
 *
 * Proměnné `{jmeno}`, `{appka}` a `{verze}` server nezná: dosazuje je konzole ve chvíli
 * vložení, kdy má recenzi před sebou. Šablona je text, ne program.
 */
class ReplyTemplateService(
    private val templates: ReplyTemplateRepository,
    private val apps: AppRepository,
    private val audit: AuditLogRepository,
) {
    fun list(
        orgId: OrganizationId,
        appId: AppId,
    ): List<ReplyTemplate> {
        requireApp(orgId, appId)
        return templates.listByApp(orgId, appId)
    }

    fun create(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        draft: ReplyTemplateDraft,
    ): ReplyTemplate {
        requireRole(actor, OrgRole.ADMIN)
        requireApp(organization.id, appId)
        val name = validName(draft.name)
        if (templates.listByApp(organization.id, appId).any { it.name.equals(name, ignoreCase = true) }) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Šablona '$name' u téhle aplikace už je")
        }
        val created = templates.create(organization.id, NewReplyTemplate(appId, name, validBody(draft.body)))
        audit(organization.id, actor, "reply_template.created", created.id.toString(), mapOf("name" to name))
        return created
    }

    fun update(
        organization: Organization,
        actor: OrgActor,
        id: ReplyTemplateId,
        draft: ReplyTemplateDraft,
    ): ReplyTemplate {
        requireRole(actor, OrgRole.ADMIN)
        templates.findById(organization.id, id) ?: notFound()
        val updated = templates.update(organization.id, id, validName(draft.name), validBody(draft.body)) ?: notFound()
        audit(organization.id, actor, "reply_template.updated", id.toString(), mapOf("name" to updated.name))
        return updated
    }

    fun delete(
        organization: Organization,
        actor: OrgActor,
        id: ReplyTemplateId,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val current = templates.findById(organization.id, id) ?: notFound()
        templates.delete(organization.id, id)
        audit(organization.id, actor, "reply_template.deleted", id.toString(), mapOf("name" to current.name))
    }

    private fun requireApp(
        orgId: OrganizationId,
        appId: AppId,
    ) {
        apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
    }

    private fun validName(raw: String): String {
        val value = raw.trim()
        if (value.length !in 1..MAX_NAME) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Název šablony musí mít 1 až $MAX_NAME znaků")
        }
        return value
    }

    /** Horní mez je limit App Storu; Google Play si 350 znaků ohlídá konzole při vkládání. */
    private fun validBody(raw: String): String {
        val value = raw.trim()
        if (value.length !in 1..MAX_BODY) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Text šablony musí mít 1 až $MAX_BODY znaků")
        }
        return value
    }

    private fun audit(
        orgId: OrganizationId,
        actor: OrgActor,
        action: String,
        targetId: String,
        metadata: Map<String, String>,
    ) {
        audit.append(
            auditEntry(
                orgId = orgId,
                action = action,
                actorType = ActorType.USER,
                actorUserId = actor.userId,
                actorLabel = actor.displayName,
                targetType = "reply_template",
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }

    private fun notFound(): Nothing = throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková šablona tu není")

    private companion object {
        const val MAX_NAME = 60
        const val MAX_BODY = 5_000
    }
}
