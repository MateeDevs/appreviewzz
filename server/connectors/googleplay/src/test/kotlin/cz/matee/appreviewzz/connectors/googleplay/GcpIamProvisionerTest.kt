package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private const val PROJECT = "appreviewzz-connect"

/** Klíč, jaký vrací IAM: service account JSON zabalený v base64. */
private fun keyResponse(email: String): String {
    val key =
        TestServiceAccount
            .payload()
            .value
            .replace(TestServiceAccount.CLIENT_EMAIL, email)
    return """{"name":"projects/$PROJECT/serviceAccounts/$email/keys/abc",""" +
        """"privateKeyData":"${Base64.getEncoder().encodeToString(key.toByteArray())}"}"""
}

class GcpIamProvisionerTest :
    FunSpec({
        val provisionerAccount = GoogleServiceAccount.parse(TestServiceAccount.payload())

        test("vyrobí service account pojmenovaný podle organizace a vrátí jeho klíč") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            val engine =
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/keys") -> respond(keyResponse(email), headers = jsonHeaders)
                        request.url.encodedPath.endsWith("/serviceAccounts") ->
                            respond("""{"email":"$email","uniqueId":"1"}""", headers = jsonHeaders)

                        else -> null
                    }
                }

            val account =
                GcpIamProvisioner(engine.client()).provision(
                    provisioner = provisionerAccount,
                    projectId = PROJECT,
                    orgSlug = "islegrow",
                    displayName = "IsleGrow s.r.o.",
                )

            account.email shouldBe email
            // Do vaultu jde dekódovaný JSON, ne base64 — jinak by ho konektor nepřečetl.
            GoogleServiceAccount.parse(account.key).clientEmail shouldBe email

            val create = engine.requests.first { it.url.encodedPath.endsWith("/serviceAccounts") }
            val body = Json.parseToJsonElement(String(create.body.toByteArray())).jsonObject
            body["accountId"]?.jsonPrimitive?.content shouldBe "arz-islegrow"
            body["serviceAccount"]
                ?.jsonObject
                ?.get("displayName")
                ?.jsonPrimitive
                ?.content shouldBe "IsleGrow s.r.o."
        }

        test("obsazené jméno účtu není chyba — zkusí se další") {
            val email = "arz-islegrow-1@$PROJECT.iam.gserviceaccount.com"
            var attempts = 0
            val engine =
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/keys") -> respond(keyResponse(email), headers = jsonHeaders)
                        request.url.encodedPath.endsWith("/serviceAccounts") -> {
                            attempts++
                            if (attempts == 1) {
                                respond(
                                    """{"error":{"code":409,"message":"already exists"}}""",
                                    status = HttpStatusCode.Conflict,
                                    headers = jsonHeaders,
                                )
                            } else {
                                respond("""{"email":"$email"}""", headers = jsonHeaders)
                            }
                        }

                        else -> null
                    }
                }

            val account =
                GcpIamProvisioner(engine.client()).provision(provisionerAccount, PROJECT, "islegrow", "IsleGrow")

            account.email shouldBe email
            engine.requests.filter { it.url.encodedPath.endsWith("/serviceAccounts") } shouldHaveSize 2
            val second = engine.requests.last { it.url.encodedPath.endsWith("/serviceAccounts") }
            Json
                .parseToJsonElement(String(second.body.toByteArray()))
                .jsonObject["accountId"]
                ?.jsonPrimitive
                ?.content shouldBe "arz-islegrow-1"
        }

        test("odebraná role provisioneru je chyba oprávnění, ne pád") {
            val engine =
                RecordingEngine {
                    respond(
                        """{"error":{"code":403,"message":"Permission iam.serviceAccounts.create denied"}}""",
                        status = HttpStatusCode.Forbidden,
                        headers = jsonHeaders,
                    )
                }

            val error =
                shouldThrow<StoreConnectorException> {
                    GcpIamProvisioner(engine.client()).provision(provisionerAccount, PROJECT, "islegrow", "IsleGrow")
                }

            error.kind shouldBe StoreErrorKind.AUTH
            error.message.orEmpty() shouldContain "iam.serviceAccounts.create"
        }

        test("accountId ze slugu splní meze IAM: 6–30 znaků a začíná písmenem") {
            GcpIamProvisioner.accountIdOf("ab") shouldBe "arz-ab"
            // Krátký slug se doplní, aby prošel spodní mezí.
            GcpIamProvisioner.accountIdOf("a") shouldBe "arz-ax"
            // Slug začínající číslicí by IAM odmítl; prefix to řeší za nás.
            GcpIamProvisioner.accountIdOf("2fresh") shouldBe "arz-2fresh"

            val long = GcpIamProvisioner.accountIdOf("a".repeat(60))
            long.length shouldBe 30
            long shouldStartWith "arz-"

            // Pomlčka na konci by byla neplatná — ořízne se, ne aby ji IAM vrátil jako chybu.
            GcpIamProvisioner.accountIdOf("a".repeat(25) + "-b") shouldEndWith "a"
        }
    })
