plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vortexx.booster"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.vortexx.booster"
        minSdk = 29          // WIFI_MODE_FULL_LOW_LATENCY نیاز به اندروید ۱۰+ دارد
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("ci") {
            storeFile = file(System.getenv("VORTEX_KS") ?: "ci.keystore")
            storePassword = System.getenv("VORTEX_KS_PASS") ?: "vortexx123"
            keyAlias = "vortex"
            keyPassword = System.getenv("VORTEX_KS_PASS") ?: "vortexx123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // در CI با keystore امضا می‌شود؛ بیلد محلی با کلید debug
            signingConfig = if (System.getenv("VORTEX_KS") != null)
                signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // انیمیشن: Rive (State Machine) + Lottie 120FPS به عنوان مسیر پشتیبان
    implementation("app.rive:rive-android:6.+")
    implementation("com.airbnb.android:lottie-compose:6.4.1")
}
