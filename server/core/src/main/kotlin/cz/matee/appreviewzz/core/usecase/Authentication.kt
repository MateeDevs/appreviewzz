package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.message.AuthMails
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OpaqueTokens
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserAccount
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.model.UserSession
import cz.matee.appreviewzz.core.model.UserTokenPurpose
import cz.matee.appreviewzz.core.port.MailException
import cz.matee.appreviewzz.core.port.Mailer
import cz.matee.appreviewzz.core.port.OutgoingMail
import cz.matee.appreviewzz.core.port.PasswordHasher
import cz.matee.appreviewzz.core.port.SessionRepository
import cz.matee.appreviewzz.core.port.UserRepository
import cz.matee.appreviewzz.core.port.UserTokenRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Proč se autentizace nepovedla. Odpovídá tomu, co API pošle ven jako `error`. */
enum class AuthFailure {
    INVALID_EMAIL,
    WEAK_PASSWORD,
    EMAIL_TAKEN,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    INVALID_TOKEN,
}

class AuthException(
    val failure: AuthFailure,
    message: String,
) : RuntimeException(message)

/**
 * Čísla, na kterých stojí bezpečnost přihlášení. Sedí pohromadě schválně — až se budou
 * zvedat (F5 hardening), ať je jasné, co všechno se tím posouvá.
 */
data class AuthPolicy(
    /** Délka místo vynucených znakových tříd — dnešní doporučení NIST i OWASP. */
    val minPasswordLength: Int = 12,
    /** Strop kvůli argon2: bez něj by megabajtové „heslo" bylo levné DoS. */
    val maxPasswordLength: Int = 200,
    val sessionLifetime: Duration = 14.days,
    val verificationLifetime: Duration = 3.days,
    val resetLifetime: Duration = 1.hours,
    val maxFailedLogins: Int = 8,
    val lockFor: Duration = 15.minutes,
)

/** Přihlášený uživatel tak, jak ho vidí zbytek API. */
data class AuthenticatedUser(
    val account: UserAccount,
    val session: UserSession,
)

sealed interface LoginResult {
    data class Success(
        /** Plaintext do cookie. Podruhé už ho nikdo nezjistí — v databázi je jen otisk. */
        val token: SecretPayload,
        val session: UserSession,
        val account: UserAccount,
    ) : LoginResult

    data object InvalidCredentials : LoginResult

    data class Locked(
        val until: Instant,
    ) : LoginResult
}

/**
 * Přihlášení do console (F3.1).
 *
 * Zásady, které se tu drží:
 *
 * - **Heslo nikdy neopustí tuhle třídu jinak než jako hash.** Chodí sem v [SecretPayload],
 *   jehož `toString()` je redigovaný, takže se nedostane ani do logu při výjimce.
 * - **Odpověď nesmí prozradit, jestli e-mail existuje** — u přihlášení i u resetu hesla.
 *   Proto se u neznámého e-mailu stejně jednou spočítá argon2: jinak by šlo účty rozeznat
 *   podle doby odpovědi.
 * - **Změna hesla ruší relace.** Kdo mi ukradl session, přijde o ni ve chvíli, kdy si
 *   heslo změním — to je hlavní důvod, proč to člověk dělá.
 */
