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
)

data class AppConfig(
    val role: Role,
    val environment: String,
    val server: ServerConfig,
    val database: DatabaseConfig,
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
                    ),
                database =
                    DatabaseConfig(
                        jdbcUrl = env.required("DATABASE_URL"),
                        user = env.required("DATABASE_USER"),
                        password = env.required("DATABASE_PASSWORD"),
                        maxPoolSize = env.optional("DATABASE_MAX_POOL_SIZE", "10").toInt(),
                        migrateOnStart = env.optional("DATABASE_MIGRATE_ON_START", "true").toBooleanStrict(),
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
