package cz.matee.appreviewzz.channels.teams

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/** Proč jsme aktivitu odmítli. Do odpovědi nejde nic z toho — jen do logu. */
enum class TeamsAuthFailure {
    MISSING_TOKEN,
    MALFORMED_TOKEN,
    UNSUPPORTED_ALGORITHM,
    WRONG_ISSUER,

    /** Token je vystavený pro jiného bota — typicky cizí instalace míří na náš endpoint. */
    WRONG_AUDIENCE,

    /** `serviceUrl` v tokenu nesedí s tím v těle: klasický pokus přesměrovat odpovědi jinam. */
    SERVICE_URL_MISMATCH,
    EXPIRED,
    NOT_YET_VALID,
    UNKNOWN_KEY,

    /** Klíč není endorsovaný pro kanál, ze kterého zpráva přišla. */
    CHANNEL_NOT_ENDORSED,
    BAD_SIGNATURE,

    /** Metadata Bot Connectoru se nepodařilo stáhnout — ověřit se nedá, takže se nevěří. */
    METADATA_UNAVAILABLE,
}

/**
 * Ověření, že příchozí aktivita opravdu přišla od Bot Connectoru (plán §5.5).
 *
 * Je to **jediná autentizace, kterou messaging endpoint má** — proto se dělá nad syrovým tělem
 * a na nic z těla se do té doby nesahá. Postup je stejný jako v dnešním n8n code nodu, jen se
 * třemi rozdíly:
 *
 * - **JWKS se cachuje** (n8n si ho stahuje při každém requestu — dvě volání navíc na každé
 *   kliknutí a výpadek Microsoftu rovnou znamená nedoručenou odpověď).
 * - **`aud` se porovnává s konfigurací**, ne s konstantou v kódu.
 * - **Podporované algoritmy se berou z metadat**; n8n je kvůli chybě v merge nikdy nedostal
 *   a jel natvrdo na RS256.
 */
