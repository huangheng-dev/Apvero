rootProject.name = "apvero"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":apps:platform-server",
    ":modules:application",
    ":modules:identity",
    ":modules:governance",
    ":modules:capability-registry",
    ":modules:knowledge",
    ":modules:release",
    ":modules:runtime",
)
