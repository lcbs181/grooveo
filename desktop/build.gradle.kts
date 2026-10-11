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

// Native DSP (native/CMakeLists.txt), built per OS into one shared library shipped
// as an app resource and loaded through JNA (audio/NativeDsp.kt):
//  - aubio 0.4.9 (beat tracking, GPL-3.0)
//  - Signalsmith Stretch 1.4.0 + Signalsmith Linear 0.6.4 (time-stretching, MIT)
// Release tarballs are downloaded and checked against their SHA-256. Needs CMake and
// a C/C++ compiler (gcc/clang, or MSVC on Windows); -Pgrooveo.cmake=<path> picks the
// CMake binary. Packaging always builds it. For tests and `run` it is skipped with a
// warning when CMake or the download is unavailable: the app then runs without BPM
// detection and tempo sync, and the tests that need the library are skipped.
data class NativeSource(val dir: String, val url: String, val sha256: String)
val nativeSources = mapOf(
    "AUBIO_SRC" to NativeSource("aubio-0.4.9", "https://aubio.org/pub/aubio-0.4.9.tar.bz2",
        "d48282ae4dab83b3dc94c16cf011bcb63835c1c02b515490e1883049c3d1f3da"),
    "STRETCH_SRC" to NativeSource("signalsmith-stretch-1.4.0", "https://github.com/Signalsmith-Audio/signalsmith-stretch/archive/refs/tags/1.4.0.tar.gz",
        "077235709ecf2a358545e3ca7bdf32859f70f5c9afb4493e078bd753c9aff284"),
    "LINEAR_SRC" to NativeSource("linear-0.6.4", "https://github.com/Signalsmith-Audio/linear/archive/refs/tags/0.6.4.tar.gz",
        "2cb10d84b96c626255ab46c4aa4c80f64ae532be487063cff6a08903c1445a56"),
)
val composeResourceOs = nativePlatform.replace("x86_64", "x64").replace("macosx", "macos")
val isWindows = nativePlatform.startsWith("windows")
val nativeDir = layout.buildDirectory.dir("native")
val nativeLibName = when {
    isWindows -> "grooveo_native.dll"
    nativePlatform.startsWith("macosx") -> "libgrooveo_native.dylib"
    else -> "libgrooveo_native.so"
}
val nativeOutDir = nativeDir.map { it.dir("resources/$composeResourceOs") }
val cmake = providers.gradleProperty("grooveo.cmake").getOrElse("cmake")
val cmakeAvailable by lazy {
    runCatching {
        val p = ProcessBuilder(cmake, "--version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor() == 0
    }.getOrDefault(false)
}
/** A packaging task runs: then a missing CMake, download or compiler fails the build. */
var nativeRequired = false
gradle.taskGraph.whenReady {
    nativeRequired = allTasks.any { it.project == project && (it.name.startsWith("package") || it.name.contains("Distributable")) }
}
/** Why the native library is not built in this run (tests and `run` only), or null. */
var nativeSkipped: String? = null
/** Records [reason] (rethrows [e] when packaging) and warns once. */
fun skipNative(reason: String, e: Exception? = null) {
    if (nativeRequired) throw e ?: GradleException(reason)
    if (nativeSkipped != null) return
    nativeSkipped = reason
    logger.warn("WARNING: native DSP library not built: $reason\n  The app runs without BPM detection and tempo sync, tests that need the library are skipped. Packaging requires it.")
}
fun nativeWanted(): Boolean {
    if (nativeSkipped == null && !nativeRequired && !cmakeAvailable) skipNative("'$cmake' not found (install CMake and a C/C++ compiler, or set -Pgrooveo.cmake=<path>)")
    return nativeSkipped == null
}
fun nativeSourceFile(dl: Directory, src: NativeSource) = dl.file("${src.dir}-${src.url.substringAfterLast('/')}").asFile
val downloadNativeSources by tasks.registering {
    val dl = nativeDir.map { it.dir("download") }
    inputs.property("sources", nativeSources.values.map { it.sha256 })
    outputs.dir(dl)
    outputs.upToDateWhen { nativeSources.values.all { nativeSourceFile(dl.get(), it).isFile } }
    onlyIf { nativeWanted() }
    doLast {
        try {
            nativeSources.values.forEach { src ->
                val f = nativeSourceFile(dl.get(), src)
                f.parentFile.mkdirs()
                if (!f.isFile) {
                    val part = File(f.path + ".part")
                    try {
                        val c = uri(src.url).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 60_000 }
                        c.getInputStream().use { s -> part.outputStream().use { s.copyTo(it) } }
                    } catch (e: java.io.IOException) {
                        part.delete()
                        throw GradleException("Download of ${src.url} failed ($e). Check the network connection or proxy settings.", e)
                    }
                    part.renameTo(f)
                }
                val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                if (sha != src.sha256) {
                    f.delete()
                    throw GradleException(
                        "${src.url}: SHA-256 $sha, expected ${src.sha256}. Either the download was corrupted or replaced, " +
                            "or GitHub regenerated the archive tarball with a different checksum (this happens). " +
                            "Verify the source before updating the hash in desktop/build.gradle.kts."
                    )
                }
            }
        } catch (e: GradleException) {
            skipNative(e.message ?: e.toString(), e)
        }
    }
}
val unpackNativeSources by tasks.registering(Sync::class) {
    dependsOn(downloadNativeSources)
    onlyIf { nativeWanted() }
    nativeSources.values.forEach { src ->
        val name = "${src.dir}-${src.url.substringAfterLast('/')}"
        from(nativeDir.map { d ->
            val f = d.file("download/$name").asFile
            tarTree(if (name.endsWith(".bz2")) resources.bzip2(f) else resources.gzip(f))
        })
    }
    into(nativeDir.map { it.dir("src") })
}
val buildNative by tasks.registering {
    val srcDir = project.file("native")
    val cmakeDir = nativeDir.map { it.dir("cmake") }
    dependsOn(unpackNativeSources)
    inputs.dir(srcDir)
    inputs.files(unpackNativeSources)
    outputs.dir(nativeOutDir)
    outputs.upToDateWhen { nativeOutDir.get().file(nativeLibName).asFile.isFile }
    onlyIf { nativeWanted() }
    doLast {
        val build = cmakeDir.get().asFile
        val defs = nativeSources.map { (k, v) -> "-D$k=${nativeDir.get().dir("src/${v.dir}").asFile}" }
        try {
            providers.exec { commandLine(listOf(cmake, "-S", srcDir.path, "-B", build.path, "-DCMAKE_BUILD_TYPE=Release") + defs) }.result.get()
            providers.exec { commandLine(cmake, "--build", build, "--config", "Release") }.result.get()
        } catch (e: Exception) {
            skipNative("CMake build failed: ${e.message}", e)
            return@doLast
        }
        val lib = build.walkTopDown().first { it.name == nativeLibName }
        val out = nativeOutDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        lib.copyTo(out.resolve(nativeLibName))
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
    dependsOn(buildNative)
    systemProperty("grooveo.native.dir", nativeOutDir.get().asFile.absolutePath)
    // tests that need the native library skip themselves only when it could not be built
    inputs.property("nativeBuilt", provider { nativeOutDir.get().file(nativeLibName).asFile.isFile })
    doFirst { if (!nativeOutDir.get().file(nativeLibName).asFile.isFile) systemProperty("grooveo.native.skipped", "true") }
}

compose.desktop {
    application {
        mainClass = "dev.schlubbe.musicagent.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "Grooveo"
            appResourcesRootDir.set(buildNative.map { nativeDir.get().dir("resources") })
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
