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
    }
}

rootProject.name = "PolyLinguaEdge"

include(":app")
// TarsosDSP 2.5 core (GPL-3.0), vendored as a plain Java library so the build never depends on
// the author's personal Maven host. See tarsosdsp/LICENSE.
include(":tarsosdsp")
