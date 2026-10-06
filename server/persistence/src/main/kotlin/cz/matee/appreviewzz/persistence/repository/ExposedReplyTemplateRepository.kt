package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.ReplyTemplate
import cz.matee.appreviewzz.core.model.ReplyTemplateId
import cz.matee.appreviewzz.core.port.NewReplyTemplate
import cz.matee.appreviewzz.core.port.ReplyTemplateRepository
import cz.matee.appreviewzz.persistence.schema.ReplyTemplates
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class ExposedReplyTemplateRepository(
    private val database: ExposedDatabase,
    private val clock: Clock = Clock.System,
) : ReplyTemplateRepository {
    override fun create(
        orgId: OrganizationId,
        template: NewReplyTemplate,
    ): ReplyTemplate =
        transaction(database) {
            val now = clock.now()
            val created =
                ReplyTemplate(
                    id = ReplyTemplateId(Uuid.random()),
                    orgId = orgId,
                    appId = template.appId,
                    name = template.name,
                    body = template.body,
                    createdAt = now,
                    updatedAt = now,
                )
            ReplyTemplates.insert {
                it[id] = created.id
                it[ReplyTemplates.orgId] = created.orgId
                it[appId] = created.appId
                it[name] = created.name
                it[body] = created.body
                it[createdAt] = now
                it[updatedAt] = now
            }
            created
        }

    override fun findById(
        orgId: OrganizationId,
        id: ReplyTemplateId,
    ): ReplyTemplate? =
        transaction(database) {
            ReplyTemplates
                .selectAll()
                .where { (ReplyTemplates.orgId eq orgId) and (ReplyTemplates.id eq id) }
                .firstOrNull()
                ?.toReplyTemplate()
        }

    override fun listByApp(
        orgId: OrganizationId,
        appId: AppId,
    ): List<ReplyTemplate> =
        transaction(database) {
            ReplyTemplates
                .selectAll()
                .where { (ReplyTemplates.orgId eq orgId) and (ReplyTemplates.appId eq appId) }
                .orderBy(ReplyTemplates.name to SortOrder.ASC)
                .map { it.toReplyTemplate() }
        }

    override fun update(
        orgId: OrganizationId,
        id: ReplyTemplateId,
        name: String,
        body: String,
    ): ReplyTemplate? =
        transaction(database) {
            val updated =
                ReplyTemplates.update({ (ReplyTemplates.orgId eq orgId) and (ReplyTemplates.id eq id) }) {
                    it[ReplyTemplates.name] = name
                    it[ReplyTemplates.body] = body
                    it[updatedAt] = clock.now()
                }
            if (updated == 0) null else findById(orgId, id)
        }

    override fun delete(
        orgId: OrganizationId,
        id: ReplyTemplateId,
    ): Boolean =
        transaction(database) {
            ReplyTemplates.deleteWhere { (ReplyTemplates.orgId eq orgId) and (ReplyTemplates.id eq id) } > 0
        }

    private fun ResultRow.toReplyTemplate(): ReplyTemplate =
        ReplyTemplate(
            id = this[ReplyTemplates.id],
            orgId = this[ReplyTemplates.orgId],
            appId = this[ReplyTemplates.appId],
            name = this[ReplyTemplates.name],
            body = this[ReplyTemplates.body],
            createdAt = this[ReplyTemplates.createdAt],
            updatedAt = this[ReplyTemplates.updatedAt],
        )
}
