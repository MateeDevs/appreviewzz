package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.persistence.DatabaseConfig

/**
 * Jeden image, dvě role (§5.1 plánu). `api` obsluhuje HTTP, `worker` točí naplánované joby.
 */
enum class Role {
    API,
    WORKER,
    ;

    companion object {
        fun parse(raw: String): Role =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error("Unknown APPREVIEWZZ_ROLE='$raw', expected one of ${entries.joinToString { it.name.lowercase() }}")
    }
}

data class ServerConfig(
    val host: String,
    val port: Int,
    /** Port pro /metrics. Nikdy se nevystavuje ven. */
    val managementPort: Int,
)

/**
 * Nastavení plánovače (jen role `worker`). Výchozí hodnoty odpovídají provozu jednotek klientů —
 * polling po deseti sekundách znamená deset dotazů za minutu do prázdné tabulky, což Postgres
 * nepozná, a přitom se jednorázová úloha (publikace odpovědi) rozjede prakticky hned.
 */
data class WorkerConfig(
    val schedulerThreads: Int,
    val pollingIntervalSeconds: Long,
    val sweepIntervalSeconds: Long,
)

/**
 * Zálohy databáze (F1.8). Bez `BACKUP_TARGET` se úloha vůbec nezaregistruje a worker to při
 * startu hlásí varováním — vypnuté zálohy mají být vidět, ne se tvářit jako výchozí stav.
 *
 * Čas je v UTC schválně: server může kdykoli změnit zónu a záloha nemá důvod se řídit létem.
 */
data class BackupConfig(
    /** `s3://bucket/prefix` nebo `file:///cesta`; `null` = zálohy vypnuté. */
    val target: String?,
    /** Denní čas spuštění ve tvaru `HH:MM` (UTC). */
    val at: String,
    val retentionDays: Int,
    val keepAtLeast: Int,
    /** Vlastní S3 endpoint (MinIO, Backblaze) — prázdné znamená AWS. */
    val s3Endpoint: String?,
    val pgDumpPath: String,
    val pgRestorePath: String,
    val timeoutMinutes: Long,
) {
    val enabled: Boolean get() = target != null
}

/**
 * AI návrhy odpovědí. Volba je per deployment (plán §5.5) — cloud jede na Gemini kvůli paritě
 * s n8n, self-host si zapne, co chce, nebo `none` a odpovědi píše rovnou člověk.
 */
data class AiConfig(
    val provider: String,
    val apiKey: String?,
    val model: String?,
)

/**
 * Naše Slack App. Signing secret je app-level (jeden pro všechny klienty) — tím padá dnešní
 * per-klient HMAC chaos v n8n. Bez něj se interactivity endpoint vůbec nezaregistruje:
 * webhook bez ověření podpisu by byl otevřený vstup do publikace odpovědí.
 */
data class SlackConfig(
    val signingSecret: String?,
    val clientId: String?,
    val clientSecret: String?,
    /** Veřejná adresa API (`https://api.appreviewzz.com`) — z ní se skládá OAuth redirect URL. */
    val publicBaseUrl: String?,
) {
    val enabled: Boolean get() = signingSecret != null

    /** Install flow potřebuje navíc OAuth údaje a adresu, na kterou se Slack vrací. */
    val installEnabled: Boolean
        get() = enabled && clientId != null && clientSecret != null && publicBaseUrl != null
}

data class AppConfig(
    val role: Role,
    val environment: String,
    val server: ServerConfig,
    val database: DatabaseConfig,
    /**
     * URI správce klíčů credential vaultu (`aws-kms://…`, `local://…`). Povinné pro roli
     * `worker`, která credentials rozbaluje; API ho začne potřebovat s onboardingem ve F3.
     */
    val vaultKekUri: String?,
    val worker: WorkerConfig,
    val backup: BackupConfig,
    val ai: AiConfig,
    val slack: SlackConfig,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): AppConfig =
            AppConfig(
                role = Role.parse(env.optional("APPREVIEWZZ_ROLE", "api")),
                environment = env.optional("APPREVIEWZZ_ENV", "local"),
                server =
                    ServerConfig(
                        host = env.optional("SERVER_HOST", "0.0.0.0"),
                        port = env.optional("SERVER_PORT", "8080").toInt(),
                        managementPort = env.optional("MANAGEMENT_PORT", "8081").toInt(),
                    ),
                database =
                    DatabaseConfig(
                        jdbcUrl = env.required("DATABASE_URL"),
                        user = env.required("DATABASE_USER"),
                        password = env.required("DATABASE_PASSWORD"),
                        maxPoolSize = env.optional("DATABASE_MAX_POOL_SIZE", "10").toInt(),
                        migrateOnStart = env.optional("DATABASE_MIGRATE_ON_START", "true").toBooleanStrict(),
                    ),
                vaultKekUri = env("VAULT_KEK_URI")?.takeIf { it.isNotBlank() },
                worker =
                    WorkerConfig(
                        schedulerThreads = env.optional("SCHEDULER_THREADS", "5").toInt(),
                        pollingIntervalSeconds = env.optional("SCHEDULER_POLLING_SECONDS", "10").toLong(),
                        sweepIntervalSeconds = env.optional("INGEST_SWEEP_SECONDS", "60").toLong(),
                    ),
                backup =
                    BackupConfig(
                        target = env("BACKUP_TARGET")?.takeIf { it.isNotBlank() },
                        at = env.optional("BACKUP_AT", "02:30"),
                        retentionDays = env.optional("BACKUP_RETENTION_DAYS", "30").toInt(),
                        keepAtLeast = env.optional("BACKUP_KEEP_AT_LEAST", "7").toInt(),
                        s3Endpoint = env("BACKUP_S3_ENDPOINT")?.takeIf { it.isNotBlank() },
                        pgDumpPath = env.optional("PG_DUMP_PATH", "pg_dump"),
                        pgRestorePath = env.optional("PG_RESTORE_PATH", "pg_restore"),
                        timeoutMinutes = env.optional("BACKUP_TIMEOUT_MINUTES", "30").toLong(),
                    ),
                ai =
                    AiConfig(
                        provider = env.optional("AI_PROVIDER", "none"),
                        apiKey = env("AI_API_KEY")?.takeIf { it.isNotBlank() },
                        model = env("AI_MODEL")?.takeIf { it.isNotBlank() },
                    ),
                slack =
                    SlackConfig(
                        signingSecret = env("SLACK_SIGNING_SECRET")?.takeIf { it.isNotBlank() },
                        clientId = env("SLACK_CLIENT_ID")?.takeIf { it.isNotBlank() },
                        clientSecret = env("SLACK_CLIENT_SECRET")?.takeIf { it.isNotBlank() },
                        publicBaseUrl = env("PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() },
                    ),
            )
    }
}

private fun ((String) -> String?).required(name: String): String =
    this(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable $name")

private fun ((String) -> String?).optional(
    name: String,
    default: String,
): String = this(name)?.takeIf { it.isNotBlank() } ?: default
