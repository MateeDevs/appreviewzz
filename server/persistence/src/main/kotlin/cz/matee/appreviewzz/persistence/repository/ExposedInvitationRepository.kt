package cz.matee.appreviewzz.persistence.repository

import cz.matee.appreviewzz.core.model.Invitation
import cz.matee.appreviewzz.core.model.InvitationId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.InvitationRepository
import cz.matee.appreviewzz.persistence.schema.OrgInvitations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class ExposedInvitationRepository(
    private val database: ExposedDatabase,
) : InvitationRepository {
    override fun create(
        orgId: OrganizationId,
        email: String,
        role: OrgRole,
        invitedBy: UserId?,
        tokenHash: ByteArray,
        expiresAt: Instant,
        at: Instant,
    ): Invitation =
        transaction(database) {
            // Parciální unikátní index pustí jen jednu čekající pozvánku na adresu — tu starou
            // proto zrušíme sami, ať je z auditu vidět, že ji nahradila novější.
            OrgInvitations.update({ pending(orgId, email) }) { it[revokedAt] = at }

            val invitation =
                Invitation(
                    id = InvitationId(Uuid.random()),
                    orgId = orgId,
                    email = email,
                    role = role,
                    invitedBy = invitedBy,
                    expiresAt = expiresAt,
                    acceptedAt = null,
                    revokedAt = null,
                    createdAt = at,
                )
            OrgInvitations.insert {
                it[id] = invitation.id
                it[OrgInvitations.orgId] = invitation.orgId
                it[OrgInvitations.email] = invitation.email
                it[OrgInvitations.role] = invitation.role
                it[OrgInvitations.invitedBy] = invitation.invitedBy
                it[OrgInvitations.tokenHash] = tokenHash
                it[OrgInvitations.expiresAt] = invitation.expiresAt
                it[createdAt] = invitation.createdAt
            }
            invitation
        }

    override fun listPending(
        orgId: OrganizationId,
        at: Instant,
    ): List<Invitation> =
        transaction(database) {
            OrgInvitations
                .selectAll()
                .where {
                    (OrgInvitations.orgId eq orgId) and
                        (OrgInvitations.acceptedAt eq null) and
                        (OrgInvitations.revokedAt eq null) and
                        (OrgInvitations.expiresAt greater at)
                }.orderBy(OrgInvitations.createdAt to SortOrder.DESC)
                .map { it.toInvitation() }
        }

    override fun findPendingByToken(
        tokenHash: ByteArray,
        at: Instant,
    ): Invitation? =
        transaction(database) {
            OrgInvitations
                .selectAll()
                .where {
                    (OrgInvitations.tokenHash eq tokenHash) and
                        (OrgInvitations.acceptedAt eq null) and
                        (OrgInvitations.revokedAt eq null) and
                        (OrgInvitations.expiresAt greater at)
                }.firstOrNull()
                ?.toInvitation()
        }

    override fun markAccepted(
        id: InvitationId,
        at: Instant,
    ): Boolean =
        transaction(database) {
            OrgInvitations.update({ (OrgInvitations.id eq id) and (OrgInvitations.acceptedAt eq null) }) {
                it[acceptedAt] = at
            } > 0
        }

    override fun revoke(
        orgId: OrganizationId,
        id: InvitationId,
        at: Instant,
    ): Boolean =
        transaction(database) {
            OrgInvitations.update({
                (OrgInvitations.orgId eq orgId) and
                    (OrgInvitations.id eq id) and
                    (OrgInvitations.acceptedAt eq null) and
                    (OrgInvitations.revokedAt eq null)
            }) {
                it[revokedAt] = at
            } > 0
        }

    private fun pending(
        orgId: OrganizationId,
        email: String,
    ) = (OrgInvitations.orgId eq orgId) and
        (OrgInvitations.email eq email) and
        (OrgInvitations.acceptedAt eq null) and
        (OrgInvitations.revokedAt eq null)
}
