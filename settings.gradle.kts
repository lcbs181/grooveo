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
        // NewPipeExtractor (on-device YouTube Music search/stream extraction, see
        // data/extract/youtube) is only published on JitPack, not Maven Central.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Grooveo"
include(":app")
