package cz.matee.appreviewzz.app.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import cz.matee.appreviewzz.app.AppConfig
import cz.matee.appreviewzz.app.Components
import cz.matee.appreviewzz.persistence.Database
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Seed CLI (F1.7) — stejný image, další entrypoint. Než bude console (F3), zakládá se tudy
 * organizace, aplikace i klíče ke storům; potom zůstane jako nástroj pro ops a self-host.
 *
 * ```
 * docker compose run --rm app org create --name IsleGrow
 * ```
 *
 * Návratové kódy: `0` hotovo, `1` příkaz neprošel (neexistující organizace, neplatný klíč),
 * `2` špatně zadaný příkaz. Skript nad tím tak pozná rozdíl mezi překlepem a odmítnutím storu.
 */
fun runCli(
    argv: List<String>,
    config: AppConfig,
    out: (String) -> Unit = ::println,
    err: (String) -> Unit = System.err::println,
): Int {
    // Seed CLI je dialog s člověkem, ne služba: startovací INFO logy by přebily výstup příkazu.
    quietLogs()

    if (argv.isEmpty() || argv.first() in HELP_COMMANDS) {
        out(usage())
        return 0
    }

    val resolved =
        try {
            resolve(argv)
        } catch (error: UsageException) {
            err("chyba: ${error.message}")
            err(usage())
            return EXIT_USAGE
        }
    val (command, tokens) = resolved

    val arguments =
        try {
            Arguments.parse(tokens, command.options)
        } catch (error: UsageException) {
            err("chyba: ${error.message}")
            err("použití: ${command.usage}")
            return EXIT_USAGE
        }

    return try {
        // Spojení se otevírá až tady — nápověda ani překlep nemají důvod sahat na databázi.
        Database.connect(config.database).use { database ->
            if (config.database.migrateOnStart) database.migrate()
            Components(config, database).use { components ->
                runBlocking { command.run(SeedCommands(components, out), arguments) }
            }
        }
        0
    } catch (error: UsageException) {
        err("chyba: ${error.message}")
        err("použití: ${command.usage}")
        EXIT_USAGE
    } catch (error: CommandException) {
        err("chyba: ${error.message}")
        EXIT_FAILED
    } catch (error: IllegalStateException) {
        // Chybějící konfigurace (typicky VAULT_KEK_URI) — chyba prostředí, ne kódu.
        err("chyba: ${error.message}")
        EXIT_FAILED
    }
}

/** Jeden příkaz. `options` je zároveň kontrola překlepů — neznámá volba příkaz zastaví. */
private class Command(
    val name: String,
    val options: Set<String>,
    val usage: String,
    val run: suspend SeedCommands.(Arguments) -> Unit,
)

