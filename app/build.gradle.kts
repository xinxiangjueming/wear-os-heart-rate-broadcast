plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x 起 Compose 编译器随 Kotlin 版本走插件，不再用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.heartrate.broadcast"
    compileSdk = 35  // Wear Compose Material3 1.5.6 的 AAR 要求 minCompileSdk 35

    defaultConfig {
        applicationId = "com.heartrate.broadcast"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }

    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    // Compose BOM（与 sportlinkwear 已验证组合一致：Compose 1.8.x）
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    // Wear OS Compose Material 3 —— 官方 EdgeButton / AppScaffold / ScreenScaffold 所在。
    // 原自绘月牙贴边按钮（BottomEdgeButton.kt）依赖的 M2 栈整体退场。
    implementation("androidx.wear.compose:compose-material3:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.compose:compose-ui-tooling:1.5.6")

    // Compose（版本统一由 BOM 提供）
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Health Services
    implementation("androidx.health:health-services-client:1.0.0-rc01")

    // Wear（AmbientLifecycleObserver 微光态）
    implementation("androidx.wear:wear:1.3.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
}
