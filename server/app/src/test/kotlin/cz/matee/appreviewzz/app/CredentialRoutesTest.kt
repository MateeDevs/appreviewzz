package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.StoreKeyFixtures
import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.connectors.googleplay.GcpIamProvisioner
import cz.matee.appreviewzz.connectors.googleplay.googleHttpClient
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.StoreApp
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.readText

private const val OWNER = "vlastnik@example.com"
private const val COLLEAGUE = "kolega@example.com"
private const val SLUG = "matee"

/** Klíče se generují, ne kopírují z produkce — konektor je reálně parsuje. */
private val serviceAccountJson: String by lazy {
    StoreKeyFixtures.serviceAccountFile(Files.createTempDirectory("appreviewzz-gp")).readText()
}
private val appStoreKey: String by lazy {
    StoreKeyFixtures.appStoreKeyFile(Files.createTempDirectory("appreviewzz-asc")).readText()
}

private fun json(value: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

private suspend fun HttpClient.addGooglePlayKey(label: String = "Play SA"): HttpResponse =
    postJson(
        "/api/orgs/$SLUG/credentials",
        """{"type":"gp","label":"$label","content":${json(serviceAccountJson)}}""",
    )

private fun String.jsonValue(field: String): String =
    checkNotNull(Regex(""""$field":"([^"]+)"""").find(this)) { "V odpovědi chybí $field: $this" }.groupValues[1]

/**
 * IAM API na mocku. Vrací účet a k němu klíč (base64 service account JSON) — přesně to,
 * co dělá `iam.googleapis.com`, aby se testovala i cesta „dekóduj a ulož do vaultu".
 */
private fun fakeIam(email: String = "arz-matee@test-project.iam.gserviceaccount.com"): GcpIamProvisioner {
    val key = Base64.getEncoder().encodeToString(serviceAccountJson.toByteArray())
    val engine =
        MockEngine { request ->
            val body =
                when {
                    // Provisioner se nejdřív přihlásí — bez tokenu se k IAM vůbec nedostane.
                    request.url.host == "oauth2.googleapis.com" -> """{"access_token":"ya29.test","expires_in":3600}"""
                    request.url.encodedPath.endsWith("/keys") -> """{"privateKeyData":"$key"}"""
                    else -> """{"email":"$email"}"""
                }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    return GcpIamProvisioner(googleHttpClient(engine))
}

/** Provisioner je nastavený až s projektem i klíčem — bez nich má endpoint hlásit nenastaveno. */
private fun provisionerEnv(): (String) -> String? =
    { name ->
        when (name) {
            "GCP_PROVISIONER_PROJECT" -> "test-project"
            "GCP_PROVISIONER_KEY" -> serviceAccountJson
            else -> null
        }
    }

private suspend fun ApplicationTestBuilder.ownerWithApp(mailer: RecordingMailer): Pair<HttpClient, String> {
    val owner = browser()
    owner.signUpVerified(OWNER, mailer)
    owner.postJson("/api/orgs", """{"name":"Matee"}""")
    val app =
        owner
            .postJson("/api/orgs/$SLUG/apps", """{"name":"Testovací appka","gpPackageName":"cz.matee.test"}""")
            .bodyAsText()
            .jsonValue("id")
    return owner to app
}

/**
 * Klíče ke storům přes API. Kromě šťastné cesty jde hlavně o jedno: **payload se nikdy
 * nevrací**, ani omylem, ani v chybové hlášce.
 */
class CredentialRoutesTest :
    StringSpec({

        lateinit var mailer: RecordingMailer
        lateinit var fakes: ConsoleFakes

        beforeTest {
            TestDatabase.reset()
            mailer = RecordingMailer()
            fakes = ConsoleFakes(FakeReviewSource(Platform.ANDROID), FakeReviewSource(Platform.IOS), FakeNotificationChannel())
        }

        "nahraný klíč se uloží a ven jde jen otisk a nápověda" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val created = owner.addGooglePlayKey()
                created.status shouldBe HttpStatusCode.Created
                val body = created.bodyAsText()
                body shouldContain "\"fingerprint\":\"sha256:"
                body shouldContain "reviews@isle-grow.iam.gserviceaccount.com"
                body shouldContain "\"validationStatus\":\"UNKNOWN\""
                // Tohle je ta věta, kvůli které test existuje.
                body shouldNotContain "PRIVATE KEY"
                body shouldNotContain "private_key"

                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldNotContain "PRIVATE KEY"
            }
        }

        "rozbitý klíč se pozná hned při nahrání" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val response =
                    owner.postJson("/api/orgs/$SLUG/credentials", """{"type":"gp","label":"Rozbitý","content":"{}"}""")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "invalid_input"
            }
        }

        "klíč App Store Connect chce Key ID" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)

                val without =
                    owner.postJson(
                        "/api/orgs/$SLUG/credentials",
                        """{"type":"asc","label":"ASC","content":${json(appStoreKey)}}""",
                    )
                without.status shouldBe HttpStatusCode.BadRequest
                without.bodyAsText() shouldContain "Key ID"

                val with =
                    owner.postJson(
                        "/api/orgs/$SLUG/credentials",
                        """{"type":"asc","label":"ASC","content":${json(appStoreKey)},"keyId":"ABC123DEFG","issuerId":"69a6de70"}""",
                    )
                with.status shouldBe HttpStatusCode.Created
                with.bodyAsText() shouldContain "Key ID ABC123DEFG"
            }
        }

        "díru v nastavení zavře až ověřený klíč, ne pouhé přiřazení" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")

                owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials", """{"credentialId":"$credentialId"}""")

                // Připojený, ale neověřený klíč je vlastní stav: čeká se na store, ne na klienta.
                // Kdyby se tvářil jako hotovo, klient by čekal na recenze, které nemají odkud přijít.
                var body = owner.get("/api/orgs/$SLUG/apps/$appId").bodyAsText()
                body shouldContain "\"gaps\":[\"STORE_KEY_WAITING\",\"CHANNEL\"]"
                body shouldContain "\"platformsWaitingForKey\":[\"ANDROID\"]"

                fakes.googlePlay.outcome = ValidationOutcome(valid = true)
                owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials/$credentialId/validate", "{}")

                body = owner.get("/api/orgs/$SLUG/apps/$appId").bodyAsText()
                body shouldContain "\"gaps\":[\"CHANNEL\"]"
            }
        }

        "ověření proti storu zapíše výsledek do metadat" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")
                owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials", """{"credentialId":"$credentialId"}""")

                val ok = owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials/$credentialId/validate", "{}")
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText() shouldContain "\"valid\":true"
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "\"validationStatus\":\"VALID\""

                // Konektor dostal rozbalený klíč a identifikátor appky.
                fakes.googlePlay.validated
                    .single()
                    .appIdentifier shouldBe "cz.matee.test"
                fakes.googlePlay.validated
                    .single()
                    .credential.value shouldContain "isle-grow"
            }
        }

        "neplatný klíč není chyba requestu, ale výsledek ověření" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")
                fakes.googlePlay.failWith = StoreConnectorException(StoreErrorKind.AUTH, "chybí oprávnění Reply to reviews")

                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials/$credentialId/validate", "{}")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "\"valid\":false"
                response.bodyAsText() shouldContain "Reply to reviews"
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "\"validationStatus\":\"INVALID\""
            }
        }

        "rotace klíče zahodí předchozí výsledek ověření" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")
                owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials/$credentialId/validate", "{}")

                val newKey =
                    StoreKeyFixtures
                        .serviceAccountFile(
                            Files.createTempDirectory("appreviewzz-gp2"),
                            clientEmail = "novy@isle-grow.iam.gserviceaccount.com",
                        ).readText()
                val rotated =
                    owner.putJson("/api/orgs/$SLUG/credentials/$credentialId", """{"content":${json(newKey)}}""")
                rotated.status shouldBe HttpStatusCode.OK
                rotated.bodyAsText() shouldContain "novy@isle-grow"
                // Ověření platilo pro jiný obsah, takže se zahazuje.
                rotated.bodyAsText() shouldContain "\"validationStatus\":\"UNKNOWN\""
            }
        }

        "klíč cizího typu se k appce nepřipojí" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val ascId =
                    owner
                        .postJson(
                            "/api/orgs/$SLUG/credentials",
                            """{"type":"asc","label":"ASC","content":${json(appStoreKey)},"keyId":"ABC123DEFG"}""",
                        ).bodyAsText()
                        .jsonValue("id")

                // Appka sleduje jen Google Play; klíč od Applu by se projevil až chybou v ingestu.
                val response = owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials", """{"credentialId":"$ascId"}""")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "ANDROID"
            }
        }

        "člen s klíči nehne" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                owner.postJson("/api/orgs/$SLUG/invitations", """{"email":"$COLLEAGUE","role":"MEMBER"}""")
                val member = joinViaInvitation(mailer, COLLEAGUE)

                member.addGooglePlayKey().status shouldBe HttpStatusCode.Forbidden
                // Vidět, že klíč existuje a jestli funguje, ale potřebuje — kvůli diagnostice.
                owner.addGooglePlayKey()
                member.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "Play SA"
            }
        }

        "klíče cizí organizace nejsou vidět" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")

                val outsider = browser()
                outsider.signUpVerified("cizi@example.com", mailer)
                outsider.postJson("/api/orgs", """{"name":"Cizí"}""")

                outsider.get("/api/orgs/$SLUG/credentials").status shouldBe HttpStatusCode.NotFound
                outsider.deleteSigned("/api/orgs/cizi/credentials/$credentialId").status shouldBe HttpStatusCode.NotFound
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain credentialId
            }
        }

        "smazání klíče, který nikde nevisí, projde" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")

                owner.deleteSigned("/api/orgs/$SLUG/credentials/$credentialId").status shouldBe HttpStatusCode.NoContent
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldBe "[]"
            }
        }

        "ověření klíče, který k appce nepatří, řekne proč" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")
                fakes.googlePlay.outcome = ValidationOutcome(valid = true)

                val iosApp =
                    owner
                        .postJson("/api/orgs/$SLUG/apps", """{"name":"iOS","ascAppId":"1234567890"}""")
                        .bodyAsText()
                        .jsonValue("id")

                val response = owner.postJson("/api/orgs/$SLUG/apps/$iosApp/credentials/$credentialId/validate", "{}")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "ANDROID"
            }
        }

        "spravovaný service account vznikne jednou a druhé volání vrátí tentýž účet" {
            testApplication {
                consoleModule(mailer, fakes = fakes, platformEnv = provisionerEnv(), gcpProvisioner = fakeIam())
                val (owner, _) = ownerWithApp(mailer)

                val first = owner.postJson("/api/orgs/$SLUG/credentials/provision-gp", "{}")
                first.status shouldBe HttpStatusCode.OK
                val body = first.bodyAsText()
                body shouldContain "PROVISIONED"
                // E-mail k pozvání do Play Console musí jít ven celý, jinak ho klient nemá odkud vzít.
                body.jsonValue("hint") shouldBe "arz-matee@test-project.iam.gserviceaccount.com"
                // Payload klíče ven nesmí ani u účtu, který jsme vyrobili my.
                body shouldNotContain "PRIVATE KEY"

                val again = owner.postJson("/api/orgs/$SLUG/credentials/provision-gp", "{}")
                again.bodyAsText().jsonValue("id") shouldBe body.jsonValue("id")

                val listed = owner.get("/api/orgs/$SLUG/credentials").bodyAsText()
                listed.split("\"id\"") shouldHaveSize 2
            }
        }

        "bez nastaveného provisioneru endpoint řekne, že to není naše chyba" {
            testApplication {
                consoleModule(mailer, fakes = fakes, gcpProvisioner = fakeIam())
                val (owner, _) = ownerWithApp(mailer)

                val response = owner.postJson("/api/orgs/$SLUG/credentials/provision-gp", "{}")

                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "Pokročilé"
            }
        }

        "service account členovi nevyrobíme" {
            testApplication {
                consoleModule(mailer, fakes = fakes, platformEnv = provisionerEnv(), gcpProvisioner = fakeIam())
                val (owner, _) = ownerWithApp(mailer)
                owner.postJson("/api/orgs/$SLUG/invitations", """{"email":"$COLLEAGUE","role":"MEMBER"}""")
                val member = joinViaInvitation(mailer, COLLEAGUE)

                member.postJson("/api/orgs/$SLUG/credentials/provision-gp", "{}").status shouldBe HttpStatusCode.Forbidden
            }
        }

        "výpis aplikací z App Store zároveň klíč označí za ověřený" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId =
                    owner
                        .postJson(
                            "/api/orgs/$SLUG/credentials",
                            """{"type":"asc","label":"ASC","content":${json(
                                appStoreKey,
                            )},"keyId":"ABCD123456","issuerId":"69a6de70-0000-47e3-e053-5b8c7c11a4d1"}""",
                        ).bodyAsText()
                        .jsonValue("id")
                fakes.appStoreCatalog.apps =
                    listOf(
                        StoreApp("1234567890", "IsleGrow", "cz.matee.islegrow"),
                        StoreApp("1234567891", "IsleGrow Pro", "cz.matee.islegrow.pro"),
                    )

                val response = owner.get("/api/orgs/$SLUG/credentials/$credentialId/store-apps")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "cz.matee.islegrow.pro"
                // Klíč se právě prokázal — bez tohohle by console hned po výběru appek
                // tvrdila „čeká na ověření".
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "\"validationStatus\":\"VALID\""
            }
        }

        "klíč, který App Store odmítne, se označí za neplatný a hláška jde ven" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId =
                    owner
                        .postJson(
                            "/api/orgs/$SLUG/credentials",
                            """{"type":"asc","label":"ASC","content":${json(appStoreKey)},"keyId":"ABCD123456"}""",
                        ).bodyAsText()
                        .jsonValue("id")
                fakes.appStoreCatalog.failWith =
                    StoreConnectorException(StoreErrorKind.AUTH, "Zkontroluj Issuer ID nahoře na stránce Integrations.")

                val response = owner.get("/api/orgs/$SLUG/credentials/$credentialId/store-apps")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Issuer ID"
                owner.get("/api/orgs/$SLUG/credentials").bodyAsText() shouldContain "\"validationStatus\":\"INVALID\""
            }
        }

        "aplikace z Google Play se vypsat nedají — store to neumí" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, _) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")

                val response = owner.get("/api/orgs/$SLUG/credentials/$credentialId/store-apps")

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "ANDROID"
            }
        }
    })
