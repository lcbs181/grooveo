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
// The Android app needs an Android SDK; without one (e.g. building only the Linux
// desktop app from the source tarball) it is left out and :desktop still builds,
// since it compiles the shared sources straight from app/src.
val hasAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !System.getenv(it).isNullOrBlank() } ||
    file("local.properties").takeIf { it.isFile }?.readLines()?.any { it.trimStart().startsWith("sdk.dir=") } == true
if (hasAndroidSdk) include(":app") else logger.lifecycle("No Android SDK found - building without :app")
include(":desktop")
