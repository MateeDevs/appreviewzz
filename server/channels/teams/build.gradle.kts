plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":server:core"))
    implementation(project(":server:crypto"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
}
