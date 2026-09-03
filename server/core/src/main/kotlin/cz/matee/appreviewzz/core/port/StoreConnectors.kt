package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.ObservedRatings
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

/**
 * Zdroj hodnocení aplikace (plán §5.4). Oddělený od [ReviewSource] schválně: hodnocení se
 * čtou jednou denně, z jiných endpointů a někdy úplně bez credentialu (veřejný listing).
 *
 * Zdrojů může být pro jednu platformu **víc a mají pořadí**: oficiální data (iTunes lookup,
 * Play Console export) mají přednost, veřejný scrape je fallback pro klienty, kde na oficiální
 * data nemáme přístup. Dnešní n8n má obojí taky, ale scrape větev nikdo nevolá a pro klienta
 * bez Play Console tak digest prostě nechodí.
 */
interface RatingsSource {
    val platform: Platform

    /** Čím vyšší, tím dřív se zkusí. Oficiální data mají přednost před scrapem. */
    val priority: Int

    /**
     * Stáhne hodnocení. Prázdný seznam znamená „tenhle zdroj pro tuhle appku nemá data"
     * (typicky chybějící nastavení) — volající pak zkusí další v pořadí; chyba storu se hlásí
     * výjimkou, protože ta se má propsat do delivery health, ne tiše přeskočit.
     */
    suspend fun fetchRatings(context: RatingsContext): List<ObservedRatings>
}

/**
 * Co konektor potřebuje ke stažení hodnocení. Credential je `null` u zdrojů, které čtou
 * veřejný listing — a je to tak správně: kvůli dennímu průměru nemá smysl rozbalovat klíč.
 */
data class RatingsContext(
    val appIdentifier: String,
    val credential: SecretPayload? = null,
    /**
     * Bucket s reportingem Play Console (`pubsite_prod_…`). Odvodit se nedá, klient ho opisuje
     * z Play Console; bez něj se na oficiální Android data nedostaneme.
     */
    val reportingBucket: String? = null,
    /** Storefronty, ze kterých se čtou iOS hodnocení. Prázdné = výchozí seznam konektoru. */
    val territories: List<String> = emptyList(),
)

/**
 * Jak dopadl pokus sáhnout do reportingového bucketu. Není to `valid/invalid`, protože
 * „do bucketu vidíme, ale export tam není" je jiná situace než „nemáme oprávnění" — klient
 * podle toho dělá jiný krok a u prvního má smysl hodnotu i tak uložit.
 */
enum class ReportingBucketStatus {
    /** Export hodnocení pro tuhle appku v bucketu leží a čteme ho. */
    OK,

    /** Do bucketu se dostaneme, ale hledaný export v něm není. */
    NO_EXPORT,

    /** Bucket existuje, ale náš účet k němu nemá práva (nebo se ještě nepropsala). */
    DENIED,

    /** Takový bucket není — typicky překlep nebo useknuté URI. */
    MISSING,

    /** Cloud Storage teď neodpovídá. O nastavení to neříká nic. */
    UNAVAILABLE,
}

/** Výsledek ověření bucketu i s větou pro klienta. Zpráva jde do console tak, jak je. */
data class ReportingBucketCheck(
    val status: ReportingBucketStatus,
    val message: String,
) {
    /** Nastavení dává smysl uložit i u [ReportingBucketStatus.NO_EXPORT] — export může přibýt. */
    val worthSaving: Boolean get() = status == ReportingBucketStatus.OK || status == ReportingBucketStatus.NO_EXPORT
}

/**
 * Ověření reportingového bucketu ještě u dialogu, ne až prvním nočním během.
 *
 * Bucket klient opisuje z cizí konzole a k datům se kromě správného jména musí dostat i **náš**
 * service account — na to Play Console právy nedosáhne, roli na bucketu přidává člověk zvlášť.
 * To jsou dvě věci na dvou místech, takže je to nejčastější místo, kde onboarding tiše skončí
 * u scrapu, aniž by o tom kdokoli věděl.
 */
interface ReportingBucketProbe {
    val platform: Platform

    suspend fun checkAccess(
        bucket: String,
        appIdentifier: String,
        credential: SecretPayload,
    ): ReportingBucketCheck
}

/**
 * Veřejný listing appky ve storu — to málo, co jde zjistit bez klíče, když si klient
 * v consoli přidává aplikaci: **jak se jmenuje**.
 *
 * Je to schválně jiné rozhraní než [RatingsSource], i když se čte tatáž stránka: tohle běží
 * interaktivně proti klientovi, který čeká u dialogu, a jeho selhání nic neshodí — jméno
 * si prostě napíše sám.
 */
interface AppListingSource {
    val platform: Platform

    /** Jméno appky ve storu, nebo `null`, když store takový identifikátor nezná. */
    suspend fun fetchName(identifier: String): String?
}

/**
 * Aplikace, kterou klíč vidí v účtu storu. `identifier` je to, čím se appka jmenuje v našem
 * modelu ([cz.matee.appreviewzz.core.model.App.storeIdentifier]) — u App Store Connect
 * tedy Apple ID aplikace.
 */
data class StoreApp(
    val identifier: String,
    val name: String,
    val bundleId: String?,
)

/**
 * Výpis aplikací, na které klíč dosáhne. Existuje kvůli onboardingu: týmový klíč App Store
 * Connect vidí všechny aplikace týmu, takže si klient místo opisování Apple ID z konzole
 * jen odklikne appky ze seznamu.
 *
 * Google Play nic takového nemá (žádný endpoint na výpis účtu) — proto vlastní rozhraní
 * a ne metoda na [ReviewSource], kterou by polovina konektorů musela odmítat.
 */
interface StoreAppCatalog {
    val platform: Platform

    suspend fun listApps(credential: SecretPayload): List<StoreApp>
}

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

/**
 * Dotažení **jedné konkrétní** recenze podle jejího ID ve storu.
 *
 * Existuje kvůli Google Play: `reviews.list` vrací jen ~týden zpět, takže odpověď, kterou
 * někdo napíše v Play Console později, projde ingestu pod rukama a recenze u nás zůstane
 * navždy „čeká na odpověď". `reviews.get` tohle okno **nemá** — ověřeno proti ostrému API
 * na dva roky staré recenzi; dokumentace omezení uvádí jen u výpisu. ID recenze už v
 * databázi máme, takže na dohledání stačí.
 *
 * Schválně jiné rozhraní než [ReviewSource]: App Store Connect tohle nepotřebuje, protože
 * historii vrací celou.
 */
interface ReviewRefreshSource {
    val platform: Platform

    /** `null`, když store recenzi nezná — smazaná autorem, nebo klíč od jiného účtu. */
    suspend fun fetchReview(
        context: StoreContext,
        storeReviewId: String,
    ): ObservedReview?
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
