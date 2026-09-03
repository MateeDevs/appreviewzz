package cz.matee.appreviewzz.connectors.googleplay

import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.time.Duration

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private const val PROJECT = "appreviewzz-connect"
private const val ORG = "3f2b9c14-0000-4000-8000-000000000001"

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
                    orgId = ORG,
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

        test("jméno obsazené cizí organizací není chyba — zkusí se další") {
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

                        // Účet toho jména patří někomu jinému — jeho klíč by pustil cizí
                        // organizaci do našich recenzí, takže se neadoptuje.
                        request.url.encodedPath.contains("/serviceAccounts/") ->
                            respond(
                                """{"email":"arz-islegrow@$PROJECT.iam.gserviceaccount.com","description":"jina-organizace"}""",
                                headers = jsonHeaders,
                            )

                        else -> null
                    }
                }

            val account =
                GcpIamProvisioner(engine.client()).provision(provisionerAccount, PROJECT, ORG, "islegrow", "IsleGrow")

            account.email shouldBe email
            engine.requests.filter { it.url.encodedPath.endsWith("/serviceAccounts") } shouldHaveSize 2
            val second = engine.requests.last { it.url.encodedPath.endsWith("/serviceAccounts") }
            Json
                .parseToJsonElement(String(second.body.toByteArray()))
                .jsonObject["accountId"]
                ?.jsonPrimitive
                ?.content shouldBe "arz-islegrow-1"
        }

        test("účet, který organizaci už patří, se adoptuje — stejný e-mail a nový klíč") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            val engine =
                RecordingEngine { request ->
                    when {
                        // Výpis klíčů (GET) versus vydání nového (POST) — obojí končí na /keys.
                        request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/keys") ->
                            respond(
                                """{"keys":[{"name":"projects/$PROJECT/serviceAccounts/$email/keys/stary"}]}""",
                                headers = jsonHeaders,
                            )

                        request.url.encodedPath.endsWith("/keys") -> respond(keyResponse(email), headers = jsonHeaders)
                        request.url.encodedPath.endsWith("/serviceAccounts") ->
                            respond(
                                """{"error":{"code":409,"message":"already exists"}}""",
                                status = HttpStatusCode.Conflict,
                                headers = jsonHeaders,
                            )

                        request.url.encodedPath.contains("/serviceAccounts/") ->
                            respond("""{"email":"$email","uniqueId":"1","description":"$ORG"}""", headers = jsonHeaders)

                        else -> null
                    }
                }

            val account =
                GcpIamProvisioner(engine.client()).provision(provisionerAccount, PROJECT, ORG, "islegrow", "IsleGrow")

            // Tentýž e-mail: klient ho má pozvaný v Play Console a pozvánku nemá řešit znovu.
            account.email shouldBe email
            // Zakládat se nic dalšího nesmí — jinak by v projektu přibývaly účty `…-1`.
            engine.requests.filter { it.url.encodedPath.endsWith("/serviceAccounts") } shouldHaveSize 1
            // Starý klíč, který jsme klientovi kdysi vydali, musí přestat platit.
            engine.requests.count { it.method == HttpMethod.Delete } shouldBe 1
            engine.requests
                .last { it.method == HttpMethod.Delete }
                .url.encodedPath shouldEndWith "/keys/stary"
        }

        test("zneplatnění klíčů účet nemaže — pozvánka v Play Console má přežít") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            val engine =
                RecordingEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/keys") ->
                            respond(
                                """{"keys":[{"name":"projects/$PROJECT/serviceAccounts/$email/keys/a"},""" +
                                    """{"name":"projects/$PROJECT/serviceAccounts/$email/keys/b"}]}""",
                                headers = jsonHeaders,
                            )

                        request.method == HttpMethod.Delete -> respond("{}", headers = jsonHeaders)
                        else -> null
                    }
                }

            GcpIamProvisioner(engine.client()).revokeKeys(provisionerAccount, PROJECT, email)

            engine.requests.count { it.method == HttpMethod.Delete } shouldBe 2
            // Jen klíče: `DELETE …/serviceAccounts/{email}` by smazal účet i s pozvánkou.
            engine.requests.filter { it.method == HttpMethod.Delete }.forAll {
                it.url.encodedPath shouldContain "/keys/"
            }
            // Systémové klíče Googlu smazat nejde — do výpisu se proto nesmí dostat.
            engine.requests
                .first { it.method == HttpMethod.Get }
                .url.parameters["keyTypes"] shouldBe "USER_MANAGED"
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
                    GcpIamProvisioner(engine.client()).provision(provisionerAccount, PROJECT, ORG, "islegrow", "IsleGrow")
                }

            error.kind shouldBe StoreErrorKind.AUTH
            error.message.orEmpty() shouldContain "iam.serviceAccounts.create"
        }

        test("právě založený účet ještě IAM nevidí — klíč se zkusí znovu, a jen jednou uspěje") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            var keyCalls = 0
            val engine =
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/keys") -> {
                            keyCalls++
                            // IAM je eventually consistent: účet po založení chvíli „neexistuje".
                            if (keyCalls == 1) {
                                respond(
                                    """{"error":{"code":404,"message":"Service account does not exist."}}""",
                                    status = HttpStatusCode.NotFound,
                                    headers = jsonHeaders,
                                )
                            } else {
                                respond(keyResponse(email), headers = jsonHeaders)
                            }
                        }

                        request.url.encodedPath.endsWith("/serviceAccounts") ->
                            respond("""{"email":"$email","uniqueId":"123456789"}""", headers = jsonHeaders)

                        else -> null
                    }
                }

            val account =
                GcpIamProvisioner(engine.client(), retryDelay = Duration.ZERO)
                    .provision(provisionerAccount, PROJECT, ORG, "islegrow", "IsleGrow")

            account.email shouldBe email
            // Účet unese deset klíčů; kdyby smyčka pokračovala i po úspěchu, jedenáctý pokus
            // by vrátil FAILED_PRECONDITION a původní příčinu by z hlášky nikdo nevyčetl.
            keyCalls shouldBe 2
            // Klíč se adresuje přes uniqueId — ten se propisuje dřív než e-mail.
            engine.requests
                .last { it.url.encodedPath.endsWith("/keys") }
                .url.encodedPath shouldContain "/123456789/keys"
        }

        test("když se účet nepropíše vůbec, uklidí se po sobě místo mrtvého účtu v projektu") {
            val engine =
                RecordingEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/keys") ->
                            respond(
                                """{"error":{"code":404,"message":"Service account does not exist."}}""",
                                status = HttpStatusCode.NotFound,
                                headers = jsonHeaders,
                            )

                        request.url.encodedPath.endsWith("/serviceAccounts") ->
                            respond("""{"email":"a@b.iam.gserviceaccount.com","uniqueId":"1"}""", headers = jsonHeaders)

                        else -> respond("{}", headers = jsonHeaders)
                    }
                }

            shouldThrow<StoreConnectorException> {
                GcpIamProvisioner(engine.client(), retryDelay = Duration.ZERO)
                    .provision(provisionerAccount, PROJECT, ORG, "islegrow", "IsleGrow")
            }

            // Bez úklidu by účet blokoval jméno a další pokus by založil `…-1`; v projektu
            // by přibývaly mrtvé účty, dokud nedojde kvóta.
            engine.requests.count { it.method == HttpMethod.Delete } shouldBe 1
        }

        test("značka vlastníka se doplní jen účtu, který ji nemá") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            var description: String? = null
            val engine =
                RecordingEngine { request ->
                    when (request.method) {
                        HttpMethod.Get ->
                            respond(
                                """{"email":"$email","displayName":"IsleGrow"""" +
                                    (description?.let { ""","description":"$it"""" } ?: "") + "}",
                                headers = jsonHeaders,
                            )

                        else -> {
                            description = ORG
                            respond("""{"email":"$email","description":"$ORG"}""", headers = jsonHeaders)
                        }
                    }
                }
            val provisioner = GcpIamProvisioner(engine.client())

            provisioner.markOwner(provisionerAccount, PROJECT, email, ORG) shouldBe true
            // Podruhé už není co dělat — příkaz musí jít pustit opakovaně.
            provisioner.markOwner(provisionerAccount, PROJECT, email, ORG) shouldBe false

            val patch = engine.requests.single { it.method == HttpMethod.Patch }
            val body = Json.parseToJsonElement(String(patch.body.toByteArray())).jsonObject
            body["updateMask"]?.jsonPrimitive?.content shouldBe "description"
            body["serviceAccount"]
                ?.jsonObject
                ?.get("description")
                ?.jsonPrimitive
                ?.content shouldBe ORG
        }

        test("cizí značku nepřepíšeme — účet by se přebral jiné organizaci") {
            val email = "arz-islegrow@$PROJECT.iam.gserviceaccount.com"
            val engine =
                RecordingEngine { respond("""{"email":"$email","description":"jina-organizace"}""", headers = jsonHeaders) }

            val error =
                shouldThrow<StoreConnectorException> {
                    GcpIamProvisioner(engine.client()).markOwner(provisionerAccount, PROJECT, email, ORG)
                }

            error.kind shouldBe StoreErrorKind.INVALID_REQUEST
            engine.requests.none { it.method == HttpMethod.Patch } shouldBe true
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
