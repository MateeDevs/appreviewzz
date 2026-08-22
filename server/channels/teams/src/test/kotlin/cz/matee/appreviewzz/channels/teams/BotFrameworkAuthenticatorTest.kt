package cz.matee.appreviewzz.channels.teams

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val NOW = Instant.parse("2026-08-21T10:00:00Z")
private const val KEY_ID = "test-key-1"
private const val JWKS_URI = "https://login.botframework.com/v1/.well-known/keys"

private val fixedClock =
    object : Clock {
        override fun now(): Instant = NOW
    }

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.base64Url(): String = toByteArray(Charsets.UTF_8).base64Url()

private class TestKey(
    val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
) {
    fun jwks(endorsements: List<String> = listOf("msteams")): String {
        val public = pair.public as RSAPublicKey
        val endorsed = endorsements.joinToString(",") { "\"$it\"" }
        return """
            { "keys": [ {
                "kty": "RSA",
                "kid": "$KEY_ID",
                "n": "${public.modulus.toByteArray().dropLeadingZero().base64Url()}",
                "e": "${public.publicExponent.toByteArray().dropLeadingZero().base64Url()}",
                "endorsements": [$endorsed]
            } ] }
            """.trimIndent()
    }

    fun token(
        audience: String = BOT_APP_ID,
        issuer: String = BotFrameworkAuthenticator.BOT_CONNECTOR_ISSUER,
        serviceUrl: String? = SERVICE_URL,
        notBefore: Instant = NOW - 1.hours,
        expiresAt: Instant = NOW + 1.hours,
        keyId: String = KEY_ID,
        algorithm: String = "RS256",
        tamper: Boolean = false,
    ): String {
        val header = """{"alg":"$algorithm","kid":"$keyId","typ":"JWT"}""".base64Url()
        val claims =
            buildString {
                append("""{"iss":"$issuer","aud":"$audience"""")
                serviceUrl?.let { append(""","serviceurl":"$it"""") }
                append(""","nbf":${notBefore.epochSeconds},"exp":${expiresAt.epochSeconds}}""")
            }.base64Url()
        val signed = "$header.$claims"
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private as RSAPrivateKey)
                update(signed.toByteArray(Charsets.US_ASCII))
                sign()
            }
        val encoded = if (tamper) (signature.also { it[0] = (it[0] + 1).toByte() }) else signature
        return "$signed.${encoded.base64Url()}"
    }
}

/** BigInteger.toByteArray() přidává vedoucí nulu kvůli znaménku; JWK ji nemá. */
private fun ByteArray.dropLeadingZero(): ByteArray = if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this

private fun activity(serviceUrl: String? = SERVICE_URL): TeamsActivity =
    TeamsActivity(
        type = "message",
        id = "f:1234",
        channelId = "msteams",
        serviceUrl = serviceUrl,
        conversationId = "19:abc@thread.tacv2",
        replyToId = "1",
        tenantId = TENANT_ID,
        fromId = "29:user",
        fromName = "Tadeáš",
        value = null,
    )

class BotFrameworkAuthenticatorTest :
    FunSpec({
        val key = TestKey()

        fun authenticator(
            engine: RecordingEngine,
            bot: TeamsBotIdentity = BOT,
        ) = BotFrameworkAuthenticator(engine.client(), bot, clock = fixedClock)

        fun metadataEngine(jwks: String = key.jwks()) =
            RecordingEngine { request ->
                if (request.url.toString().contains("openidconfiguration")) {
                    respondJson(
                        """{"issuer":"${BotFrameworkAuthenticator.BOT_CONNECTOR_ISSUER}","jwks_uri":"$JWKS_URI",""" +
                            """"id_token_signing_alg_values_supported":["RS256"]}""",
                    )
                } else {
                    respondJson(jwks)
                }
            }

        test("platný token od Bot Connectoru projde") {
            authenticator(metadataEngine()).verify("Bearer ${key.token()}", activity()).shouldBeNull()
        }

        test("bez hlavičky Authorization se nic nepublikuje") {
            authenticator(metadataEngine()).verify(null, activity()) shouldBe TeamsAuthFailure.MISSING_TOKEN
            authenticator(metadataEngine()).verify("Basic abc", activity()) shouldBe TeamsAuthFailure.MISSING_TOKEN
            authenticator(metadataEngine()).verify("Bearer neni.jwt", activity()) shouldBe TeamsAuthFailure.MALFORMED_TOKEN
        }

        test("token pro jiného bota nepatří nám") {
            val failure = authenticator(metadataEngine()).verify("Bearer ${key.token(audience = "cizi-bot")}", activity())

            failure shouldBe TeamsAuthFailure.WRONG_AUDIENCE
        }

        test("vystavitel musí být Bot Connector, ne kdokoli s platným podpisem") {
            val token = key.token(issuer = "https://login.microsoftonline.com/common")

            authenticator(metadataEngine()).verify("Bearer $token", activity()) shouldBe TeamsAuthFailure.WRONG_ISSUER
        }

        test("serviceUrl v tokenu musí sedět s tělem — jinak by šly odpovědi jinam") {
            val token = key.token(serviceUrl = "https://utocnik.example.com")

            authenticator(metadataEngine()).verify("Bearer $token", activity()) shouldBe TeamsAuthFailure.SERVICE_URL_MISMATCH
        }

        test("propadlý i budoucí token se odmítne, tolerance je pět minut") {
            val expired = key.token(expiresAt = NOW - 10.hours, notBefore = NOW - 11.hours)
            val future = key.token(notBefore = NOW + 10.hours, expiresAt = NOW + 11.hours)

            authenticator(metadataEngine()).verify("Bearer $expired", activity()) shouldBe TeamsAuthFailure.EXPIRED
            authenticator(metadataEngine()).verify("Bearer $future", activity()) shouldBe TeamsAuthFailure.NOT_YET_VALID
        }

        test("neznámý kid ani přehozený podpis neprojdou") {
            val unknownKey = key.token(keyId = "jiny-kid")
            val tampered = key.token(tamper = true)

            authenticator(metadataEngine()).verify("Bearer $unknownKey", activity()) shouldBe TeamsAuthFailure.UNKNOWN_KEY
            authenticator(metadataEngine()).verify("Bearer $tampered", activity()) shouldBe TeamsAuthFailure.BAD_SIGNATURE
        }

        test("klíč endorsovaný pro jiný kanál na Teams nestačí") {
            val engine = metadataEngine(key.jwks(endorsements = listOf("webchat")))

            authenticator(engine).verify("Bearer ${key.token()}", activity()) shouldBe TeamsAuthFailure.CHANNEL_NOT_ENDORSED
        }

        test("nepodporovaný algoritmus se odmítne, i když je podpis platný") {
            val token = key.token(algorithm = "HS256")

            authenticator(metadataEngine()).verify("Bearer $token", activity()) shouldBe TeamsAuthFailure.UNSUPPORTED_ALGORITHM
        }

        test("bez metadat se nevěří ničemu") {
            val engine = RecordingEngine { respondError(HttpStatusCode.ServiceUnavailable) }

            authenticator(engine).verify("Bearer ${key.token()}", activity()) shouldBe TeamsAuthFailure.METADATA_UNAVAILABLE
        }

        test("klíče se cachují — každé kliknutí nesmí znamenat dvě volání k Microsoftu") {
            val engine = metadataEngine()
            val authenticator = authenticator(engine)

            authenticator.verify("Bearer ${key.token()}", activity()).shouldBeNull()
            authenticator.verify("Bearer ${key.token()}", activity()).shouldBeNull()

            // Dvě volání celkem (metadata + JWKS), ne čtyři: n8n je stahuje při každém requestu.
            engine.requests.size shouldBe 2
        }
    })
