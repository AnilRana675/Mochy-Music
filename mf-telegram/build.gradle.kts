// our telegram channel playback engine: tdlib client wrapper, channel index, match engine, and
// a loopback stream server. android library on purpose (tdlib is jni native), because built-in
// source modules under modules/ must stay plain kotlin and depend only on :modules:api
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.ksp)
}

import java.util.Properties

android {
    namespace = "com.mochyfork.mftelegram"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        // api keys stay in local.properties (user's own from my.telegram.org, clause 2.1). a build
        // without keys keeps the feature off and never fails to compile
        val local = Properties()
        if (rootProject.file("local.properties").exists()) {
            local.load(rootProject.file("local.properties").inputStream())
        }
        buildConfigField("int", "MF_TELEGRAM_API_ID", (local.getProperty("TELEGRAM_API_ID") ?: "0"))
        buildConfigField("String", "MF_TELEGRAM_API_HASH", "\"${local.getProperty("TELEGRAM_API_HASH") ?: ""}\"")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":modules:api"))
    // tdlib core, prebuilt aar (tdlib-android project, exact version pinned, verify at bump)
    implementation("io.github.tdlib-android:core:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    coreLibraryDesugaring(libs.desugaring)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
