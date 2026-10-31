plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val githubUser = providers.gradleProperty("lsposed_github_username").getOrElse("your_username")
val moduleName = providers.gradleProperty("module_name").getOrElse("imeauto")
val fullPkg = "io.github.$githubUser.$moduleName"

android {
    namespace = fullPkg
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = fullPkg
        minSdk = 26
        targetSdk = 35
        versionCode = 10100
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "MODULE_PACKAGE_NAME", "\"$fullPkg\"")
    }

    val signKeystorePath = System.getenv("KEYSTORE_PATH") ?: "keystore.jks"
    val signStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: "marukon_imeauto_2026"
    val signKeyAlias = System.getenv("KEY_ALIAS") ?: "imeauto"
    val signKeyPassword = System.getenv("KEY_PASSWORD") ?: "marukon_imeauto_2026"

    signingConfigs {
        create("release") {
            storeFile = file(signKeystorePath)
            storePassword = signStorePassword
            keyAlias = signKeyAlias
            keyPassword = signKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // LSPosed Modern API 102
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // AndroidX & Jetpack Compose (Material 3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Image loading
    implementation(libs.coil.compose)

    // JSON serialization
    implementation(libs.kotlinx.serialization.json)
}
