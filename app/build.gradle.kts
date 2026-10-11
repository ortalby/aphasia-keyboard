plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ortalby.aphasiakeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ortalby.aphasiakeyboard"
        minSdk = 31
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Galaxy phones are arm64; leaving out the x86_64 emulator library halves the download.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Compress the native library inside the APK (smaller download).
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // A fixed key, so every new build installs over the previous one
    // without uninstalling (and without losing the copied model file).
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/aphasia.keystore")
            storePassword = "aphasia-keyboard"
            keyAlias = "aphasia"
            keyPassword = "aphasia-keyboard"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
