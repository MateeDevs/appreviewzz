package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.ActorType
import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.AppTopic
import cz.matee.appreviewzz.core.model.AppTopicId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.Organization
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.port.AppRepository
import cz.matee.appreviewzz.core.port.AppTopicRepository
import cz.matee.appreviewzz.core.port.AuditLogRepository
import cz.matee.appreviewzz.core.port.NewAppTopic
import cz.matee.appreviewzz.core.port.auditEntry

/** Vlastní téma, jak ho zadává člověk v konzoli. */
data class AppTopicDraft(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

/**
 * Vlastní témata aplikace (F8). Klient si přidá téma, které v obecné taxonomii nedává smysl
 * mít — „synchronizace s Garmin hodinkami", „školní účty".
 *
 * Popis se ukládá **anglicky**, protože jde doslova do promptu jako popis uzlu taxonomie.
 * Změna popisu **nepřeanalyzuje historii**: staré výklady zůstanou, jak jsou, a nové recenze
 * se tagují podle nového znění. Přepočítat všechno by stálo peníze a míchalo by dvě pravítka
 * v jednom grafu.
 */
class AppTopicService(
    private val topics: AppTopicRepository,
    private val apps: AppRepository,
    private val audit: AuditLogRepository,
) {
    fun list(
        orgId: OrganizationId,
        appId: AppId,
    ): List<AppTopic> {
        requireApp(orgId, appId)
        return topics.listByApp(orgId, appId)
    }

    fun create(
        organization: Organization,
        actor: OrgActor,
        appId: AppId,
        draft: AppTopicDraft,
    ): AppTopic {
        requireRole(actor, OrgRole.ADMIN)
        requireApp(organization.id, appId)
        val name = validName(draft.name)
        val description = validDescription(draft.description)
        if (topics.listByApp(organization.id, appId).any { it.name.equals(name, ignoreCase = true) }) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Téma '$name' už u téhle aplikace je")
        }
        val created = topics.create(organization.id, NewAppTopic(appId = appId, name = name, description = description))
        audit(organization.id, actor, "app_topic.created", created.id.toString(), mapOf("name" to name))
        return created
    }

    fun update(
        organization: Organization,
        actor: OrgActor,
        id: AppTopicId,
        draft: AppTopicDraft,
    ): AppTopic {
        requireRole(actor, OrgRole.ADMIN)
        val current = topics.findById(organization.id, id) ?: notFound()
        val updated =
            topics.update(
                orgId = organization.id,
                id = id,
                name = validName(draft.name),
                description = validDescription(draft.description),
                enabled = draft.enabled,
            ) ?: notFound()
        audit(
            organization.id,
            actor,
            "app_topic.updated",
            id.toString(),
            mapOf("name" to updated.name, "zapnuté" to updated.enabled.toString(), "před" to current.name),
        )
        return updated
    }

    /**
     * Smazání tématu. Zmínky ve výkladech zůstanou — jsou to naměřená data a mazat je jen
     * proto, že se změnil seznam témat, by z historie udělalo lež. V konzoli se ukážou pod
     * klíčem, dokud je nepřebije nový výklad.
     */
    fun delete(
        organization: Organization,
        actor: OrgActor,
        id: AppTopicId,
    ) {
        requireRole(actor, OrgRole.ADMIN)
        val current = topics.findById(organization.id, id) ?: notFound()
        topics.delete(organization.id, id)
        audit(organization.id, actor, "app_topic.deleted", id.toString(), mapOf("name" to current.name))
    }

    private fun requireApp(
        orgId: OrganizationId,
        appId: AppId,
    ) {
        apps.findById(orgId, appId) ?: throw ConsoleException(ConsoleFailure.NOT_FOUND, "Taková aplikace tu není")
    }

    private fun validName(raw: String): String {
        val value = raw.trim()
        if (value.length !in MIN_NAME..MAX_NAME) {
            throw ConsoleException(ConsoleFailure.INVALID_INPUT, "Název tématu musí mít $MIN_NAME až $MAX_NAME znaků")
        }
        return value
    }

    private fun validDescription(raw: String): String {
        val value = raw.trim()
        if (value.length !in MIN_DESCRIPTION..MAX_DESCRIPTION) {
            throw ConsoleException(
                ConsoleFailure.INVALID_INPUT,
                "Popis tématu musí mít $MIN_DESCRIPTION až $MAX_DESCRIPTION znaků — čte ho AI, takže z něj musí " +
                    "poznat, co do tématu patří",
            )
        }
        return value
    }

    private fun notFound(): Nothing = throw ConsoleException(ConsoleFailure.NOT_FOUND, "Takové téma tu není")

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
                targetType = "app_topic",
                targetId = targetId,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        /** Meze zrcadlí `CHECK` v databázi — tady z nich ale vzejde věta, ne constraint violation. */
        const val MIN_NAME = 2
        const val MAX_NAME = 60
        const val MIN_DESCRIPTION = 10
        const val MAX_DESCRIPTION = 300
    }
}
