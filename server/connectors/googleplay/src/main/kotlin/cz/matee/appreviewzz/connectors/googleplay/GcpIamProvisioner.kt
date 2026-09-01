package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * Service account, který jsme právě vyrobili pro jednu organizaci.
 *
 * `key` je celý JSON, jaký by si klient jinak stahoval z Google Cloud konzole — jde rovnou
 * do vaultu a víc se ho nikdo nedotkne. `email` je to jediné, co smí ven: klient ho vloží
 * do Play Console jako pozvánku.
 */
class ProvisionedServiceAccount(
    val email: String,
    val key: SecretPayload,
) {
    override fun toString(): String = "ProvisionedServiceAccount(email=$email)"
}

/**
 * Výroba service accountů v **našem** GCP projektu ([plán onboardingu, varianta B]).
 *
 * Klient tak nezakládá nic v Google Cloudu a nenahrává žádný soubor — dostane od nás e-mail
 * a jen ho pozve do Play Console. Play Console od podzimu 2023 nevyžaduje, aby byl service
 * account z projektu propojeného s Play účtem, takže tenhle zkrat funguje.
 *
 * Provisioner je jeden pro celou platformu (`roles/iam.serviceAccountAdmin` +
 * `…KeyAdmin` nad projektem); klíče, které vyrobí, patří jednotlivým organizacím a leží
 * v jejich vaultu. Provisioner sám se sem dostane jako [GoogleServiceAccount] z platformního
 * tajemství — v datech klienta nikdy není.
 */
