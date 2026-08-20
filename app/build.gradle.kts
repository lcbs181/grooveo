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
        versionCode = 9
        versionName = "0.3.2"

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.coil.compose)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.phosphor.icon)
}
