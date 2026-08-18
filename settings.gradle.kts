rootProject.name = "appreviewzz"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    ":server:core",
    ":server:persistence",
    ":server:crypto",
    ":server:connectors:googleplay",
    ":server:connectors:appstore",
    ":server:channels:slack",
    ":server:channels:teams",
    ":server:ai",
    ":server:jobs",
    ":server:app",
)
