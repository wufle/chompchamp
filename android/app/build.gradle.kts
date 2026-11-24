plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key comes from CI secrets (see README "Signing"). Every build must use the
// same key or Android refuses to install an update over the existing app.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "au.chompchamp"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.chompchamp"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.8.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "chompchamp"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the real key, fall back to the throwaway debug key so the APK still
            // installs (it just can't update over a build signed with a different key).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // Sign debug builds with the same key when available, so either can update the other.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 11.7.0+ is required for pmtiles:// sources.
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
