plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.peter.minimal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.peter.minimal"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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

    buildFeatures {
        viewBinding = true
    }

    // The Vosk model files (am/final.mdl, graph/*.fst, etc.) are large
    // binary files with no recognized extension. Without this, Android's
    // build tools may try to compress them into the APK, which has known
    // reliability issues for large files and also makes them slower/more
    // memory-hungry to read back out at runtime. Storing them uncompressed
    // avoids that.
    androidResources {
        noCompress += listOf("mdl", "fst", "int", "conf", "mat", "ie", "dubm", "stats")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // registerForActivityResult / ActivityResultContracts used in
    // MainActivity.kt — explicit rather than relying on it being pulled in
    // transitively by appcompat, which isn't guaranteed across versions.
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Offline speech recognition, used here for continuous local wake-word
    // style listening (transcribe audio, check if "peter" appears in text).
    // NOTE: verify this artifact coordinate/version is still current at
    // build time — I cannot check JitPack/GitHub live from here.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
