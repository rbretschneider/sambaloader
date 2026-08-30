pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
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

rootProject.name = "sambaloader"

include(":app")
include(":core:data")
include(":core:media")
include(":core:network")
include(":core:crypto")
include(":core:testing")
include(":sync")
