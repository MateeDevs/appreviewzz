package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.app.cli.runCli
import cz.matee.appreviewzz.persistence.Database
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val config = AppConfig.fromEnv()

    // Argumenty = seed CLI (F1.7); bez nich se startuje server v roli podle konfigurace.
    if (args.isNotEmpty()) exitProcess(runCli(args.toList(), config))

    logger.info {
        "Starting appreviewzz ${BuildInfo.version} (${BuildInfo.gitSha}) " +
            "role=${config.role} env=${config.environment}"
    }

    val database = Database.connect(config.database)
    Runtime.getRuntime().addShutdownHook(Thread(database::close, "database-shutdown"))

    if (config.database.migrateOnStart) {
        database.migrate()
    }

    val components = Components(config, database)
    when (config.role) {
        Role.API -> runApi(config, database, components)
        Role.WORKER -> runWorker(config, database, components)
    }
}