class GcpIamProvisioner(
    private val httpClient: HttpClient,
    private val oauth: GoogleOAuth = GoogleOAuth(httpClient),
    private val baseUrl: String = IAM_BASE_URL,
    /** Prodleva mezi pokusy o klíč. Testy si ji nulují, ať nečekají na propsání účtu. */
    private val retryDelay: Duration = KEY_RETRY_DELAY,
) {
    /**
     * Založí service account pojmenovaný podle organizace a hned k němu vyrobí klíč.
     *
     * `accountId` musí být 6–30 znaků `[a-z][a-z0-9-]*` a je v projektu unikátní; ze slugu
     * organizace se proto odvozuje přes [accountIdOf] a při kolizi se zkouší s příponou —
     * dvě organizace se slugem, který se po ořezání sejde, se jinak zablokují navzájem.
     */
    suspend fun provision(
        provisioner: GoogleServiceAccount,
        projectId: String,
        orgSlug: String,
        displayName: String,
    ): ProvisionedServiceAccount {
        val token = oauth.accessToken(provisioner, CLOUD_PLATFORM_SCOPE)
        val account = createAccount(token, projectId, orgSlug, displayName)
        val key =
            try {
                createKey(token, projectId, account)
            } catch (error: StoreConnectorException) {
                // Účet bez klíče je k ničemu a jméno by blokoval: další pokus by kvůli němu
                // založil `…-1` a v projektu by přibývaly mrtvé účty, dokud nedojde kvóta.
                deleteAccount(token, projectId, account.email)
                throw error
            }
        logger.info { "Service account ${account.email} vyrobený pro organizaci $orgSlug v projektu $projectId" }
        return ProvisionedServiceAccount(account.email, key)
    }

    private suspend fun createAccount(
        token: String,
        projectId: String,
        orgSlug: String,
        displayName: String,
    ): IamServiceAccountDto {
        val base = accountIdOf(orgSlug)
        // Pár pokusů stačí: kolize je vzácná (slug je unikátní) a nekonečná smyčka nad cizím
        // API je horší než čitelná chyba, na kterou se dá reagovat ručně.
        repeat(COLLISION_ATTEMPTS) { attempt ->
            val accountId = if (attempt == 0) base else suffixed(base, attempt)
            val response =
                request {
                    httpClient.post("$baseUrl/v1/projects/$projectId/serviceAccounts") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            CreateIamServiceAccountRequest(
                                accountId = accountId,
                                serviceAccount = IamServiceAccountFields(displayName = displayName.take(DISPLAY_NAME_LIMIT)),
                            ),
                        )
                    }
                }
            if (response.status != HttpStatusCode.Conflict) {
                return response.body<IamServiceAccountDto>()
            }
            logger.info { "Service account $accountId v projektu $projectId už existuje, zkouším další jméno" }
        }
        throw StoreConnectorException(
            StoreErrorKind.INVALID_REQUEST,
            "Pro organizaci '$orgSlug' se nepodařilo najít volné jméno service accountu v projektu $projectId",
        )
    }

    /**
     * Klíč k právě založenému účtu.
     *
     * **IAM je eventually consistent**: `POST serviceAccounts` vrátí 200, ale účet ještě chvíli
     * pro další volání neexistuje a klíč spadne na 404. Ověřeno proti ostrému API, ne z
     * dokumentace. Proto se čeká a zkouší znovu — a adresuje se `uniqueId`, který se propisuje
     * dřív než e-mail.
     *
     * Smyčka musí skončit **prvním** úspěchem: účet unese jen deset klíčů a jedenáctý pokus
     * vrací `FAILED_PRECONDITION`, tedy hlášku, ze které původní příčinu nikdo nevyčte.
     */
    private suspend fun createKey(
        token: String,
        projectId: String,
        account: IamServiceAccountDto,
    ): SecretPayload {
        val reference = account.uniqueId ?: account.email
        var attempt = 0
        var response: HttpResponse? = null

        while (response == null) {
            if (attempt > 0) delay(retryDelay)
            response =
                try {
                    request {
                        httpClient.post("$baseUrl/v1/projects/$projectId/serviceAccounts/$reference/keys") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(CreateIamKeyRequest())
                        }
                    }
                } catch (error: StoreConnectorException) {
                    if (error.kind != StoreErrorKind.NOT_FOUND) throw error
                    attempt++
                    if (attempt >= KEY_ATTEMPTS) {
                        throw StoreConnectorException(
                            StoreErrorKind.TRANSIENT,
                            "Service account ${account.email} se ani po " +
                                "${KEY_ATTEMPTS * retryDelay.inWholeSeconds} s nepropsal do IAM",
                            error,
                        )
                    }
                    logger.info {
                        "Service account ${account.email} se ještě nepropsal, zkouším klíč znovu ($attempt/$KEY_ATTEMPTS)"
                    }
                    null
                }
        }

        val encoded =
            response.body<IamServiceAccountKeyDto>().privateKeyData
                ?: throw StoreConnectorException(
                    StoreErrorKind.TRANSIENT,
                    "IAM nevrátil obsah klíče service accountu ${account.email}",
                )
        val decoded =
            try {
                Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw StoreConnectorException(
                    StoreErrorKind.TRANSIENT,
                    "Klíč service accountu ${account.email} nejde dekódovat",
                    error,
                )
            }
        // Ověření, že je to opravdu service account JSON: chybu chceme tady, ne až prvním
        // ingestem. Objekt zahazujeme, do vaultu jde původní text.
        GoogleServiceAccount.parse(SecretPayload(decoded))
        return SecretPayload(decoded)
    }

    /** Úklid po nepovedeném založení. Selhání mazání se jen loguje — původní chyba je důležitější. */
    private suspend fun deleteAccount(
        token: String,
        projectId: String,
        email: String,
    ) {
        runCatching {
            request {
                httpClient.delete("$baseUrl/v1/projects/$projectId/serviceAccounts/$email") { bearerAuth(token) }
            }
        }.onFailure { error ->
            logger.warn(error) { "Nepovedený service account $email se nepodařilo uklidit — smaž ho v projektu $projectId ručně" }
        }
    }

    /** Ověření, že provisioner na projekt vůbec vidí — bez toho by se chyba ukázala až klientovi. */
    suspend fun checkAccess(
        provisioner: GoogleServiceAccount,
        projectId: String,
    ) {
        val token = oauth.accessToken(provisioner, CLOUD_PLATFORM_SCOPE)
        request {
            httpClient.get("$baseUrl/v1/projects/$projectId/serviceAccounts") {
                bearerAuth(token)
            }
        }
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse {
        val response =
            try {
                block()
            } catch (error: java.io.IOException) {
                throw StoreConnectorException(StoreErrorKind.TRANSIENT, "IAM API Googlu je nedostupné", error)
            }
        // Konflikt řeší volající (kolize jména) — pro něj to není chyba, ale další pokus.
        if (response.status.isSuccess() || response.status == HttpStatusCode.Conflict) return response

        val detail = response.bodyAsText().take(ERROR_DETAIL_LIMIT)
        val kind =
            when {
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                    StoreErrorKind.AUTH

                response.status == HttpStatusCode.NotFound -> StoreErrorKind.NOT_FOUND
                response.status == HttpStatusCode.TooManyRequests -> StoreErrorKind.RATE_LIMITED
                response.status.value >= HttpStatusCode.InternalServerError.value -> StoreErrorKind.TRANSIENT
                else -> StoreErrorKind.INVALID_REQUEST
            }
        throw StoreConnectorException(kind, "IAM API vrátilo ${response.status.value}: $detail")
    }

    companion object {
        const val IAM_BASE_URL = "https://iam.googleapis.com"
        const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

        private const val MIN_ACCOUNT_ID = 6
        private const val MAX_ACCOUNT_ID = 30
        private const val DISPLAY_NAME_LIMIT = 100
        private const val COLLISION_ATTEMPTS = 5
        private const val ERROR_DETAIL_LIMIT = 300

        /** Zhruba půl minuty čekání na propsání účtu — v praxi to bývá jedna vteřina. */
        private const val KEY_ATTEMPTS = 12
        private val KEY_RETRY_DELAY = 2500.milliseconds

        /**
         * Slug organizace na `accountId`, který IAM přijme: malá písmena, číslice a pomlčky,
         * 6–30 znaků, začíná písmenem. Prefix `arz-` řeší jak krátké slugy, tak ty, co začínají
         * číslicí, a v projektu je zároveň vidět, odkud účet je.
         */
        fun accountIdOf(orgSlug: String): String {
            val body =
                orgSlug
                    .lowercase()
                    .map { if (it.isLetterOrDigit()) it else '-' }
                    .joinToString("")
                    .trim('-')
                    .ifEmpty { "org" }
            return "$PREFIX$body".take(MAX_ACCOUNT_ID).trimEnd('-').padEnd(MIN_ACCOUNT_ID, 'x')
        }

        private fun suffixed(
            base: String,
            attempt: Int,
        ): String {
            val suffix = "-$attempt"
            return base.take(MAX_ACCOUNT_ID - suffix.length).trimEnd('-') + suffix
        }

        private const val PREFIX = "arz-"
    }
}

@Serializable
private data class CreateIamServiceAccountRequest(
    val accountId: String,
    val serviceAccount: IamServiceAccountFields,
)

@Serializable
private data class IamServiceAccountFields(
    val displayName: String,
)

@Serializable
private data class IamServiceAccountDto(
    val email: String,
    val uniqueId: String? = null,
)

/** Prázdné tělo znamená výchozí `TYPE_GOOGLE_CREDENTIALS_FILE`, tedy JSON klíč. */
@Serializable
private data class CreateIamKeyRequest(
    val keyAlgorithm: String = "KEY_ALG_RSA_2048",
)

@Serializable
private data class IamServiceAccountKeyDto(
    @SerialName("privateKeyData") val privateKeyData: String? = null,
)