class BotFrameworkAuthenticator(
    private val httpClient: HttpClient,
    private val bot: TeamsBotIdentity,
    private val clock: Clock = Clock.System,
    private val metadataUrl: String = OPEN_ID_METADATA_URL,
    private val cacheLifetime: Duration = DEFAULT_CACHE_LIFETIME,
    private val clockSkew: Duration = DEFAULT_CLOCK_SKEW,
) {
    private val cached = AtomicReference<CachedKeys?>(null)

    /**
     * Vrátí `null`, když je aktivita v pořádku, jinak důvod odmítnutí. Stejná konvence jako
     * u slackové verifikace, aby se volající obou choval stejně.
     */
    suspend fun verify(
        authorization: String?,
        activity: TeamsActivity,
    ): TeamsAuthFailure? {
        val token =
            authorization
                ?.trim()
                ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return TeamsAuthFailure.MISSING_TOKEN

        val parts = token.split('.')
        if (parts.size != JWT_PARTS) return TeamsAuthFailure.MALFORMED_TOKEN
        val header = parts[0].decodeJsonPart() ?: return TeamsAuthFailure.MALFORMED_TOKEN
        val claims = parts[1].decodeJsonPart() ?: return TeamsAuthFailure.MALFORMED_TOKEN

        val algorithm = header.string("alg") ?: return TeamsAuthFailure.MALFORMED_TOKEN
        val keyId = header.string("kid") ?: return TeamsAuthFailure.MALFORMED_TOKEN
        val keys = signingKeys() ?: return TeamsAuthFailure.METADATA_UNAVAILABLE
        if (algorithm !in keys.algorithms) return TeamsAuthFailure.UNSUPPORTED_ALGORITHM

        claimFailure(claims, activity)?.let { return it }

        val key = keys.byId[keyId] ?: return TeamsAuthFailure.UNKNOWN_KEY
        val endorsements = key.endorsements
        if (endorsements.isNotEmpty() && activity.channelId != null && activity.channelId !in endorsements) {
            return TeamsAuthFailure.CHANNEL_NOT_ENDORSED
        }

        val signed = "${parts[0]}.${parts[1]}"
        val signature = runCatching { parts[2].decodeBase64Url() }.getOrElse { return TeamsAuthFailure.MALFORMED_TOKEN }
        return if (key.verifies(signed, signature)) null else TeamsAuthFailure.BAD_SIGNATURE
    }

    private fun claimFailure(
        claims: JsonObject,
        activity: TeamsActivity,
    ): TeamsAuthFailure? {
        if (claims.string("iss") != BOT_CONNECTOR_ISSUER) return TeamsAuthFailure.WRONG_ISSUER
        if (claims.string("aud") != bot.appId) return TeamsAuthFailure.WRONG_AUDIENCE

        // serviceUrl v tokenu je ochrana proti přesměrování odpovědí na cizí endpoint: bez ní
        // by stačilo poslat platný token s vlastním serviceUrl v těle.
        val tokenServiceUrl = claims.string("serviceurl") ?: claims.string("serviceUrl")
        val activityServiceUrl = activity.serviceUrl
        if (tokenServiceUrl != null &&
            activityServiceUrl != null &&
            tokenServiceUrl.trimEnd('/') != activityServiceUrl.trimEnd('/')
        ) {
            return TeamsAuthFailure.SERVICE_URL_MISMATCH
        }

        val now = clock.now()
        claims.epochSeconds("nbf")?.let { if (now + clockSkew < it) return TeamsAuthFailure.NOT_YET_VALID }
        claims.epochSeconds("exp")?.let { if (now - clockSkew > it) return TeamsAuthFailure.EXPIRED }
        return null
    }

    /**
     * Podepisovací klíče Bot Connectoru. Cachují se, protože se mění řádově v měsících —
     * a protože bez nich neprojde ani jedna odpověď, což je horší než mírně starý klíč.
     */
    private suspend fun signingKeys(): CachedKeys? {
        cached.get()?.takeIf { it.freshAt + cacheLifetime > clock.now() }?.let { return it }
        val fetched = fetchKeys() ?: return cached.get()
        cached.set(fetched)
        return fetched
    }

    private suspend fun fetchKeys(): CachedKeys? =
        try {
            val metadata = json.parseToJsonElement(httpClient.get(metadataUrl).bodyAsText()).jsonObject
            val jwksUri =
                metadata.string("jwks_uri")
                    ?: error("OpenID metadata Bot Connectoru neobsahují jwks_uri")
            val algorithms =
                metadata["id_token_signing_alg_values_supported"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: setOf(DEFAULT_ALGORITHM)
            val keys =
                json
                    .parseToJsonElement(httpClient.get(jwksUri).bodyAsText())
                    .jsonObject["keys"]
                    ?.jsonArray
                    ?.mapNotNull { SigningKey.of(it.jsonObject) }
                    .orEmpty()
            if (keys.isEmpty()) error("JWKS Bot Connectoru je prázdné")
            logger.info { "Načteno ${keys.size} podepisovacích klíčů Bot Connectoru" }
            CachedKeys(keys.associateBy { it.id }, algorithms, clock.now())
        } catch (error: Exception) {
            // Stará cache je pořád lepší než odmítnout všechny odpovědi kvůli výpadku metadat.
            logger.warn(error) { "Podepisovací klíče Bot Connectoru se nepodařilo načíst" }
            null
        }

    private class CachedKeys(
        val byId: Map<String, SigningKey>,
        val algorithms: Set<String>,
        val freshAt: Instant,
    )

    private class SigningKey(
        val id: String,
        private val modulus: BigInteger,
        private val exponent: BigInteger,
        val endorsements: Set<String>,
    ) {
        fun verifies(
            signed: String,
            signature: ByteArray,
        ): Boolean =
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(key)
                    update(signed.toByteArray(Charsets.US_ASCII))
                    verify(signature)
                }
            }.getOrElse {
                logger.warn(it) { "Podpis aktivity se nepodařilo ověřit" }
                false
            }

        companion object {
            fun of(jwk: JsonObject): SigningKey? {
                val id = jwk.string("kid") ?: return null
                val modulus = jwk.string("n") ?: return null
                val exponent = jwk.string("e") ?: return null
                return runCatching {
                    SigningKey(
                        id = id,
                        modulus = BigInteger(1, modulus.decodeBase64Url()),
                        exponent = BigInteger(1, exponent.decodeBase64Url()),
                        endorsements =
                            jwk["endorsements"]
                                ?.jsonArray
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                ?.toSet()
                                .orEmpty(),
                    )
                }.getOrNull()
            }
        }
    }

    companion object {
        /** Metadata Bot Connectoru; odsud vede cesta k JWKS. */
        const val OPEN_ID_METADATA_URL = "https://login.botframework.com/v1/.well-known/openidconfiguration"
        const val BOT_CONNECTOR_ISSUER = "https://api.botframework.com"

        private const val BEARER_PREFIX = "Bearer "
        private const val JWT_PARTS = 3
        private const val DEFAULT_ALGORITHM = "RS256"
        private val DEFAULT_CACHE_LIFETIME = 24.hours
        private val DEFAULT_CLOCK_SKEW = 5.minutes
    }
}

private fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

private fun String.decodeJsonPart(): JsonObject? =
    runCatching { json.parseToJsonElement(String(decodeBase64Url(), Charsets.UTF_8)).jsonObject }.getOrNull()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.epochSeconds(key: String): Instant? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
        ?.let { Instant.fromEpochSeconds(it) }
