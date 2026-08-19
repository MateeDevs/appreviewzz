dependencies {
    api(project(":server:core"))
    implementation(libs.aws.s3)
    implementation(libs.kotlin.logging)

    // Obnova zakládá databázi přes JDBC, než na ni pustí pg_restore.
    testRuntimeOnly(libs.postgresql)
    testImplementation(project(":server:persistence"))
    testImplementation(libs.bundles.testcontainers)
    testRuntimeOnly(libs.logback.classic)
}
