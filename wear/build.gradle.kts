import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.tremolo.wear"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // Must match the phone app: the Wearable Data Layer pairs apps by application id.
        applicationId = "app.tremolo"
        // Wear OS 3, the first version shared by Pixel, Samsung, Mobvoi, Fossil and others.
        minSdk = 30
        targetSdk = 36
        // Same application id as the phone app, so the store needs a different version
        // code: the watch build is always the phone's plus a million.
        versionCode = 1_000_001
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The upload key is described in keystore.properties in the project root, which is never
    // checked in. Without that file, release builds fall back to the debug key so they can
    // still be installed and tested; the Play Store rejects those.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("upload") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreFile.exists()) "upload" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.datasource)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
