plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vakarux.instadownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oranges.instadownload"
        minSdk = 24  // Android 7.0 (Nougat)
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 14
        versionName = "2.6.0-stories"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/**/LICENSE.txt"
            excludes += "/DebugProbesKt.bin"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/kotlin/**.kotlin_builtins"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.all { it.enabled = false }   // disable unit tests
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
    implementation(libs.androidx.animation)
    implementation(libs.androidx.foundation)

    // HTTP client for network requests
    implementation(libs.okhttp)
    debugImplementation(libs.logging.interceptor)

    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

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