package cz.matee.appreviewzz.app.cli

import cz.matee.appreviewzz.app.AiConfig
import cz.matee.appreviewzz.app.AppConfig
import cz.matee.appreviewzz.app.BackupConfig
import cz.matee.appreviewzz.app.Role
import cz.matee.appreviewzz.app.ServerConfig
import cz.matee.appreviewzz.app.SlackConfig
import cz.matee.appreviewzz.app.WorkerConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * Seed CLI proti opravdovému Postgresu a opravdovému vaultu (lokální keyset). Testuje se celá
 * cesta příkazové řádky včetně návratových kódů — právě ty odlišují překlep (2) od odmítnutí
 * storu (1), když CLI běží ze skriptu.
 */
class SeedCliTest :
    StringSpec({

        val workDirectory = Files.createTempDirectory("appreviewzz-cli-test")
        val config =
            AppConfig(
                role = Role.API,
                environment = "test",
                server = ServerConfig(host = "127.0.0.1", port = 0, managementPort = 0),
                database = TestDatabase.config,
                vaultKekUri = "local://${workDirectory.resolve("keyset")}",
                worker = WorkerConfig(schedulerThreads = 1, pollingIntervalSeconds = 10, sweepIntervalSeconds = 60),
                // Zálohy jsou vypnuté: `backup run` by chtěl pg_dump, a ten se testuje v modulu backup.
                backup =
                    BackupConfig(
                        target = null,
                        at = "02:30",
                        retentionDays = 30,
                        keepAtLeast = 7,
                        s3Endpoint = null,
                        pgDumpPath = "pg_dump",
                        pgRestorePath = "pg_restore",
                        timeoutMinutes = 30,
                    ),
                // Seed CLI nesahá ani na AI, ani na Slack.
                ai = AiConfig(provider = "none", apiKey = null, model = null),
                slack =
                    SlackConfig(
                        signingSecret = "test-signing-secret",
                        clientId = null,
                        clientSecret = null,
                        publicBaseUrl = "https://appreviewzz.test",
                    ),
            )

        /** Příkaz se píše tak, jak se zadává v terminálu; hodnoty s mezerami se dávají do apostrofů. */
        fun cli(commandLine: String): CliResult {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val code = runCli(tokenize(commandLine), config, out::add, err::add)
            return CliResult(code, out.joinToString(System.lineSeparator()), err.joinToString(System.lineSeparator()))
        }

        fun seedOrganization(): String {
            cli("org create --name 'Isle Grow'").code shouldBe 0
            return "isle-grow"
        }

        fun seedApp(): String {
            val organization = seedOrganization()
            val result = cli("app create --org $organization --name IsleGrow --gp-package cz.matee.islegrow")
            result.code shouldBe 0
            return result.uuid()
        }

        fun seedAppStoreKey(): String =
            cli(
                "credential add --org isle-grow --type asc --label 'IsleGrow ASC' " +
                    "--file '${StoreKeyFixtures.appStoreKeyFile(workDirectory)}' --key-id ABC123DEFG",
            ).uuid()

        beforeTest { TestDatabase.reset() }

        "bez příkazu vypíše nápovědu" {
            val result = cli("")
            result.code shouldBe 0
            result.out shouldContain "seed CLI"
            result.out shouldContain "credential add"
        }

        "neznámý příkaz končí kódem 2" {
            val result = cli("org destroy")
            result.code shouldBe 2
            result.err shouldContain "Neznámý příkaz"
        }

        "překlep ve volbě databázi vůbec neotevře" {
            val result = cli("org create --nmae 'Isle Grow'")
            result.code shouldBe 2
            result.err shouldContain "--nmae"
        }

        "org create odvodí slug z názvu a založí organizaci" {
            val result = cli("org create --name 'Isle Grow s.r.o.'")
            result.code shouldBe 0
            result.out shouldContain "isle-grow-s-r-o"

            cli("org list").out shouldContain "Isle Grow s.r.o."
        }

        "org create odmítne druhou organizaci se stejným slugem" {
            seedOrganization()
            val result = cli("org create --name 'Isle Grow' --slug isle-grow")
            result.code shouldBe 1
            result.err shouldContain "už existuje"
        }

        "org create odmítne nepoužitelný slug" {
            val result = cli("org create --name Ř")
            result.code shouldBe 2
            result.err shouldContain "Slug"
        }

        "user add založí uživatele i členství" {
            val organization = seedOrganization()
            val result = cli("user add --org $organization --email tadeas@matee.cz --role owner")
            result.code shouldBe 0
            result.out shouldContain "owner"

            // Opakované přidání je upsert členství, ne druhý uživatel.
            cli("user add --org $organization --email tadeas@matee.cz").code shouldBe 0
        }

        "app create potřebuje aspoň jeden store" {
            val result = cli("app create --org ${seedOrganization()} --name IsleGrow")
            result.code shouldBe 2
            result.err shouldContain "--gp-package"
        }

        "app create uloží nastavení a appka se objeví v seznamu" {
            val organization = seedOrganization()
            val result =
                cli(
                    "app create --org $organization --name IsleGrow --gp-package cz.matee.islegrow " +
                        "--asc-app-id 1234567890 --locale en --notify-from now " +
                        "--ingest-interval 15 --digest-at 07:45",
                )
            result.code shouldBe 0
            result.out shouldContain "cz.matee.islegrow"
            result.out shouldContain "každých 15 min"
            result.out shouldContain "07:45"

            val listed = cli("app list --org $organization")
            listed.out shouldContain "android+ios"
            listed.out shouldContain "IsleGrow"
        }

        "app create odmítne balíček, který v organizaci už je" {
            seedApp()
            val result = cli("app create --org isle-grow --name 'IsleGrow znovu' --gp-package cz.matee.islegrow")
            result.code shouldBe 1
            result.err shouldContain "cz.matee.islegrow"
        }

        "app create hlídá rozsah intervalu ingestu" {
            val organization = seedOrganization()
            val result =
                cli("app create --org $organization --name IsleGrow --gp-package cz.matee.x --ingest-interval 2")
            result.code shouldBe 2
            result.err shouldContain "5"
        }

        "app create odmítne neznámou časovou zónu" {
            val organization = seedOrganization()
            val result =
                cli("app create --org $organization --name IsleGrow --gp-package cz.matee.x --timezone Europe/Prag")
            result.code shouldBe 2
            result.err shouldContain "Europe/Prag"
        }

        "příkaz nad neexistující organizací končí kódem 1" {
            val result = cli("app list --org neexistuje")
            result.code shouldBe 1
            result.err shouldContain "neexistuje"
        }

        "credential add uloží service account a nevypíše z něj nic tajného" {
            val organization = seedOrganization()
            val file = StoreKeyFixtures.serviceAccountFile(workDirectory)

            val result = cli("credential add --org $organization --type gp --label 'IsleGrow Play' --file '$file'")
            result.code shouldBe 0
            result.out shouldContain "sha256:"
            result.out shouldContain "reviews@isle-grow.iam.gserviceaccount.com"
            result.out shouldNotContain "PRIVATE KEY"

            val listed = cli("credential list --org $organization")
            listed.out shouldContain "GP_SERVICE_ACCOUNT"
            listed.out shouldContain "UNKNOWN"
        }

        "credential add poskládá klíč App Store Connect z .p8 a Key ID" {
            val organization = seedOrganization()
            val file = StoreKeyFixtures.appStoreKeyFile(workDirectory)

            val result =
                cli(
                    "credential add --org $organization --type asc --label 'IsleGrow ASC' --file '$file' " +
                        "--key-id ABC123DEFG --issuer-id 69a6de70-1111-2222-3333-5d0663f0f1c9",
                )
            result.code shouldBe 0
            result.out shouldContain "Key ID ABC123DEFG"
            result.out shouldNotContain "individuální"
        }

        "credential add odmítne soubor, který klíčem není" {
            val organization = seedOrganization()
            val file = workDirectory.resolve("nesmysl.json")
            Files.writeString(file, "{}")

            val result = cli("credential add --org $organization --type gp --label Rozbité --file '$file'")
            result.code shouldBe 1
            result.err shouldContain "nejde načíst"
        }

        "credential add hlásí chybějící soubor" {
            val organization = seedOrganization()
            val missing = workDirectory.resolve("neni-tady.json")

            val result = cli("credential add --org $organization --type gp --label Chybí --file '$missing'")
            result.code shouldBe 1
            result.err shouldContain "neexistuje"
        }

        "credential attach odmítne klíč ke storu, který appka nemá" {
            val appId = seedApp()
            val credentialId = seedAppStoreKey()

            val result = cli("credential attach --org isle-grow --app $appId --credential $credentialId")
            result.code shouldBe 1
            result.err shouldContain "IOS"
        }

        "credential attach připojí klíč k aplikaci" {
            val appId = seedApp()
            val file = StoreKeyFixtures.serviceAccountFile(workDirectory)
            val credentialId =
                cli("credential add --org isle-grow --type gp --label 'IsleGrow Play' --file '$file'").uuid()

            val result = cli("credential attach --org isle-grow --app $appId --credential $credentialId")
            result.code shouldBe 0
            result.out shouldContain "reviews"
        }

        "ingest run bez připojeného klíče řekne, proč nic nestáhl" {
            val appId = seedApp()
            val result = cli("ingest run --org isle-grow --app $appId")
            result.code shouldBe 0
            result.out shouldContain "MISSING_CREDENTIAL"
            result.out shouldContain "k doručení do kanálů: 0"
        }

        "credential validate nesahá do storu, ke kterému appka nepatří" {
            val appId = seedApp()
            val credentialId = seedAppStoreKey()

            val result = cli("credential validate --org isle-grow --app $appId --credential $credentialId")
            result.code shouldBe 1
            result.err shouldContain "IOS"
        }

        "channel test nad aplikací bez kanálu poradí, co udělat" {
            val app = seedApp()
            val result = cli("channel test --org isle-grow --app $app")
            result.code shouldBe 1
            result.err shouldContain "channel add"
        }

        "channel test odmítne kanál, který u aplikace není" {
            val app = seedApp()
            val result = cli("channel test --org isle-grow --app $app --channel C0NEEXISTUJE")
            result.code shouldBe 1
            result.err shouldContain "channel list"
        }

        "jobs failed nad prázdnou frontou nic nepředstírá" {
            val result = cli("jobs failed")
            result.code shouldBe 0
            result.out shouldContain "žádnou neuzavřenou chybu"
        }

        "review list nad prázdnou aplikací nic nepředstírá" {
            val appId = seedApp()
            val result = cli("review list --org isle-grow --app $appId")
            result.code shouldBe 0
            result.out shouldContain "zatím nemá uložené recenze"
        }

        "review list odmítne ID aplikace, které není UUID" {
            seedOrganization()
            val result = cli("review list --org isle-grow --app IsleGrow")
            result.code shouldBe 2
            result.err shouldContain "UUID"
        }
    })

private data class CliResult(
    val code: Int,
    val out: String,
    val err: String,
) {
    /** ID vypsané právě založeným objektem — CLI ho tiskne, aby na něj šlo navázat dalším příkazem. */
    fun uuid(): String {
        val match = UUID_PATTERN.find(out)
        match shouldNotBe null
        return match?.value.orEmpty()
    }
}

/** Rozsekání příkazu na tokeny; apostrofy drží pohromadě hodnoty s mezerami, jako v shellu. */
private fun tokenize(commandLine: String): List<String> =
    TOKEN_PATTERN
        .findAll(commandLine)
        .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
        .toList()

private val TOKEN_PATTERN = Regex("'([^']*)'|(\\S+)")
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
