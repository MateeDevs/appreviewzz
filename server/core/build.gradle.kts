plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)

    // kotlin-logging si slf4j binding netahá; bez něj testy padají na chybějící LoggerFactory.
    testRuntimeOnly(libs.logback.classic)
}