private val COMMANDS =
    listOf(
        Command(
            name = "org create",
            options = setOf("name", "slug"),
            usage = "org create --name <název> [--slug <slug>]",
            run = { args -> orgCreate(args) },
        ),
        Command(
            name = "org list",
            options = emptySet(),
            usage = "org list",
            run = { _ -> orgList() },
        ),
        Command(
            name = "org plan",
            options = setOf("org", "plan"),
            usage = "org plan --org <slug|ID> --plan starter|insights|agency",
            run = { args -> orgPlan(args) },
        ),
        Command(
            name = "user add",
            options = setOf("org", "email", "name", "role"),
            usage = "user add --org <slug|ID> --email <e-mail> [--name <jméno>] [--role owner|admin|member]",
            run = { args -> userAdd(args) },
        ),
        Command(
            name = "user platform-role",
            options = setOf("email", "role"),
            usage = "user platform-role [--email <e-mail>] [--role superadmin|none]  (bez --email jen vypíše, kdo ji má)",
            run = { args -> userPlatformRole(args) },
        ),
        Command(
            name = "platform config",
            options = emptySet(),
            usage = "platform config",
            run = { _ -> platformConfigList() },
        ),
        Command(
            name = "app create",
            options =
                setOf(
                    "org",
                    "name",
                    "gp-package",
                    "gp-bucket",
                    "asc-app-id",
                    "locale",
                    "timezone",
                    "notify-from",
                    "ai-instructions",
                    "ingest-interval",
                    "digest-at",
                ),
            usage =
                "app create --org <slug|ID> --name <název> [--gp-package <balíček>] [--gp-bucket <pubsite_prod_…>] " +
                    "[--asc-app-id <ID>] " +
                    "[--locale cs|en] [--timezone <zóna>] [--notify-from <ISO-8601>; výchozí je teď] " +
                    "[--ingest-interval <minuty>] [--digest-at HH:MM] [--ai-instructions <text>]",
            run = { args -> appCreate(args) },
        ),
        Command(
            name = "app list",
            options = setOf("org"),
            usage = "app list --org <slug|ID>",
            run = { args -> appList(args) },
        ),
        Command(
            name = "credential add",
            options = setOf("org", "type", "label", "file", "key-id", "issuer-id"),
            usage =
                "credential add --org <slug|ID> --type gp|asc --label <štítek> --file <cesta> " +
                    "[--key-id <Key ID>] [--issuer-id <Issuer ID>]",
            run = { args -> credentialAdd(args) },
        ),
        Command(
            name = "credential list",
            options = setOf("org"),
            usage = "credential list --org <slug|ID>",
            run = { args -> credentialList(args) },
        ),
        Command(
            name = "credential attach",
            options = setOf("org", "app", "credential", "purpose"),
            usage =
                "credential attach --org <slug|ID> --app <ID> --credential <ID> " +
                    "[--purpose reviews|replies|ratings]",
            run = { args -> credentialAttach(args) },
        ),
        Command(
            name = "credential mark-managed",
            options = setOf("org"),
            usage = "credential mark-managed [--org <slug|ID>]  (bez --org projde všechny organizace)",
            run = { args -> credentialMarkManaged(args) },
        ),
        Command(
            name = "credential validate",
            options = setOf("org", "app", "credential"),
            usage = "credential validate --org <slug|ID> --app <ID> --credential <ID>",
            run = { args -> credentialValidate(args) },
        ),
        Command(
            name = "slack install-url",
            options = setOf("org"),
            usage = "slack install-url --org <slug|ID>",
            run = { args -> slackInstallUrl(args) },
        ),
        Command(
            name = "slack connect",
            options = setOf("org", "token", "label"),
            usage = "slack connect --org <slug|ID> --token <xoxb-…> [--label <štítek>]",
            run = { args -> slackConnect(args) },
        ),
        Command(
            name = "teams connect",
            options = setOf("org", "tenant", "service-url", "team", "team-name", "label"),
            usage =
                "teams connect --org <slug|ID> --tenant <tenant ID> [--service-url <https://smba…>] " +
                    "[--team <19:…>] [--team-name <název>] [--label <štítek>]",
            run = { args -> teamsConnect(args) },
        ),
        Command(
            name = "channel add",
            options = setOf("org", "app", "credential", "slack-channel", "teams-channel", "label", "locale"),
            usage =
                "channel add --org <slug|ID> --app <ID> --credential <ID instalace> " +
                    "(--slack-channel <C…> | --teams-channel <19:…>) [--label <popis>] [--locale cs|en]",
            run = { args -> channelAdd(args) },
        ),
        Command(
            name = "channel list",
            options = setOf("org", "app"),
            usage = "channel list --org <slug|ID> --app <ID>",
            run = { args -> channelList(args) },
        ),
        Command(
            name = "channel test",
            options = setOf("org", "app", "channel"),
            usage = "channel test --org <slug|ID> --app <ID> [--channel <ID kanálu|C…>]",
            run = { args -> channelTest(args) },
        ),
        Command(
            name = "jobs failed",
            options = setOf("org", "limit"),
            usage = "jobs failed [--org <slug|ID>] [--limit <počet>]",
            run = { args -> jobsFailed(args) },
        ),
        Command(
            name = "ingest run",
            options = setOf("org", "app"),
            usage = "ingest run --org <slug|ID> --app <ID>",
            run = { args -> ingestRun(args) },
        ),
        Command(
            name = "history import",
            options = setOf("org", "app", "months"),
            usage = "history import --org <slug|ID> --app <ID> [--months <1-24>]",
            run = { args -> historyImport(args) },
        ),
        Command(
            name = "ratings run",
            options = setOf("org", "app"),
            usage = "ratings run --org <slug|ID> --app <ID>",
            run = { args -> ratingsRun(args) },
        ),
        Command(
            name = "analysis backfill",
            options = setOf("org", "app"),
            usage = "analysis backfill --org <slug|ID> --app <ID>",
            run = { args -> analysisBackfill(args) },
        ),
        Command(
            name = "analysis status",
            options = setOf("org", "app"),
            usage = "analysis status --org <slug|ID> --app <ID>",
            run = { args -> analysisStatus(args) },
        ),
        Command(
            name = "analysis export",
            options = setOf("org", "app", "limit", "out"),
            usage = "analysis export --org <slug|ID> --app <ID> --out <soubor.jsonl> [--limit <počet>]",
            run = { args -> analysisExport(args) },
        ),
        Command(
            name = "analysis eval",
            options = setOf("file", "model", "app-name"),
            usage = "analysis eval --file <gold.jsonl> [--model <model>] [--app-name <název>]",
            run = { args -> analysisEval(args) },
        ),
        Command(
            name = "analysis run",
            options = setOf("org", "app", "period-start", "force"),
            usage = "analysis run --org <slug|ID> --app <ID> [--period-start YYYY-MM-DD] [--force true]",
            run = { args -> analysisRun(args) },
        ),
        Command(
            name = "analysis report generate",
            options = setOf("org", "app", "month"),
            usage = "analysis report generate --org <slug|ID> --app <ID> [--month YYYY-MM]",
            run = { args -> analysisReportGenerate(args) },
        ),
        Command(
            name = "analysis report share",
            options = setOf("org", "report", "off"),
            usage = "analysis report share --org <slug|ID> --report <ID> [--off true]",
            run = { args -> analysisReportShare(args) },
        ),
        Command(
            name = "vault rotate",
            options = setOf("org"),
            usage = "vault rotate [--org <slug|ID>]",
            run = { args -> vaultRotate(args) },
        ),
        Command(
            name = "backup run",
            options = emptySet(),
            usage = "backup run",
            run = { _ -> backupRun() },
        ),
        Command(
            name = "backup list",
            options = emptySet(),
            usage = "backup list",
            run = { _ -> backupList() },
        ),
        Command(
            name = "backup restore",
            options = setOf("key", "database", "drop-existing"),
            usage = "backup restore --key <klíč zálohy> --database <nová databáze> [--drop-existing true]",
            run = { args -> backupRestore(args) },
        ),
        Command(
            name = "review list",
            options = setOf("org", "app", "limit", "state"),
            usage = "review list --org <slug|ID> --app <ID> [--limit <počet>] [--state new,notified]",
            run = { args -> reviewList(args) },
        ),
    )

