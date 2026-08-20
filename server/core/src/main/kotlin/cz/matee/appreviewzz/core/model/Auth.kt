package cz.matee.appreviewzz.core.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class SessionId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

/**
 * Přihlášená relace console. Samotný token tady není a v databázi taky ne — drží ho
 * jen prohlížeč v cookie, my známe jeho otisk.
 */
data class UserSession(
    val id: SessionId,
    val userId: UserId,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
)

/** K čemu je jednorázový odkaz poslaný e-mailem. */
enum class UserTokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
}

/**
 * Přihlašovací stránka [User]. Hash hesla je schválně mimo `User`: ten se serializuje do API
 * odpovědí a do audit logu, tohle nikdy nesmí opustit autentizační use-case.
 *
 * `passwordHash == null` znamená účet založený pozvánkou, který si heslo teprve nastaví.
 */
data class UserAccount(
    val user: User,
    val passwordHash: String?,
    val emailVerifiedAt: Instant?,
    val lastLoginAt: Instant?,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
) {
    val emailVerified: Boolean get() = emailVerifiedAt != null

    fun isLockedAt(now: Instant): Boolean = lockedUntil?.let { it > now } == true
}
