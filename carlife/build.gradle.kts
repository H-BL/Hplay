plugins {
    id("com.android.library")
}

android {
    namespace = "com.hpplay.carlife"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // CarLifeWire 用 android.util.Log 打日志，JVM 单测里静默掉
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":common"))

    // common 里 androidx 是 implementation，不透传，这里显式声明宿主 Activity 所需
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
