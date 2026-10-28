plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.olenva"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.olenva"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
    }

    // OpenCV's native lib is 24 MB (arm64) / 59 MB (x86_64): ship one APK per ABI instead of a fat one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // The release key lives outside the repo; path and password come from the environment.
    // Without them (e.g. a fresh clone) release builds fall back to the debug key so they still install.
    val keystore = System.getenv("OLENVA_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("OLENVA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("OLENVA_KEY_ALIAS") ?: "olenva"
            keyPassword = System.getenv("OLENVA_KEYSTORE_PASSWORD")   // PKCS12: key and store share one password
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else {
                logger.warn("OLENVA_KEYSTORE not set: release APK is signed with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }
    buildFeatures { compose = true }
    androidResources { localeFilters += listOf("en", "zh-rTW") }
    // DebugProbesKt.bin only serves the coroutines debugger.
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.opencv)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
}
