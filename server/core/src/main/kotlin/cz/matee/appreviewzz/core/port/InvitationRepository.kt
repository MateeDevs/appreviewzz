package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.Invitation
import cz.matee.appreviewzz.core.model.InvitationId
import cz.matee.appreviewzz.core.model.OrgRole
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.UserId
import kotlin.time.Instant

interface InvitationRepository {
    /**
     * Založí pozvánku. Když na tutéž adresu v organizaci nějaká čeká, zruší ji a nahradí —
     * jinak by po druhém „pozvat" platily dva odkazy a nikdo by nevěděl který.
     */
    fun create(
        orgId: OrganizationId,
        email: String,
        role: OrgRole,
        invitedBy: UserId?,
        tokenHash: ByteArray,
        expiresAt: Instant,
        at: Instant,
    ): Invitation

    fun listPending(
        orgId: OrganizationId,
        at: Instant,
    ): List<Invitation>

    /**
     * Bez org-scope záměrně: pozvaný v tu chvíli do žádné organizace nepatří, takže se
     * nemá k čemu vztáhnout. Token sám nese, o kterou organizaci jde.
     */
    fun findPendingByToken(
        tokenHash: ByteArray,
        at: Instant,
    ): Invitation?

    fun markAccepted(
        id: InvitationId,
        at: Instant,
    ): Boolean

    fun revoke(
        orgId: OrganizationId,
        id: InvitationId,
        at: Instant,
    ): Boolean
}