/**
 * Jméno příkazu je jedno až tři slova před první `--volbou`. Hledá se od nejdelšího:
 * `analysis weekly run` by se tak nespletlo s hypotetickým `analysis weekly`.
 */
private fun resolve(argv: List<String>): Pair<Command, List<String>> {
    val words = argv.take(MAX_COMMAND_WORDS).takeWhile { !it.startsWith("--") }
    val command =
        (words.size downTo 1)
            .asSequence()
            .map { size -> words.take(size).joinToString(" ") }
            .mapNotNull { name -> COMMANDS.firstOrNull { it.name == name } }
            .firstOrNull()
            ?: throw UsageException("Neznámý příkaz '${words.joinToString(" ").ifBlank { argv.first() }}'")
    return command to argv.drop(command.name.split(' ').size)
}

private const val MAX_COMMAND_WORDS = 3

private fun usage(): String =
    buildString {
        appendLine("appreviewzz — seed CLI pro zakládání organizací, aplikací a klíčů ke storům")
        appendLine()
        appendLine("Použití: appreviewzz <příkaz> [--volba hodnota]")
        appendLine()
        COMMANDS.forEach { appendLine("  ${it.usage}") }
        appendLine()
        appendLine("Připojení k databázi, KEK a úložiště záloh se berou ze stejných proměnných prostředí")
        appendLine("jako server (DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, VAULT_KEK_URI, BACKUP_TARGET).")
        append("Bez příkazu se spustí server v roli podle APPREVIEWZZ_ROLE.")
    }

/**
 * Stáhne logy na WARN. Chyby a varování (třeba „vygenerován nový lokální keyset") zůstávají
 * vidět, provozní INFO z Hikari, Flyway a repozitářů uživatele příkazové řádky nezajímá.
 *
 * Nestačí přenastavit root: logback.xml má pro Hikari i Flyway vlastní `<logger>` s pevnou
 * úrovní, a ta by root ignorovala. Proto se nejdřív zahodí úrovně jednotlivých loggerů.
 */
private fun quietLogs() {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    context.loggerList.filterNot { it == root }.forEach { it.level = null }
    root.level = Level.WARN
}

private val HELP_COMMANDS = setOf("help", "--help", "-h")
private const val EXIT_FAILED = 1
private const val EXIT_USAGE = 2
