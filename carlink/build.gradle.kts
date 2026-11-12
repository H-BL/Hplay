plugins {
    id("com.android.library")
}

android {
    namespace = "com.hpplay.carlink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Compiled at 24 so it links into the API24 app; libovmsink.so itself is built at API 27,
        // so the whole feature is runtime-gated to Android 8.1+ (PlatformCapabilities.supportsCarLink).
        minSdk = 24
        // 车机 MTK8666 为 arm64；libovmsink.so 只带 arm64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":common"))
    implementation(project(":shared"))

    // protobuf-lite runtime for the pre-generated com.ucar.databus.proto.UCarProto
    // (generated offline with protoc --java_out=lite from src/main/proto/ucar.proto).
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")

    testImplementation(libs.junit)
}
