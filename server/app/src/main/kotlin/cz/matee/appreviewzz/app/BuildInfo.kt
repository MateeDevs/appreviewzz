package cz.matee.appreviewzz.app

import java.util.Properties

/** Verze a git SHA se generují při buildu (task `generateBuildInfo`) a jedou až do /health/live. */
object BuildInfo {
    private val properties: Properties =
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/appreviewzz-build.properties")?.use { load(it) }
        }

    val version: String = properties.getProperty("version") ?: "unknown"
    val gitSha: String = properties.getProperty("gitSha") ?: "unknown"
}
