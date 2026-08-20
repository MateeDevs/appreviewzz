package cz.matee.appreviewzz.core.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class InvitationId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun parse(raw: String): InvitationId = InvitationId(Uuid.parse(raw))
    }
}

/**
 * Pozvánka do organizace. Členství vzniká až přijetím — do té doby pozvaný nikam nevidí,
 * ale je vidět, že se na něj čeká.
 */
data class Invitation(
    val id: InvitationId,
    val orgId: OrganizationId,
    val email: String,
    val role: OrgRole,
    val invitedBy: UserId?,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
    val revokedAt: Instant?,
    val createdAt: Instant,
) {
    fun isPendingAt(now: Instant): Boolean = acceptedAt == null && revokedAt == null && expiresAt > now
}
