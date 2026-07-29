pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Upstream phono also declares maven.pkg.github.com/lightphone/light-keyboard here
        // for the private LP3 keyboard artifact, which required a GitHub PAT in
        // local.properties (gpr.user / gpr.key) just to resolve dependencies. LightPhono
        // uses the system IME, so every dependency now comes from public repos and a
        // clean checkout builds with no credentials.
    }
}

rootProject.name = "LightPhono"
include(":light-ui")
include(":app")
