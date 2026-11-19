plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.justprintit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.justprintit.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build is a newer version and installs as an update.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // A fixed key, so each new APK installs over the previous one and keeps your counters and settings.
        // It's in the repo for convenience; to use a private key instead, set the KEYSTORE_* environment variables.
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "justprintit.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "justprintit"
            keyAlias = System.getenv("KEY_ALIAS") ?: "justprintit"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "justprintit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
