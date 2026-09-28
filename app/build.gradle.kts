plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Screenshot tests of the UI, rendered on the JVM (see .github/workflows/screenshots.yml).
    id("app.cash.paparazzi")
}

android {
    namespace = "com.steamoslite"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.steamoslite"
        minSdk = 26
        // Must stay 28. The runtime execs proot and the Linux userland from the app's data
        // directory, which Android forbids for apps targeting 29 or later (W^X on app data).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        // AOSP testkey: public and fixed, so every build (local or CI) installs over the last one.
        create("testkey") {
            storeFile = rootProject.file("keystore/testkey.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "testkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("testkey") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("testkey")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // The PulseAudio daemon is exec'd straight out of nativeLibraryDir, so the libraries have to
        // be extracted to disk rather than mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }

    // ResumableDownload logs through android.util.Log, which the JVM tests do not need to see.
    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")

    testImplementation("junit:junit:4.13.2")
}
