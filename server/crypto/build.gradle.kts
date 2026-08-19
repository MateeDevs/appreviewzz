dependencies {
    api(project(":server:core"))
    implementation(libs.tink)
    implementation(libs.aws.kms)
    implementation(libs.kotlin.logging)
}
