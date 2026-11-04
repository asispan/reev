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

    // Upload key comes from env vars (never committed). Release builds fail closed without them:
    // there is no fallback to the public debug key.
    val ksPath = System.getenv("REEV_KEYSTORE")
    val ksPass = System.getenv("REEV_KEYSTORE_PASSWORD")
    val keyPass = System.getenv("REEV_KEY_PASSWORD") ?: ksPass
    val wantsRelease = gradle.startParameter.taskNames.any { Regex("(assemble|bundle|package|install|publish)\\w*Release").containsMatchIn(it) }
    if (wantsRelease && (ksPath == null || ksPass == null)) {
        throw GradleException("Release builds need REEV_KEYSTORE and REEV_KEYSTORE_PASSWORD (REEV_KEY_PASSWORD if different). Refusing to sign with the debug key.")
    }
    signingConfigs {
        if (ksPath != null && ksPass != null) create("upload") {
            storeFile = file(ksPath); storePassword = ksPass
            keyAlias = System.getenv("REEV_KEY_ALIAS") ?: "reev-upload"; keyPassword = keyPass
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
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
    // Pinned and checksum-verified by tools/fetch_deps.sh.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
