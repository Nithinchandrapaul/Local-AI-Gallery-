plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sunny.localphotoai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sunny.localphotoai"
        minSdk = 31
        targetSdk = 36
        versionCode = 14
        versionName = "3.0.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/{LGPL2.1,AL2.0}"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.google.mediapipe:tasks-retrieval:latest.release")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
