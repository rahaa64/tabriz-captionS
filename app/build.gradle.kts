plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modelBaseUrl: String = (project.findProperty("modelBaseUrl") as String?)
    ?: "https://github.com/rahaa64/tabriz-captions/releases/download/models-v1/"
val ksFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotEmpty() } ?: file("release.jks").takeIf { it.exists() }?.path
fun envOr(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotEmpty() } ?: d

android {
    namespace = "com.turkishfacts.captions"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.turkishfacts.captions"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "MODEL_BASE_URL", "\"$modelBaseUrl\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = file(ksFile)
            storePassword = envOr("KEYSTORE_PASSWORD", "tabrizcaptions")
            keyAlias = envOr("KEY_ALIAS", "tabriz")
            keyPassword = envOr("KEY_PASSWORD", "tabrizcaptions")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
