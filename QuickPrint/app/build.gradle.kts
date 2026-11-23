plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.quickprint"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.quickprint"
        minSdk = 29
        targetSdk = 34
        // CI passes its run number so every build is newer and installs over the previous one.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 300 + build
        versionName = "3.0.$build"
    }

    // Fixed signing key so every new build installs over the old one (keeps your data).
    // The key is not stored in the repository: CI writes it from the QUICKPRINT_KEYSTORE_B64 secret.
    // Without it the build falls back to a throwaway debug key (installs fine, but can't update an older install).
    val qpKey = file("quickprint.keystore")
    signingConfigs {
        if (qpKey.exists()) {
            create("qp") {
                storeFile = qpKey
                storePassword = System.getenv("QUICKPRINT_KEY_PASSWORD") ?: "quickprint"
                keyAlias = "quickprint"
                keyPassword = System.getenv("QUICKPRINT_KEY_PASSWORD") ?: "quickprint"
            }
        }
    }
    buildTypes {
        val signing = if (qpKey.exists()) signingConfigs.getByName("qp") else signingConfigs.getByName("debug")
        getByName("debug") {
            signingConfig = signing
        }
        getByName("release") {
            signingConfig = signing
            isMinifyEnabled = false
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
