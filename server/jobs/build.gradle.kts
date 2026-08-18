dependencies {
    api(project(":server:core"))
    implementation(project(":server:persistence"))
    implementation(libs.db.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
}
