pluginManagement {
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

rootProject.name = "LocalAgent"

include(":app")

includeBuild("third_party/termlib") {
    dependencySubstitution {
        substitute(module("com.github.connectbot:lib")).using(project(":lib"))
    }
}
