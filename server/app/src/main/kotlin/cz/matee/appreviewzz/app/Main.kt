package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.persistence.Database
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = AppConfig.fromEnv()
    logger.info {
        "Starting appreviewzz ${BuildInfo.version} (${BuildInfo.gitSha}) " +
            "role=${config.role} env=${config.environment}"
    }

    val database = Database.connect(config.database)
    Runtime.getRuntime().addShutdownHook(Thread(database::close, "database-shutdown"))

    if (config.database.migrateOnStart) {
        database.migrate()
    }

    when (config.role) {
        Role.API -> runApi(config, database)
        Role.WORKER -> runWorker(config, database)
    }
}
