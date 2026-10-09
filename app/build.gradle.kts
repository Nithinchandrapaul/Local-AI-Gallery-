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
        versionCode = 23
        versionName = "3.3.6"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/leo_release.keystore")
            storePassword = "leo_ai_gallery_keystore_pass"
            keyAlias = "leo_key"
            keyPassword = "leo_ai_gallery_keystore_pass"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite", "bin")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/{LGPL2.1,AL2.0}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Automated Unit Testing & Benchmarking Suite
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
}
