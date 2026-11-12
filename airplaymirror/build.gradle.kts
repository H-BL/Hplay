plugins {
    id("com.android.library")
}

android {
    namespace = "com.hpplay.mirror"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28

        // R8 混淆规则随模块一起带给宿主(单 App 打包时生效)
        consumerProguardFiles("consumer-rules.pro")

        // 与 Hplay 保持一致：只编 arm64/armv7/x86_64；主力车机为 arm64
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // ffmpeg/openssl 由 CMake ExternalProject 交叉编译，使用 NDK 自带 clang
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 原生构建耗时较长，避免不必要的清理
    buildFeatures {
        buildConfig = true
        // oboe 以 prefab 形式提供 CMake 包 (find_package(oboe))
        prefab = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media)
    implementation(libs.oboe)
}
