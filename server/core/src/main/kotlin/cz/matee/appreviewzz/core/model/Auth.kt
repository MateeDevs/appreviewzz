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

/** K čemu je jednorázový token. První dva chodí e-mailem, třetí drží rozdělané přihlášení. */
enum class UserTokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,

    /**
     * Mezistav přihlášení: heslo sedí, čeká se na kód z autentizační appky. Token nahrazuje
     * „polovičatou relaci" — dokud druhý faktor neprojde, žádná session nevznikne, takže
     * není co ukrást ani čím se prokázat.
     */
    MFA_CHALLENGE,
}

/**
 * Zašifrované tajemství vázané na uživatele (F5.3). Nese s sebou ID klíče, pod kterým vzniklo,
 * aby rotace klíče nezneplatnila to, co je v databázi.
 */
class SealedSecret(
    val dataKeyId: Uuid,
    val ciphertext: ByteArray,
) {
    override fun toString(): String = "SealedSecret(dataKeyId=$dataKeyId, ciphertext=${ciphertext.size}B)"
}

/**
 * Druhý faktor uživatele. `confirmedAt == null` znamená rozdělané nastavení — tajemství už
 * existuje, ale ještě nikdo neprokázal, že si ho opravdu naskenoval. Takový záznam přihlášení
 * neovlivňuje; jinak by se člověk mohl vyřadit z vlastního účtu tím, že setup nedoklikne.
 */
data class UserTotp(
    val userId: UserId,
    val secret: SealedSecret,
    val createdAt: Instant,
    val confirmedAt: Instant?,
    /** Poslední uplatněný časový krok — tentýž kód podruhé neprojde. */
    val lastStep: Long?,
) {
    val enabled: Boolean get() = confirmedAt != null
}

/** Co o druhém faktoru ukazuje console. */
data class MfaStatus(
    val enabled: Boolean,
    val setupPending: Boolean,
    val remainingRecoveryCodes: Int,
)

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
