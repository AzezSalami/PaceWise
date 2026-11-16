plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.pacewise.wear"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the phone app (required by the Wearable Data Layer).
        applicationId = "app.pacewise"
        minSdk = 30
        targetSdk = 34
        // CI passes these so every release installs over the previous one.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }
    signingConfigs {
        // Used when the release keystore is supplied through environment variables (see README).
        val store = System.getenv("KEYSTORE_FILE")
        if (!store.isNullOrBlank()) create("release") {
            storeFile = file(store)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            // Falls back to the debug key so the APK is always installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // Code shrinking is off until a minified build has been verified on real devices.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    implementation("androidx.health:health-services-client:1.0.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    // Health Services returns ListenableFuture; the type has to be on the compile classpath.
    implementation("com.google.guava:guava:31.1-android")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
