package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.StoreKeyFixtures
import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.StoreConnectorException
import cz.matee.appreviewzz.core.port.StoreErrorKind
import cz.matee.appreviewzz.core.port.ValidationOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
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

        "přiřazení klíče zavře díru v nastavení appky" {
            testApplication {
                consoleModule(mailer, fakes = fakes)
                val (owner, appId) = ownerWithApp(mailer)
                val credentialId = owner.addGooglePlayKey().bodyAsText().jsonValue("id")

                owner.postJson("/api/orgs/$SLUG/apps/$appId/credentials", """{"credentialId":"$credentialId"}""")

                // Klíč je připojený, takže zbývá jediné, co appce brání v provozu — kanál.
                owner.get("/api/orgs/$SLUG/apps/$appId").bodyAsText() shouldContain "\"gaps\":[\"CHANNEL\"]"
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
    })
