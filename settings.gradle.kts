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
        // Upstream phono declared maven.pkg.github.com/lightphone/light-keyboard here for the
        // private LP3 keyboard artifact. That one is gone — LightPhono uses the system IME —
        // but light-common brings the credential requirement back, because GitHub Packages has
        // no anonymous read even for public packages. Same property names as upstream used:
        // gpr.user / gpr.key in local.properties, a PAT with read:packages.
        // light-common lives in GitHub Packages, which has no anonymous read even for public
        // packages. Locally: gpr.user / gpr.key in local.properties (a PAT with read:packages).
        // In CI: the run's own GITHUB_ACTOR / GITHUB_TOKEN, or GPR_USER / GPR_TOKEN if a
        // cross-repo PAT is ever needed.
        maven {
            url = uri("https://maven.pkg.github.com/gi-os/light-common")
            credentials {
                // takeUnless(String::isBlank), not `?:` — an unset repository secret arrives as
                // an empty string rather than as null, so a plain elvis chain would hand Gradle
                // a blank username and never reach the fallback.
                username = System.getenv("GPR_USER")?.takeUnless(String::isBlank)
                    ?: System.getenv("GITHUB_ACTOR")?.takeUnless(String::isBlank)
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GPR_TOKEN")?.takeUnless(String::isBlank)
                    ?: System.getenv("GITHUB_TOKEN")?.takeUnless(String::isBlank)
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

rootProject.name = "LightPhono"
include(":light-ui")
include(":app")
