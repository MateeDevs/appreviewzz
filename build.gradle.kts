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

        // AWS SDK (apache5-client) i httpclient5 si pořád říkají o httpcore5 5.4.3; drží se
        // poslední opravená řada, protože starší verze měly HIGH CVE (DoS přes hlavičky
        // a HPACK) a Trivy sken v CI na tom shazuje build. Smazat, až SDK chce aspoň tohle.
        constraints {
            val httpcore5 = versionCatalog.findVersion("httpcore5").get().requiredVersion
            add("implementation", "org.apache.httpcomponents.core5:httpcore5:$httpcore5")
            add("implementation", "org.apache.httpcomponents.core5:httpcore5-h2:$httpcore5")
        }

        // Netty jednou rodinou: Ktor chce 4.2, AWS SDK (netty-nio-client) pořád 4.1. Bez BOM
        // by se moduly rozjely na dvě řady — spolehlivý způsob, jak si vyrobit
        // NoSuchMethodError — a stará řada navíc nesla CRITICAL CVE (CVE-2026-75595).
        add("implementation", platform("io.netty:netty-bom:${versionCatalog.findVersion("netty").get().requiredVersion}"))

        // Jackson 3 celou rodinou přes BOM: logstash-logback-encoder 9.0 si říká o databind
        // 3.0.1 a řada 3.1 měla HIGH CVE (CVE-2026-68497, CVE-2026-89407 a spol.).
        // Smazat, až závislosti samy chtějí opravenou verzi.
        add("implementation", platform("tools.jackson:jackson-bom:${versionCatalog.findVersion("jackson").get().requiredVersion}"))
    }
}
