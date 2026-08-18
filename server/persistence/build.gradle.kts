dependencies {
    api(project(":server:core"))
    api(libs.bundles.exposed)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.testcontainers)
    testRuntimeOnly(libs.postgresql)
}
