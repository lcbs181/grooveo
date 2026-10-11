import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

val versionName = providers.gradleProperty("grooveo.versionName").get()
val versionCode = providers.gradleProperty("grooveo.versionCode").get().toInt()

// FFmpeg/JavaCPP natives for the OS doing the build (jpackage cannot cross-package,
// so Windows installers are built on Windows, see .github/workflows/release.yml).
val nativePlatform = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").contains("aarch64")
    when {
        "win" in os -> "windows-x86_64"
        "mac" in os -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

// aubio (beat tracking, GPL-3.0) is built from its release tarball together with
// native/aubio/grooveo_aubio.c into one shared library per OS and shipped as an app
// resource; Kotlin loads it through JNA (see audio/BeatTracker.kt). Needs CMake and a
// C compiler (gcc/clang, or MSVC on Windows).
val aubioVersion = "0.4.9"
val aubioSha256 = "d48282ae4dab83b3dc94c16cf011bcb63835c1c02b515490e1883049c3d1f3da"
val composeResourceOs = nativePlatform.replace("x86_64", "x64").replace("macosx", "macos")
val isWindows = nativePlatform.startsWith("windows")
val aubioDir = layout.buildDirectory.dir("aubio")
val downloadAubio by tasks.registering {
    val archive = aubioDir.map { it.file("aubio-$aubioVersion.tar.bz2") }
    outputs.file(archive)
    doLast {
        val f = archive.get().asFile
        f.parentFile.mkdirs()
        uri("https://aubio.org/pub/aubio-$aubioVersion.tar.bz2").toURL().openStream().use { s -> f.outputStream().use { s.copyTo(it) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (sha != aubioSha256) { f.delete(); throw GradleException("aubio-$aubioVersion.tar.bz2: SHA-256 $sha, expected $aubioSha256") }
    }
}
val unpackAubio by tasks.registering(Sync::class) {
    from(downloadAubio.map { tarTree(resources.bzip2(it.outputs.files.singleFile)) })
    into(aubioDir.map { it.dir("src") })
}
val buildAubio by tasks.registering {
    val srcDir = project.file("native/aubio")
    val cmakeDir = aubioDir.map { it.dir("cmake") }
    val outDir = aubioDir.map { it.dir("resources/$composeResourceOs") }
    dependsOn(unpackAubio)
    inputs.dir(srcDir)
    inputs.files(unpackAubio)
    outputs.dir(outDir)
    doLast {
        val aubioSrc = aubioDir.get().dir("src/aubio-$aubioVersion").asFile
        val build = cmakeDir.get().asFile
        providers.exec { commandLine("cmake", "-S", srcDir, "-B", build, "-DAUBIO_SRC=$aubioSrc", "-DCMAKE_BUILD_TYPE=Release") }.result.get()
        providers.exec { commandLine("cmake", "--build", build, "--config", "Release") }.result.get()
        val name = when {
            isWindows -> "grooveo_aubio.dll"
            nativePlatform.startsWith("macosx") -> "libgrooveo_aubio.dylib"
            else -> "libgrooveo_aubio.so"
        }
        val lib = build.walkTopDown().first { it.name == name }
        val out = outDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        lib.copyTo(out.resolve(name))
    }
}

// APP_VERSION constant generated from gradle.properties (single version source).
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", versionName)
    outputs.dir(out)
    doLast {
        val f = out.get().file("dev/schlubbe/musicagent/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package dev.schlubbe.musicagent.desktop\n\nconst val APP_VERSION = \"$versionName\"\n")
    }
}

// Platform-independent sources shared verbatim with the Android app (:app).
// Android-only types they reference (android.util.Log, Room DAOs, DataStore-backed
// SettingsRepository, ...) are provided by desktop implementations with the same
// fully-qualified names under src/main/kotlin, so fixes to extraction, feed and
// DSP logic land in both apps at once.
val sharedRoot = rootProject.file("app/src/main/java/dev/schlubbe/musicagent")
val sharedFiles = listOf(
    "data/extract/di/ExtractionHttpClient.kt",
    "data/extract/ResolvedStream.kt",
    "data/extract/StreamResolverRegistry.kt",
    "data/extract/YouTubeFallback.kt",
    "data/extract/soundcloud/SoundCloudApi.kt",
    "data/extract/soundcloud/SoundCloudClientIdProvider.kt",
    "data/extract/soundcloud/SoundCloudMappers.kt",
    "data/extract/soundcloud/SoundCloudSearchClient.kt",
    "data/extract/soundcloud/SoundCloudStreamResolver.kt",
    "data/extract/youtube/NewPipeDownloader.kt",
    "data/extract/youtube/YouTubeMusicSearchClient.kt",
    "data/extract/youtube/YouTubeStreamResolver.kt",
    "data/remote/dto/SearchDtos.kt",
    "data/remote/dto/LibraryDtos.kt",
    "data/local/entity/TrackEntity.kt",
    "data/local/entity/DownloadEntity.kt",
    "data/repository/SearchRepository.kt",
    "data/repository/FeedRepository.kt",
    "data/repository/DiscoverySafetyFilter.kt",
    "data/repository/LyricsRepository.kt",
    "data/backup/BackupModels.kt",
    "data/backup/SyncMerge.kt",
    "data/backup/SyncProtocol.kt",
    "playback/reverb/Fft.kt",
    "playback/reverb/PartitionedConvolver.kt",
    "playback/eq/ParametricEq.kt",
    "playback/eq/MatchedBiquad.kt",
    "playback/eq/EqProcessor.kt",
    "playback/eq/Loudness.kt",
)
val syncShared by tasks.registering(Sync::class) {
    from(sharedRoot) { sharedFiles.forEach { include(it) } }
    into(layout.buildDirectory.dir("shared/dev/schlubbe/musicagent"))
}
// Archivo, the Canopy design system's typeface, shipped with the Android app.
val syncFonts by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/res/font"))
    into(layout.buildDirectory.dir("sharedRes/fonts"))
}
sourceSets.main {
    kotlin.srcDir(syncShared.map { layout.buildDirectory.dir("shared").get() })
    kotlin.srcDir(generateBuildInfo.map { layout.buildDirectory.dir("generated/buildinfo").get() })
    resources.srcDir(rootProject.file("app/src/main/assets"))
    resources.srcDir(syncFonts.map { layout.buildDirectory.dir("sharedRes").get() })
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.phosphor.icon)
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    implementation(libs.okhttp.core)
    implementation(libs.zxing.core)
    implementation(libs.jna)
    implementation(libs.mcp.kotlin.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.newpipe.extractor)
    implementation(libs.jsoup)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("javax.inject:javax.inject:1")
    implementation("androidx.room:room-common:${libs.versions.room.get()}")
    // FFmpeg (libavformat/libavcodec for decoding),
    // in-process via JavaCPP presets. Only the FFmpeg parts of JavaCV are needed.
    implementation("org.bytedeco:javacv:1.5.14") {
        listOf("opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense",
            "librealsense2", "videoinput", "artoolkitplus", "flandmark", "leptonica", "tesseract").forEach {
            exclude(group = "org.bytedeco", module = it)
        }
    }
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    // MPRIS2 (media keys, GNOME/KDE media controls) over the D-Bus session bus
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
    runtimeOnly("org.bytedeco:ffmpeg:8.1.2-1.5.14:$nativePlatform")
    runtimeOnly("org.bytedeco:javacpp:1.5.14:$nativePlatform")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildAubio)
    systemProperty("grooveo.native.dir", aubioDir.get().dir("resources/$composeResourceOs").asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "dev.schlubbe.musicagent.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "Grooveo"
            appResourcesRootDir.set(buildAubio.map { aubioDir.get().dir("resources") })
            // from `./gradlew :desktop:suggestRuntimeModules`; jdk.security.auth is needed by dbus-java (MPRIS)
            modules("java.instrument", "java.management", "java.net.http", "java.sql", "jdk.dynalink", "jdk.httpserver", "jdk.security.auth", "jdk.unsupported")
            packageVersion = versionName
            description = "Grooveo desktop music player"
            vendor = "lcbs181"
            linux { iconFile.set(project.file("src/main/resources/grooveo.png")) }
            windows {
                iconFile.set(project.file("src/main/resources/grooveo.ico"))
                // MSI versions need MAJOR > 0, so the installer carries 1.0.<versionCode>
                packageVersion = "1.0.$versionCode"
                upgradeUuid = "6c1b5c0e-3f0a-4c2e-9a51-6a8f0f2d7b41"
                menu = true
                menuGroup = "Grooveo"
                shortcut = true
                perUserInstall = true
                dirChooser = true
            }
        }
    }
}
