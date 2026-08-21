package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.port.ChannelErrorKind
import cz.matee.appreviewzz.core.port.ChannelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode

class TeamsTokensTest :
    FunSpec({
        test("token se vydá jednou a pak se bere z cache") {
            val engine = RecordingEngine { tokenResponse() }
            val tokens = TeamsTokens(engine.client())

            tokens.accessToken(BOT) shouldBe "bot-token"
            tokens.accessToken(BOT) shouldBe "bot-token"

            // n8n si o token říká při každém requestu; hodinový token to nepotřebuje.
            engine.requests.size shouldBe 1
            val body =
                String(
                    engine.requests
                        .single()
                        .body
                        .toByteArray(),
                )
            body shouldContain "grant_type=client_credentials"
            body shouldContain "scope=https%3A%2F%2Fapi.botframework.com%2F.default"
            engine.requests
                .single()
                .url
                .toString() shouldContain "/$TENANT_ID/oauth2/v2.0/token"
        }

        test("po zneplatnění se řekne o nový") {
            val engine = RecordingEngine { tokenResponse() }
            val tokens = TeamsTokens(engine.client())

            tokens.accessToken(BOT)
            tokens.invalidate(BOT)
            tokens.accessToken(BOT)

            engine.requests.size shouldBe 2
        }

        test("multi-tenant bot si říká u botframework.com, ne u tenantu klienta") {
            val engine = RecordingEngine { tokenResponse() }

            TeamsTokens(engine.client()).accessToken(BOT.copy(tenantId = null))

            engine.requests
                .single()
                .url
                .toString() shouldContain "/botframework.com/oauth2/v2.0/token"
        }

        test("špatný secret je chyba pro člověka a nesmí se objevit v hlášce") {
            val engine =
                RecordingEngine {
                    respondError(
                        HttpStatusCode.Unauthorized,
                        """{"error":"invalid_client","error_description":"AADSTS7000215: Invalid client secret provided."}""",
                        headers = jsonHeaders,
                    )
                }

            val error = shouldThrow<ChannelException> { TeamsTokens(engine.client()).accessToken(BOT) }

            error.kind shouldBe ChannelErrorKind.AUTH
            error.message!! shouldContain "AADSTS7000215"
            error.message!! shouldNotContain "tajne-heslo"
        }

        test("výpadek Entry se zkusí znovu") {
            val engine = RecordingEngine { respondError(HttpStatusCode.ServiceUnavailable) }

            shouldThrow<ChannelException> { TeamsTokens(engine.client()).accessToken(BOT) }
                .kind shouldBe ChannelErrorKind.TRANSIENT
        }
    })
