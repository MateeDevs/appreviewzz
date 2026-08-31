package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.TestDatabase
import cz.matee.appreviewzz.core.model.PlatformRole
import cz.matee.appreviewzz.core.model.SecretPayload
import cz.matee.appreviewzz.core.model.Totp
import cz.matee.appreviewzz.persistence.repository.ExposedUserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

private const val ADMIN = "spravce@example.com"
private const val CLIENT = "klient@example.com"
private const val PASSWORD = "dostatecne-dlouhe-heslo"

/** `source` konkrétního klíče z výpisu nastavení — na řetězcové `shouldContain` je to moc křehké. */
private fun sourceOf(
    body: String,
    key: String,
): String =
    checkNotNull(Regex(""""key":"${Regex.escape(key)}".*?"source":"(\w+)"""").find(body)) {
        "Ve výpisu není '$key': $body"
    }.groupValues[1]

/**
 * Správa platformy (F7).
 *
 * Nejdůležitější testy tady nejsou o tom, co sekce umí, ale co **neumí**: nepustí dovnitř
 * bez role ani bez druhého faktoru, nevrátí uložené tajemství a nedá superadminovi nic
 * z dat organizací.
 */
class PlatformRoutesTest :
    StringSpec({

        lateinit var clock: TestClock
        lateinit var mailer: RecordingMailer

        /** Povýšení jde jen mimo API — v provozu to dělá seed CLI, v testu repozitář. */
        fun promote(email: String) {
            val users = ExposedUserRepository(TestDatabase.database.exposed)
            val user = checkNotNull(users.findByEmail(email)) { "Uživatel $email tu není" }
            users.setPlatformRole(user.id, PlatformRole.SUPERADMIN)
        }

        suspend fun ApplicationTestBuilder.enableTotp(client: HttpClient) {
            val setup = client.postJson("/api/auth/totp/setup", "{}")
            setup.status shouldBe HttpStatusCode.OK
            val secret = SecretPayload(Regex(""""secret":"([^"]+)"""").find(setup.bodyAsText())!!.groupValues[1])
            client
                .postJson("/api/auth/totp/confirm", """{"code":"${Totp.code(secret, Totp.stepAt(clock.current))}"}""")
                .status shouldBe HttpStatusCode.OK
            clock.advance(Totp.PERIOD)
        }

        /** Přihlášený superadmin se zapnutým druhým faktorem — tak, jak sekce vyžaduje. */
        suspend fun ApplicationTestBuilder.superadmin(): HttpClient {
            val client = browser()
            client.signUp(ADMIN, PASSWORD)
            promote(ADMIN)
            enableTotp(client)
            return client
        }

        beforeTest {
            TestDatabase.reset()
            clock = TestClock()
            mailer = RecordingMailer()
        }

        "bez role sekce neexistuje — 404, ne 403" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val client = browser()
                client.signUp(CLIENT, PASSWORD)

                // 404 schválně: kdyby to bylo 403, dalo by se hádáním adres zjistit, že
                // taková sekce vůbec existuje.
                client.get("/api/platform/settings").status shouldBe HttpStatusCode.NotFound
                client.get("/api/platform/overview").status shouldBe HttpStatusCode.NotFound
            }
        }

        "superadmin bez druhého faktoru dovnitř nesmí" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val client = browser()
                client.signUp(ADMIN, PASSWORD)
                promote(ADMIN)

                val response = client.get("/api/platform/settings")
                response.status shouldBe HttpStatusCode.Forbidden
                val body = response.bodyAsText()
                body shouldContain "platform_mfa_required"
                // Věta má říct, co s tím — jinak z ní člověk nepozná, že si má zapnout TOTP.
                body shouldContain "druhý faktor"
            }
        }

        "nepřihlášený dostane 401, ne 404" {
            testApplication {
                consoleModule(mailer, clock = clock)
                browser().get("/api/platform/settings").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "katalog nese výchozí hodnoty i to, odkud jsou" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                val body = admin.get("/api/platform/settings").bodyAsText()
                body shouldContain "ingest.default_interval_minutes"
                body shouldContain "\"value\":\"30\""
                body shouldContain "\"source\":\"DEFAULT\""
            }
        }

        "uložená hodnota přebíjí prostředí a smazání ji vrací zpátky" {
            testApplication {
                // Prostředí nastavené jako v produkci — uložená hodnota ho musí přebít,
                // jinak by správce v consoli ukládal něco, co nic nedělá.
                consoleModule(mailer, clock = clock, platformEnv = { name ->
                    if (name == "AI_PROVIDER") "gemini" else null
                })
                val admin = superadmin()

                admin.get("/api/platform/settings").bodyAsText() shouldContain "\"source\":\"ENV\""

                admin.putJson("/api/platform/settings", """{"values":{"ai.provider":"none"}}""").status shouldBe
                    HttpStatusCode.OK
                val stored = admin.get("/api/platform/settings").bodyAsText()
                stored shouldContain "\"source\":\"DB\""

                admin.putJson("/api/platform/settings", """{"values":{"ai.provider":null}}""").status shouldBe
                    HttpStatusCode.OK
                admin.get("/api/platform/settings").bodyAsText() shouldContain "\"source\":\"ENV\""
            }
        }

        "hodnota mimo katalog i mimo rozsah končí na 400" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                admin.putJson("/api/platform/settings", """{"values":{"neexistuje":"1"}}""").status shouldBe
                    HttpStatusCode.BadRequest

                val range = admin.putJson("/api/platform/settings", """{"values":{"ingest.default_interval_minutes":"2"}}""")
                range.status shouldBe HttpStatusCode.BadRequest
                range.bodyAsText() shouldContain "5"

                // Nic se neuložilo — validace běží před zápisem, ne v jeho průběhu.
                admin.get("/api/platform/settings").bodyAsText() shouldContain "\"value\":\"30\""
            }
        }

        "tajemství jde dovnitř, ven jen otisk" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                admin.putJson("/api/platform/secrets/ai.api_key", """{"value":"tajny-klic-k-ai"}""").status shouldBe
                    HttpStatusCode.NoContent

                val secrets = admin.get("/api/platform/secrets").bodyAsText()
                secrets shouldContain "sha256:"
                // Tohle je ten test, kvůli kterému je úložiště write-only.
                secrets shouldNotContain "tajny-klic-k-ai"
                admin.get("/api/platform/settings").bodyAsText() shouldNotContain "tajny-klic-k-ai"

                admin.deleteSigned("/api/platform/secrets/ai.api_key").status shouldBe HttpStatusCode.NoContent
                admin.get("/api/platform/secrets").bodyAsText() shouldNotContain "sha256:"
            }
        }

        "uložené tajemství hlásí zdroj DB, ne prostředí" {
            testApplication {
                // Klíč v prostředí i v databázi naráz: console musí ukázat ten uložený,
                // jinak by správce hledal, proč se jeho klíč nepoužívá.
                consoleModule(mailer, clock = clock, platformEnv = { name ->
                    if (name == "AI_API_KEY") "klic-z-prostredi" else null
                })
                val admin = superadmin()

                sourceOf(admin.get("/api/platform/settings").bodyAsText(), "ai.api_key") shouldBe "ENV"

                admin.putJson("/api/platform/secrets/ai.api_key", """{"value":"ulozeny-klic"}""")
                val stored = admin.get("/api/platform/settings").bodyAsText()
                // Tajemství neleží v `platform_setting`; bez zvláštního dohledání by uložený
                // klíč navěky hlásil „z prostředí" a nikdo by nepoznal, který se používá.
                sourceOf(stored, "ai.api_key") shouldBe "DB"
                // A pořád platí, že hodnota ven nejde — ani ta z prostředí.
                stored shouldNotContain "klic-z-prostredi"
                stored shouldNotContain "ulozeny-klic"

                admin.deleteSigned("/api/platform/secrets/ai.api_key")
                admin.get("/api/platform/secrets").bodyAsText() shouldNotContain "sha256:"
            }
        }

        "tajemství se nedá uložit jako obyčejné nastavení" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                // Kdyby to prošlo, klíč by skončil v `platform_setting` a odtud rovnou v JSONu.
                admin.putJson("/api/platform/settings", """{"values":{"ai.api_key":"tajny"}}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "změna nastavení i klíče zůstane v auditu, hodnota nikdy" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                admin.putJson("/api/platform/settings", """{"values":{"ingest.default_interval_minutes":"45"}}""")
                admin.putJson("/api/platform/secrets/ai.api_key", """{"value":"tajny-klic-k-ai"}""")

                val audit = admin.get("/api/platform/audit").bodyAsText()
                audit shouldContain "platform.setting.changed"
                audit shouldContain "platform.secret.set"
                audit shouldContain "\"to\":\"45\""
                audit shouldNotContain "tajny-klic-k-ai"
            }
        }

        "změna platformního intervalu se propíše do appek bez výjimky" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                // Klientská organizace vedle: superadmin do ní nepatří a nesmí do ní vidět.
                val client = browser()
                client.signUpVerified(CLIENT, mailer)
                client.postJson("/api/orgs", """{"name":"Klient"}""")
                val appId =
                    Regex(""""id":"([^"]+)"""")
                        .find(client.postJson("/api/orgs/klient/apps", """{"name":"A","gpPackageName":"cz.a"}""").bodyAsText())!!
                        .groupValues[1]

                client.get("/api/orgs/klient/apps/$appId").bodyAsText() shouldContain "\"ingestIntervalMinutes\":30"

                admin
                    .putJson("/api/platform/settings", """{"values":{"ingest.default_interval_minutes":"45"}}""")
                    .status shouldBe HttpStatusCode.OK

                val after = client.get("/api/orgs/klient/apps/$appId").bodyAsText()
                after shouldContain "\"ingestIntervalMinutes\":45"
                after shouldContain "\"ingestIntervalSource\":\"PLATFORM\""
            }
        }

        "výjimku pro appku uděluje superadmin a podlaha platí i pro ni" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                val client = browser()
                client.signUpVerified(CLIENT, mailer)
                client.postJson("/api/orgs", """{"name":"Klient"}""")
                val appId =
                    Regex(""""id":"([^"]+)"""")
                        .find(client.postJson("/api/orgs/klient/apps", """{"name":"A","gpPackageName":"cz.a"}""").bodyAsText())!!
                        .groupValues[1]

                admin.get("/api/platform/apps").bodyAsText() shouldBe "[]"

                // Podlaha (15 min) drží i u výjimky — jinak by nebyla k ničemu.
                admin.patchJson("/api/platform/apps/$appId", """{"minutes":5}""").status shouldBe
                    HttpStatusCode.BadRequest

                admin.patchJson("/api/platform/apps/$appId", """{"minutes":60}""").status shouldBe HttpStatusCode.OK
                client.get("/api/orgs/klient/apps/$appId").bodyAsText() shouldContain "\"ingestIntervalSource\":\"APP\""
                admin.get("/api/platform/apps").bodyAsText() shouldContain "\"overrideMinutes\":60"

                admin.patchJson("/api/platform/apps/$appId", """{"minutes":null}""").status shouldBe HttpStatusCode.OK
                client.get("/api/orgs/klient/apps/$appId").bodyAsText() shouldContain "\"ingestIntervalSource\":\"PLATFORM\""
            }
        }

        "strop počtu aplikací platí při zakládání, na existující nesahá" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                val client = browser()
                client.signUpVerified(CLIENT, mailer)
                client.postJson("/api/orgs", """{"name":"Klient"}""")
                client.postJson("/api/orgs/klient/apps", """{"name":"A","gpPackageName":"cz.a"}""").status shouldBe
                    HttpStatusCode.Created

                admin
                    .putJson("/api/platform/settings", """{"values":{"limits.max_apps_per_org":"1"}}""")
                    .status shouldBe HttpStatusCode.OK

                val rejected = client.postJson("/api/orgs/klient/apps", """{"name":"B","gpPackageName":"cz.b"}""")
                rejected.status shouldBe HttpStatusCode.Forbidden
                rejected.bodyAsText() shouldContain "maximum"

                // Snížení stropu nesmí zneviditelnit appku, kterou klient už sleduje.
                client.get("/api/orgs/klient/apps").bodyAsText() shouldContain "cz.a"
            }
        }

        "superadmin se k datům cizí organizace nedostane" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                val client = browser()
                client.signUpVerified(CLIENT, mailer)
                client.postJson("/api/orgs", """{"name":"Klient"}""")

                // Tohle je celý smysl kolmé role: správa platformy není nadřízená role
                // v organizaci. 404, protože členem není.
                admin.get("/api/orgs/klient/apps").status shouldBe HttpStatusCode.NotFound
                admin.get("/api/orgs/klient/reviews").status shouldBe HttpStatusCode.NotFound
                admin.get("/api/orgs/klient/credentials").status shouldBe HttpStatusCode.NotFound
                admin.get("/api/orgs/klient/members").status shouldBe HttpStatusCode.NotFound
            }
        }

        "přehled ukazuje agregáty, ne obsah organizací" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()

                val client = browser()
                client.signUpVerified(CLIENT, mailer)
                client.postJson("/api/orgs", """{"name":"Klient"}""")
                client.postJson("/api/orgs/klient/apps", """{"name":"Tajný projekt","gpPackageName":"cz.a"}""")

                val overview = admin.get("/api/platform/overview").bodyAsText()
                overview shouldContain "\"apps\":1"
                overview shouldContain "\"defaultIntervalMinutes\":30"
                // Názvy appek ani organizací do přehledu nepatří.
                overview shouldNotContain "Tajný projekt"
                overview shouldNotContain "klient"
            }
        }

        "profil přihlášeného nese platformní roli, běžnému uživateli null" {
            testApplication {
                consoleModule(mailer, clock = clock)
                val admin = superadmin()
                admin.get("/api/auth/me").bodyAsText() shouldContain "\"platformRole\":\"SUPERADMIN\""

                val client = browser()
                client.signUp(CLIENT, PASSWORD)
                client.get("/api/auth/me").bodyAsText() shouldNotContain "SUPERADMIN"
            }
        }
    })
