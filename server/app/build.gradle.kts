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
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.bundles.testcontainers)
}

application {
    mainClass.set("cz.matee.appreviewzz.app.MainKt")
    applicationName = "appreviewzz"
    applicationDefaultJvmArgs =
        listOf(
            // Bez limitu cgroup by procento RAM počítalo celý sdílený hostitel pro každou roli zvlášť.
            "-Xms64m",
            "-Xmx512m",
            "-XX:+UseG1GC",
            // I při malém provozu vracet nevyužitou paměť; jinak se čeká na další alokační tlak.
            "-XX:G1PeriodicGCInterval=60000",
            "-XX:+ExitOnOutOfMemoryError",
            // CLI má na stdout jen svůj výsledek; GC diagnostika patří na stderr.
            "-Xlog:gc=info:stderr:time,uptime,level,tags",
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
