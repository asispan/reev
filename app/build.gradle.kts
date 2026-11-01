plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asis.reev"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.asis.reev"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Upload key comes from env vars (never committed). Without them, release falls back to the debug key.
    val ksPath = System.getenv("REEV_KEYSTORE")
    val ksPass = System.getenv("REEV_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksPath != null && ksPass != null) create("upload") {
            storeFile = file(ksPath); storePassword = ksPass
            keyAlias = System.getenv("REEV_KEY_ALIAS") ?: "reev-upload"; keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
