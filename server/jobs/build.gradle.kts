plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":server:core"))
    api(libs.db.scheduler)
    implementation(project(":server:persistence"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(project(":server:persistence"))
    testImplementation(libs.bundles.testcontainers)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.logback.classic)
}