class AuthenticationService(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val tokens: UserTokenRepository,
    private val hasher: PasswordHasher,
    private val mailer: Mailer,
    private val links: ConsoleLinks,
    private val clock: Clock = Clock.System,
    private val policy: AuthPolicy = AuthPolicy(),
) {
    /**
     * Registrace. Když už účet existuje bez hesla (založený přes CLI nebo pozvánkou),
     * jen se mu heslo nastaví — jinak by se člověk pozvaný do organizace nemohl přihlásit
     * a zároveň by si nemohl založit účet.
     */
    fun register(
        email: String,
        displayName: String?,
        password: SecretPayload,
        locale: MessageLocale = MessageLocale.CS,
    ): User {
        val normalized = normalizeEmail(email)
        requireStrongPassword(password)
        val now = clock.now()
        val hash = hasher.hash(password)

        val existing = users.findAccountByEmail(normalized)
        val user =
            when {
                existing == null -> users.create(normalized, displayName).also { users.setPassword(it.id, hash, now) }
                existing.passwordHash == null -> {
                    users.setPassword(existing.user.id, hash, now)
                    existing.user
                }
                else -> throw AuthException(AuthFailure.EMAIL_TAKEN, "Účet s e-mailem $normalized už existuje")
            }

        sendVerification(user, locale, now)
        logger.info { "Registrace uživatele ${user.id}" }
        return user
    }

    fun login(
        email: String,
        password: SecretPayload,
        userAgent: String?,
        clientIp: String?,
    ): LoginResult {
        val now = clock.now()
        val account = users.findAccountByEmail(normalizeEmailLoosely(email))

        if (account?.passwordHash == null) {
            // Účet neznáme (nebo si ještě nenastavil heslo). Přesto jednou spočítáme hash,
            // ať odpověď trvá stejně dlouho jako u existujícího účtu.
            hasher.verify(password, DUMMY_HASH)
            return LoginResult.InvalidCredentials
        }
        if (account.isLockedAt(now)) return LoginResult.Locked(checkNotNull(account.lockedUntil))

        if (!hasher.verify(password, account.passwordHash)) {
            val failures = account.failedLoginCount + 1
            val lockedUntil = if (failures >= policy.maxFailedLogins) now + policy.lockFor else null
            users.recordLoginAttempt(account.user.id, failures, lockedUntil, lastLoginAt = null)
            if (lockedUntil != null) {
                logger.warn { "Účet ${account.user.id} zamčený do $lockedUntil po $failures neúspěšných pokusech" }
                return LoginResult.Locked(lockedUntil)
            }
            return LoginResult.InvalidCredentials
        }

        users.recordLoginAttempt(account.user.id, failedLoginCount = 0, lockedUntil = null, lastLoginAt = now)
        val token = OpaqueTokens.generate()
        val session =
            sessions.create(
                userId = account.user.id,
                tokenHash = OpaqueTokens.hash(token),
                createdAt = now,
                expiresAt = now + policy.sessionLifetime,
                userAgent = userAgent,
                clientIp = clientIp,
            )
        return LoginResult.Success(token, session, account.copy(failedLoginCount = 0, lockedUntil = null))
    }

    /** Ověření cookie při každém požadavku. `lastSeenAt` se posouvá, expirace se neprodlužuje. */
    fun authenticate(token: SecretPayload): AuthenticatedUser? {
        val now = clock.now()
        val session = sessions.findValid(OpaqueTokens.hash(token), now) ?: return null
        val account = users.findAccountById(session.userId) ?: return null
        sessions.touch(session.id, now)
        return AuthenticatedUser(account, session)
    }

    fun logout(token: SecretPayload) {
        val now = clock.now()
        sessions.findValid(OpaqueTokens.hash(token), now)?.let { sessions.revoke(it.id, now) }
    }

    fun verifyEmail(token: SecretPayload): User {
        val now = clock.now()
        val userId =
            tokens.consume(UserTokenPurpose.EMAIL_VERIFICATION, OpaqueTokens.hash(token), now)
                ?: throw AuthException(AuthFailure.INVALID_TOKEN, "Odkaz je neplatný nebo mu vypršela platnost")
        users.markEmailVerified(userId, now)
        return checkNotNull(users.findById(userId))
    }

    fun resendVerification(
        userId: UserId,
        locale: MessageLocale = MessageLocale.CS,
    ) {
        val account = users.findAccountById(userId) ?: return
        if (account.emailVerified) return
        sendVerification(account.user, locale, clock.now())
    }

    /**
     * Žádost o reset. Navenek se chová stejně pro známý i neznámý e-mail — jinak by
     * formulář fungoval jako seznam zákazníků.
     */
    fun requestPasswordReset(
        email: String,
        locale: MessageLocale = MessageLocale.CS,
    ) {
        val now = clock.now()
        val account = users.findAccountByEmail(normalizeEmailLoosely(email)) ?: return
        tokens.invalidateAll(account.user.id, UserTokenPurpose.PASSWORD_RESET, now)

        val token = OpaqueTokens.generate()
        tokens.create(
            userId = account.user.id,
            purpose = UserTokenPurpose.PASSWORD_RESET,
            tokenHash = OpaqueTokens.hash(token),
            expiresAt = now + policy.resetLifetime,
            at = now,
        )
        deliver(AuthMails.passwordReset(account.user, links.passwordReset(token), policy.resetLifetime, locale))
    }

    /** Reset hesla z odkazu. Ruší všechny relace — nevíme, kdo se mezitím přihlásil. */
    fun resetPassword(
        token: SecretPayload,
        newPassword: SecretPayload,
    ): User {
        requireStrongPassword(newPassword)
        val now = clock.now()
        val userId =
            tokens.consume(UserTokenPurpose.PASSWORD_RESET, OpaqueTokens.hash(token), now)
                ?: throw AuthException(AuthFailure.INVALID_TOKEN, "Odkaz je neplatný nebo mu vypršela platnost")

        users.setPassword(userId, hasher.hash(newPassword), now)
        val revoked = sessions.revokeAllOfUser(userId, now)
        logger.info { "Heslo uživatele $userId resetované, zrušeno $revoked relací" }
        return checkNotNull(users.findById(userId))
    }

    /**
     * Změna hesla přihlášeným člověkem. Ostatní relace padají, ta aktuální zůstává —
     * odhlásit člověka z prohlížeče, ve kterém si heslo právě změnil, nedává smysl.
     */
    fun changePassword(
        user: AuthenticatedUser,
        currentPassword: SecretPayload,
        newPassword: SecretPayload,
    ) {
        val hash =
            user.account.passwordHash
                ?: throw AuthException(AuthFailure.INVALID_CREDENTIALS, "Účet zatím nemá heslo — použij obnovu hesla")
        if (!hasher.verify(currentPassword, hash)) {
            throw AuthException(AuthFailure.INVALID_CREDENTIALS, "Stávající heslo nesouhlasí")
        }
        requireStrongPassword(newPassword)

        val now = clock.now()
        users.setPassword(user.account.user.id, hasher.hash(newPassword), now)
        val revoked = sessions.revokeAllOfUserExcept(user.account.user.id, user.session.id, now)
        logger.info { "Uživatel ${user.account.user.id} si změnil heslo, zrušeno $revoked dalších relací" }
    }

    private fun sendVerification(
        user: User,
        locale: MessageLocale,
        now: Instant,
    ) {
        tokens.invalidateAll(user.id, UserTokenPurpose.EMAIL_VERIFICATION, now)
        val token = OpaqueTokens.generate()
        tokens.create(
            userId = user.id,
            purpose = UserTokenPurpose.EMAIL_VERIFICATION,
            tokenHash = OpaqueTokens.hash(token),
            expiresAt = now + policy.verificationLifetime,
            at = now,
        )
        deliver(AuthMails.emailVerification(user, links.emailVerification(token), locale))
    }

    /**
     * Nefunkční pošta nesmí zabít registraci — účet platí a odkaz jde poslat znovu.
     * Kdyby to spadlo, člověk by měl založený účet a zároveň chybovou hlášku.
     */
    private fun deliver(mail: OutgoingMail) {
        try {
            mailer.send(mail)
        } catch (error: MailException) {
            logger.error(error) { "E-mail '${mail.subject}' se nepodařilo odeslat" }
        }
    }

    private fun requireStrongPassword(password: SecretPayload) {
        val length = password.value.length
        if (length < policy.minPasswordLength) {
            throw AuthException(
                AuthFailure.WEAK_PASSWORD,
                "Heslo musí mít aspoň ${policy.minPasswordLength} znaků",
            )
        }
        if (length > policy.maxPasswordLength) {
            throw AuthException(AuthFailure.WEAK_PASSWORD, "Heslo je delší než ${policy.maxPasswordLength} znaků")
        }
        if (password.value.isBlank()) throw AuthException(AuthFailure.WEAK_PASSWORD, "Heslo nesmí být jen mezery")
    }

    private fun normalizeEmail(email: String): String {
        val normalized = normalizeEmailLoosely(email)
        if (!EMAIL.matches(normalized)) throw AuthException(AuthFailure.INVALID_EMAIL, "'$email' nevypadá jako e-mail")
        return normalized
    }

    /** Při přihlášení se na tvar e-mailu nestěžujeme — odpověď je stejně „nesedí to". */
    private fun normalizeEmailLoosely(email: String): String = email.trim().lowercase()

    private companion object {
        /** Záměrně volný: adresy jsou divočejší, než se lidem zdá, a pravdu řekne až doručení. */
        val EMAIL = Regex("""^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""")

        /**
         * Hash neexistujícího účtu. Ověřuje se proti němu, aby přihlášení trvalo stejně dlouho
         * bez ohledu na to, jestli e-mail známe. Heslo k němu nikdo nezná — je to náhodný salt.
         */
        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZS1zYWx0LTE2Ynl0ZQ\$" +
                "Y2hhcmFjdGVycy10aGF0LWFyZS1uZXZlci1hLXZhbGlkLWhhc2g"
    }
}

/** Adresy v console, na které se odkazuje z e-mailů. */
class ConsoleLinks(
    baseUrl: String,
) {
    private val base = baseUrl.trimEnd('/')

    fun emailVerification(token: SecretPayload): String = "$base/overeni?token=${token.value}"

    fun passwordReset(token: SecretPayload): String = "$base/obnova-hesla?token=${token.value}"

    fun invitation(token: SecretPayload): String = "$base/pozvanka?token=${token.value}"
}
