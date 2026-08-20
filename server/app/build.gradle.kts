plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":server:core"))
    implementation(project(":server:persistence"))
    implementation(project(":server:crypto"))
    implementation(project(":server:backup"))
    implementation(project(":server:jobs"))
    implementation(project(":server:connectors:googleplay"))
    implementation(project(":server:connectors:appstore"))
    implementation(project(":server:channels:slack"))
    implementation(project(":server:channels:teams"))
    implementation(project(":server:ai"))

    implementation(libs.bundles.ktor.server)
    // Konektory berou HttpClient jako parametr — app ho musí umět pojmenovat, aby je složil.
    implementation(libs.ktor.client.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.micrometer.prometheus)
    implementation(libs.angus.mail)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.logging)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass.set("cz.matee.appreviewzz.app.MainKt")
    applicationName = "appreviewzz"
    applicationDefaultJvmArgs =
        listOf(
            "-XX:MaxRAMPercentage=75.0",
            "-XX:+UseZGC",
            // Uvítací řádek kotlin-loggingu jde na stdout mimo logback; ve výstupu seed CLI nemá co dělat.
            "-Dkotlin-logging.logStartupMessage=false",
        )
}

tasks.named<JavaExec>("run") {
    // lokální běh: `./gradlew :server:app:run` čte .env přes docker-compose, tady jen defaulty
    environment("APPREVIEWZZ_ROLE", providers.environmentVariable("APPREVIEWZZ_ROLE").getOrElse("api"))
}

val generateBuildInfo by tasks.registering(WriteProperties::class) {
    description = "Zapisuje verzi a git SHA do resources, aby je /health/live uměl vrátit."
    destinationFile.set(layout.buildDirectory.file("generated/buildinfo/appreviewzz-build.properties"))
    property("version", project.version.toString())
    property("gitSha", providers.environmentVariable("GIT_SHA").getOrElse("unknown"))
}

sourceSets.named("main") {
    resources.srcDir(
        generateBuildInfo.map {
            it.destinationFile
                .get()
                .asFile.parentFile
        },
    )
}
