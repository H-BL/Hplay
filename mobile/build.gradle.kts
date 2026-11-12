import com.android.build.api.variant.FilterConfiguration
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
}


// Release signing material. keystore.properties is machine-local and gitignored;
// CI supplies the same values through ANDROID_KEY* environment variables.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val signingValue: (String, String) -> String? = { property, environment ->
    (keystoreProperties.getProperty(property) ?: providers.environmentVariable(environment).orNull)
        ?.takeIf { it.isNotBlank() }
}
val releaseStorePath: String? = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
val hasReleaseSigning: Boolean = releaseStorePath != null

android {
    namespace = "com.hpplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hpplay"
        // Ordinary third-party Android head units: support Android 7.0 (API 24)+.
        // CarLink (libovmsink.so is built at API 27) and AirPlay screen mirror are runtime-gated to 8.1+.
        minSdk = 24
        targetSdk = 37
        versionCode = providers.gradleProperty("hplay.versionCode").get().toInt()
        versionName = providers.gradleProperty("hplay.versionName").get()

    }

    signingConfigs {
        create("release") {
            // Real material comes from keystore.properties or ANDROID_KEY* env
            // vars. The placeholder is only reached when neither exists, and in
            // that case this config is never attached to a build type.
            storeFile = rootProject.file(releaseStorePath ?: "missing-release-keystore.jks")
            storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // R8: shrink + optimize + obfuscate, and drop unused resources.
            // Rules live in proguard-rules.pro (JNI entry points, Parcelable
            // creators, enum value tables).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign only when a real keystore is supplied (CI injects it via
            // ANDROID_KEYSTORE_PATH). Without one, build an unsigned APK
            // rather than failing on the placeholder path.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Package name: Hplay-<version>[-debug][-<abi>].apk, e.g. Hplay-1.3.3.apk.
val apkVersionName: String = providers.gradleProperty("hplay.versionName").get()
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val name = buildString {
                append("Hplay-").append(apkVersionName)
                if (variant.buildType == "debug") append("-debug")
                if (abi != null) append('-').append(abi)
            }
            output.outputFileName.set("$name.apk")
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":carlife"))
    implementation(project(":carlink"))
    implementation(project(":shared"))
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    constraints {
        implementation(libs.androidx.collection) {
            because("Without the Compose BOM, androidx.collection falls back to 1.4.2; keep the 1.5.0 this app already ships.")
        }
        implementation(libs.androidx.emoji2) {
            because("Without the Compose BOM, androidx.emoji2 falls back to 1.3.0; keep the 1.4.0 this app already ships.")
        }
    }
}
