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
    signingConfigs {
        create("qp") {
            storeFile = file("quickprint.keystore")
            storePassword = "quickprint"
            keyAlias = "quickprint"
            keyPassword = "quickprint"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("qp")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("qp")
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
