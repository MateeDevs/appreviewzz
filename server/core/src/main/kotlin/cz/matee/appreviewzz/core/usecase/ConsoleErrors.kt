package cz.matee.appreviewzz.core.usecase

/**
 * Důvody, proč console odmítla operaci. Jde o hodnotu v poli `error` v odpovědi API —
 * console podle ní volí, co uživateli nabídnout, takže se nepřejmenovávají jen tak.
 */
enum class ConsoleFailure {
    /** Zakládat organizaci smí jen ověřený e-mail — jinak by šlo obsadit cizí adresou. */
    EMAIL_NOT_VERIFIED,
    SLUG_TAKEN,
    INVALID_INPUT,

    /** Uživatel je členem, ale nemá dost vysokou roli. */
    FORBIDDEN,
    NOT_FOUND,
    INVITATION_INVALID,

    /** Poslední OWNER se nedá odebrat ani degradovat; organizace by zůstala bez správce. */
    LAST_OWNER,

    /**
     * Funkce existuje, ale provozovatel platformy ji ještě nenastavil (chybí klíč provisioneru).
     * Klient s tím nic neudělá — console proto ukazuje jinou větu než u vlastní chyby.
     */
    NOT_CONFIGURED,
}

class ConsoleException(
    val failure: ConsoleFailure,
    message: String,
) : RuntimeException(message)
