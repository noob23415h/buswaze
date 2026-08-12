plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.buswaze.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.buswaze.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.5"

        ndk {
            // Phones only use ARM — dropping x86/x86_64 roughly halves the APK size
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.cardview:cardview:1.0.0")
}
