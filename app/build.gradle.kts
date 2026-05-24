import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing reads from keystore.properties at the repo root (gitignored).
// See keystore.properties.example for the schema and RELEASING.md for the full
// release flow. When the file is absent, `release` builds fall back to the
// debug signing config so local debug installs of the release variant still
// work — but the resulting AAB cannot be uploaded to Play.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.blindfoldchess.app"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.blindfoldchess.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Stockfish JNI is built for 64-bit ARM only — covers ~all phones from
            // the last several years and halves APK size. Add armeabi-v7a / x86_64
            // here later if a tester actually needs them.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Stockfish NNUE network files (~108MB) and the Vosk speech model (~68MB) live in a
    // separate install-time asset pack so the base AAB stays under Play Store's 200MB
    // limit. Play merges the pack into the app's AssetManager namespace at install
    // time; the existing EngineAssets and VoskRecognizer code uses them transparently.
    assetPacks += setOf(":engineassets")

    // assetPacks only delivers contents into AAB builds (where Play handles the merge).
    // For APK builds — most importantly the debug variant that Android Studio "Run"
    // installs — the pack's contents are NOT auto-merged into the base APK, so the
    // app would crash at startup looking for vosk-model-.../uuid. Re-expose the pack's
    // assets directory on the debug source set so installDebug ships a usable APK.
    // Release sideloads (rare) still need bundletool install-apks from the AAB.
    sourceSets {
        getByName("debug") {
            assets.srcDirs("../engineassets/src/main/assets")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // .nnue files are pre-quantized neural-net weights — recompressing them
        // gains nothing and just makes asset extraction slower.
        noCompress += "nnue"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media)
    implementation(libs.vosk.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.chesslib)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
