import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "cz.matee.appreviewzz"
    version = providers.gradleProperty("appVersion").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    dependencies {
        add("testImplementation", versionCatalog.findBundle("testing").get())

        // AWS SDK (apache5-client) si přitáhne httpcore5 5.4.2 se dvěma HIGH CVE (DoS přes
        // hlavičky a HPACK). Apache opravu vydal dřív, než ji SDK zvedlo, a Trivy sken v CI
        // na tom shazuje build — po upgradu SDK se tenhle constraint dá zase smazat.
        constraints {
            val httpcore5 = versionCatalog.findVersion("httpcore5").get().requiredVersion
            add("implementation", "org.apache.httpcomponents.core5:httpcore5:$httpcore5")
            add("implementation", "org.apache.httpcomponents.core5:httpcore5-h2:$httpcore5")
        }
    }
}
