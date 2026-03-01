plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.grantbor.gpx2ms"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        resValues = true
    }

    defaultConfig {
        applicationId = "io.github.grantbor.gpx2ms"
        minSdk = 24
        targetSdk = 36
        versionCode = 11

        ndk {
            abiFilters.add("arm64-v8a")
        }

        resValue("string", "app_version", versionName ?: "1.01")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}