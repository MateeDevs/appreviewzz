package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.ObservedReview
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.SecretPayload
import kotlin.time.Instant

/**
 * Vše, co konektor potřebuje k jednomu volání storu: identifikátor aplikace v daném storu
 * (`cz.matee.islegrow`, resp. ASC app ID) a credential rozbalený z vaultu těsně před použitím.
 */
data class StoreContext(
    val appIdentifier: String,
    val credential: SecretPayload,
)

/**
 * Druh selhání storu. Řídí, jestli má smysl to zkusit znovu (`TRANSIENT`, `RATE_LIMITED`),
 * nebo jestli je potřeba člověk (`AUTH`, `NOT_FOUND`, `INVALID_REQUEST`).
 */
enum class StoreErrorKind {
    /** Klíč je neplatný, expirovaný nebo nemá potřebné oprávnění. */
    AUTH,

    /** Aplikace nebo recenze v daném účtu neexistuje. */
    NOT_FOUND,

    /** Store požadavek odmítl — typicky moc dlouhá odpověď nebo špatný formát. */
    INVALID_REQUEST,

    /** Překročený limit; retry s backoffem má smysl. */
    RATE_LIMITED,

    /** Výpadek sítě nebo 5xx na straně storu. */
    TRANSIENT,
}

class StoreConnectorException(
    val kind: StoreErrorKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val isRetryable: Boolean get() = kind == StoreErrorKind.RATE_LIMITED || kind == StoreErrorKind.TRANSIENT
}

/**
 * Výsledek ověření credentialu při onboardingu. Zpráva jde klientovi do console, takže
 * musí být srozumitelná bez znalosti interních věcí — a nesmí obsahovat nic z credentialu.
 */
data class ValidationOutcome(
    val valid: Boolean,
    val message: String? = null,
)

/** Zdroj recenzí jednoho storu. Přidání dalšího storu je implementace tohohle rozhraní. */
interface ReviewSource {
    val platform: Platform

    /**
     * Stáhne recenze, které store zrovna vrací. Stránkování si řeší konektor sám;
     * volající dostane už normalizovaná data.
     */
    suspend fun fetchReviews(context: StoreContext): List<ObservedReview>

    /** Ověřovací volání pro onboarding — nic nemění, jen zjišťuje, jestli klíč funguje. */
    suspend fun validate(context: StoreContext): ValidationOutcome
}

/** Publikace odpovědi zpět do storu. */
interface ReplyTarget {
    val platform: Platform

    /** Google Play 350 znaků, App Store Connect 5 000 — limit vynucujeme před odesláním. */
    val replyMaxLength: Int

    suspend fun publishReply(
        context: StoreContext,
        storeReviewId: String,
        body: String,
    ): PublishedReply
}

data class PublishedReply(
    val body: String,
    val publishedAt: Instant,
)
