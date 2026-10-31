import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Service-account credentials for the silent background login that keeps analytics
// events flowing to the backend without ever showing a login screen (see
// MusicAgentApp.onCreate / AuthRepository). Read from local.properties (not
// committed) rather than hardcoded, same pattern as sdk.dir. The account itself
// must be created once against the real backend via its existing registration
// flow -- this is operator setup, not something the build does automatically.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    // Native libraries compressed in the APK (~9 MB smaller download): Android unpacks
    // only the device's ABI at install, so the space used on the phone shrinks as well.
    packaging { jniLibs { useLegacyPackaging = true } }
    // Deliberately NOT changed: every .kt file in this source tree still declares
    // `package dev.schlubbe.musicagent...`, and AndroidManifest.xml's component
    // entries (e.g. ".MainActivity") resolve against `namespace`, not applicationId.
    // Changing this without renaming every source file's package would break
    // manifest resolution. applicationId (below) is what actually needs to differ
    // for the two apps to install side by side -- it's independent of namespace.
    namespace = "dev.schlubbe.musicagent"
    compileSdk = 37

    defaultConfig {
        // Distinct from the server-backed app ("dev.schlubbe.musicagent") so both
        // can be installed side by side on the same device -- this is a genuinely
        // separate app, not a build variant of the original.
        applicationId = "dev.schlubbe.musicagent.standalone"
        minSdk = 30
        targetSdk = 37
        // grooveo.versionCode counts releases; Android gets 100 codes per release so test
        // builds (grooveo.testBuild = 1..99) fit between two releases without using one up:
        // release 29 is 2900, its test builds before it are 2801, 2802, ... (docs/RELEASING.md)
        val release = providers.gradleProperty("grooveo.versionCode").get().toInt()
        val testBuild = providers.gradleProperty("grooveo.testBuild").getOrElse("0").toInt()
        require(testBuild in 0..99) { "grooveo.testBuild must be 0 (release) or 1..99" }
        versionCode = if (testBuild == 0) release * 100 else (release - 1) * 100 + testBuild
        versionName = providers.gradleProperty("grooveo.versionName").get() + if (testBuild == 0) "" else "-test.$testBuild"

        buildConfigField(
            "String",
            "SERVICE_ACCOUNT_EMAIL",
            "\"${localProperties.getProperty("serviceAccountEmail", "")}\"",
        )
        buildConfigField(
            "String",
            "SERVICE_ACCOUNT_PASSWORD",
            "\"${localProperties.getProperty("serviceAccountPassword", "")}\"",
        )
    }

    // Two apps from one source tree: "grooveo" is the full app (PC features, DJ mix,
    // AI assistant); "dawn" is Dawn Music, for
    // people without a PC - Spotify-style layout, only the features a casual listener
    // needs. Code checks BuildConfig.DAWN to switch; both install side by side.
    flavorDimensions += "edition"
    productFlavors {
        create("grooveo") {
            dimension = "edition"
            buildConfigField("boolean", "DAWN", "false")
            // update assets on GitHub releases: Grooveo-<version>.apk
            buildConfigField("String", "APK_PREFIX", "\"Grooveo\"")
            resValue("string", "app_name", "Grooveo")
        }
        create("dawn") {
            dimension = "edition"
            applicationId = "dev.schlubbe.musicagent.dawn"
            buildConfigField("boolean", "DAWN", "true")
            // update assets on GitHub releases: DawnMusic-<version>.apk
            buildConfigField("String", "APK_PREFIX", "\"DawnMusic\"")
            resValue("string", "app_name", "Dawn Music")
        }
    }

    buildTypes {
        release {
            // R8 only drops unused library code (see proguard-rules.pro: the app's own
            // classes are kept as they are), unused resources go too.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the same debug keystore the published debug APKs used, so
            // this installs as an in-place update over them. The point of release
            // over debug is debuggable=false: a debuggable build runs mostly in the
            // ART interpreter (measured ~90% of main-thread startup time on a real
            // device), which is where the startup/scroll jank came from.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer.hls)

    implementation(libs.newpipe.extractor)
    implementation(libs.jsoup)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.coil.compose)
    // backdrop blur for Dawn Music's glass bars
    implementation(libs.androidx.webkit)
    implementation(libs.haze)
    // Performance tracing: named Perfetto slices for composables (runtime-tracing)
    // plus our own sections (androidx.tracing). Inert unless a trace is recording -
    // see tools/perf/trace.sh.
    implementation("androidx.compose.runtime:runtime-tracing")
    implementation("androidx.tracing:tracing-ktx:1.2.0")
    implementation("androidx.tracing:tracing-perfetto:1.0.0")
    implementation("androidx.tracing:tracing-perfetto-binary:1.0.0")

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.phosphor.icon)

    testImplementation(kotlin("test-junit"))
}
