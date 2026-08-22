package cz.matee.appreviewzz.core.usecase

import cz.matee.appreviewzz.core.model.MfaStatus
import cz.matee.appreviewzz.core.model.RecoveryCodes
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Totp
import cz.matee.appreviewzz.core.model.User
import cz.matee.appreviewzz.core.model.UserId
import cz.matee.appreviewzz.core.port.UserMfaRepository
import cz.matee.appreviewzz.core.port.UserRepository
import cz.matee.appreviewzz.core.port.UserSecretVault
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** AAD zapečetěného tajemství — tentýž řádek nejde použít v jiné roli. */
const val TOTP_SECRET_PURPOSE = "totp"

/**
 * Co člověk uvidí při zapínání druhého faktoru. **Jediná chvíle, kdy tajemství opouští
 * server v otevřené podobě** — proto se nikam neloguje a console ho po potvrzení zahodí.
 */
data class TotpSetup(
    val secret: SecretPayload,
    val provisioningUri: String,
)

/**
 * Druhý faktor přihlášení do console (F5.3).
 *
 * Postup je záměrně dvoukrokový: `startSetup` tajemství jen vyrobí a uloží **nepotvrzené**,
 * teprve `confirmSetup` s opsaným kódem ho zapne. Bez toho by šlo zamknout se z vlastního
 * účtu tím, že člověk zavře okno dřív, než si QR kód naskenuje.
 *
 * Záchranné kódy vznikají až s potvrzením a ukazují se jednou. Jsou to jediná dvířka zpátky
 * do účtu po ztrátě telefonu — bez nich by odemčení znamenalo ruční zásah v databázi.
 */
class MfaService(
    private val mfa: UserMfaRepository,
    private val vault: UserSecretVault,
    private val users: UserRepository,
    /** Jméno, které se ukáže v autentizační appce vedle e-mailu. */
    private val issuer: String = "appreviewzz",
    private val clock: Clock = Clock.System,
) {
    fun status(userId: UserId): MfaStatus {
        val record = mfa.find(userId)
        return MfaStatus(
            enabled = record?.enabled == true,
            setupPending = record != null && !record.enabled,
            remainingRecoveryCodes = if (record?.enabled == true) mfa.remainingRecoveryCodes(userId) else 0,
        )
    }

    fun isEnabled(userId: UserId): Boolean = mfa.find(userId)?.enabled == true

    /**
     * Nové tajemství. Zapnutý druhý faktor se nepřepisuje — jinak by stačila ukradená relace
     * k tomu, aby útočník navázal vlastní appku a majitele odstřihl.
     */
    fun startSetup(user: User): TotpSetup {
        if (isEnabled(user.id)) {
            throw AuthException(AuthFailure.MFA_ALREADY_ENABLED, "Druhý faktor už je zapnutý — nejdřív ho vypni")
        }
        val secret = Totp.generateSecret()
        mfa.startSetup(user.id, vault.seal(user.id, TOTP_SECRET_PURPOSE, secret), clock.now())
        logger.info { "Uživatel ${user.id} začal nastavovat druhý faktor" }
        return TotpSetup(secret, Totp.provisioningUri(issuer, user.email, secret))
    }

    /** @return záchranné kódy; ukazují se jednou a v databázi zůstane jen otisk. */
    fun confirmSetup(
        userId: UserId,
        code: String,
    ): List<String> {
        val record =
            mfa.find(userId)
                ?: throw AuthException(AuthFailure.MFA_NOT_SET_UP, "Nastavení druhého faktoru není rozdělané")
        if (record.enabled) {
            throw AuthException(AuthFailure.MFA_ALREADY_ENABLED, "Druhý faktor už je zapnutý")
        }

        val now = clock.now()
        val secret = vault.open(userId, TOTP_SECRET_PURPOSE, record.secret)
        val step =
            Totp.matchingStep(secret, code, now)
                ?: throw AuthException(AuthFailure.MFA_INVALID_CODE, "Kód nesouhlasí — zkontroluj čas na telefonu")

        mfa.confirm(userId, now, step)
        val codes = RecoveryCodes.generate()
        mfa.replaceRecoveryCodes(userId, codes.map(RecoveryCodes::hash), now)
        logger.info { "Uživatel $userId zapnul druhý faktor" }
        return codes
    }

    /**
     * Nové záchranné kódy. Ty staré přestanou platit okamžitě — proto se to dělá až po tom,
     * co člověk prokáže, že má telefon (kód se ověřuje ve volajícím).
     */
    fun regenerateRecoveryCodes(userId: UserId): List<String> {
        if (!isEnabled(userId)) {
            throw AuthException(AuthFailure.MFA_NOT_SET_UP, "Druhý faktor není zapnutý")
        }
        val codes = RecoveryCodes.generate()
        mfa.replaceRecoveryCodes(userId, codes.map(RecoveryCodes::hash), clock.now())
        logger.info { "Uživatel $userId si nechal vygenerovat nové záchranné kódy" }
        return codes
    }

    fun disable(userId: UserId) {
        mfa.delete(userId)
        logger.info { "Uživatel $userId vypnul druhý faktor" }
    }

    /**
     * Ověření při přihlášení. Bere kód z appky **i** záchranný kód: člověk, který zrovna
     * nemá telefon, jinak nemá jak dovnitř a rozlišovat to dvěma poli je zbytečný krok.
     *
     * Uplatněný kód se zapíše — tentýž už podruhé neprojde, ani v okně své platnosti.
     */
    fun verify(
        userId: UserId,
        code: String,
    ): Boolean {
        val record = mfa.find(userId) ?: return false
        if (!record.enabled) return false
        val now = clock.now()

        val secret = vault.open(userId, TOTP_SECRET_PURPOSE, record.secret)
        val step = Totp.matchingStep(secret, code, now, record.lastStep)
        if (step != null) {
            mfa.recordStep(userId, step)
            return true
        }

        val used = mfa.consumeRecoveryCode(userId, RecoveryCodes.hash(code), now)
        if (used) {
            val left = mfa.remainingRecoveryCodes(userId)
            logger.warn { "Uživatel $userId se přihlásil záchranným kódem, zbývá jich $left" }
        }
        return used
    }

    /** Kdo je vlastníkem účtu — použije se při dokončení přihlášení. */
    fun userOf(userId: UserId): User? = users.findById(userId)
}
