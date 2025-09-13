plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.vakarux.instadownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vakarux.instadownload"
        minSdk = 24  // Android 7.0 (Nougat)
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 2  // Updated version for new features
        versionName = "1.2.0"  // Updated version name

        ndk {
            // Use both if you build for emulators and modern devices
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.all { it.enabled = false }   // disable unit tests
    }
}
chaquopy {
    defaultConfig {
        // Optional: pick a Python version. If you omit, Chaquopy chooses a default.
        version = "3.11"
        //buildPython("C:/Python311/python.exe")
        // Install Instaloader into the APK at build time
        pip {
            install("instaloader==4.13.2") // current stable on PyPI
        }
    }
}
dependencies {
    // Core Android libraries
    implementation(libs.androidx.core.ktx.v1120)
    implementation(libs.androidx.lifecycle.runtime.ktx.v270)
    implementation(libs.androidx.activity.compose.v182)

    // Compose BOM - using latest version
    implementation(platform(libs.androidx.compose.bom.v20240401))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)

    // Additional Compose libraries for enhanced features
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.foundation)

    // HTTP client for network requests
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor) // For debugging network calls

    // JSON parsing
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // ViewModel and State Management
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // DataStore for settings persistence (modern SharedPreferences alternative)
    implementation(libs.androidx.datastore.preferences)

    // Navigation if you plan to add more screens later
    implementation(libs.androidx.navigation.compose)

    // Splash Screen API for modern splash screens
    implementation(libs.androidx.core.splashscreen)

    // Work Manager for background tasks (useful for queued downloads)
    implementation(libs.androidx.work.runtime.ktx)

    // Testing dependencies
    //testImplementation(libs.junit)
    //testImplementation(libs.kotlinx.coroutines.test)
    //testImplementation(libs.androidx.core.testing)

    //androidTestImplementation(libs.androidx.junit)
    //androidTestImplementation(libs.androidx.espresso.core.v351)
    //androidTestImplementation(platform(libs.androidx.compose.bom.v20250900))
    //androidTestImplementation(libs.ui.test.junit4)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}