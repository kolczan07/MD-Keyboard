plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Wartości z -P (GitHub Actions) lub domyślne (lokalnie)
val propVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0"
val propVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Keystore: lokalnie release.jks w root projektu, w CI ścieżka z env KEYSTORE_FILE
val ksFile = file(System.getenv("KEYSTORE_FILE") ?: "${rootDir}/release.jks")
val hasKeystore = ksFile.exists() && ksFile.length() > 0

android {
    namespace = "pl.mdinstal.ergokeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.mdinstal.ergokeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "mdinstal"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Ten sam podpis co release => debug aktualizuje zainstalowaną aplikację (ustawienia zostają).
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // FileProvider do instalacji pobranego APK (updater)
    implementation("androidx.core:core:1.13.1")
}
