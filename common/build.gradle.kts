plugins {
    id("com.android.library")
}

android {
    namespace = "com.hpplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Android 7.0+ (API 24) for ordinary head units. `:airplaymirror` stays at 28 (see its
        // build file); the manifest below overrides that library check with tools:overrideLibrary.
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":shared"))
    implementation(project(":airplaymirror"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
    constraints {
        implementation(libs.androidx.collection) {
            because("Without the Compose BOM, androidx.collection falls back to 1.4.2; keep the 1.5.0 this app already ships.")
        }
        implementation(libs.androidx.emoji2) {
            because("Without the Compose BOM, androidx.emoji2 falls back to 1.3.0; keep the 1.4.0 this app already ships.")
        }
    }
}
