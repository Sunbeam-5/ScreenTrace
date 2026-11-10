plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentrace.app"
    // 需求文档 §6：minSdk 26、target 34+。
    // 若 SDK Manager 里装的是 API 35/36 而没有 34，把下面两行改成已安装的版本即可。
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentrace.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // 必须匹配 Kotlin 1.9.24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 纯 JVM 单测：领域计算器不依赖 Android 框架，可直接 Run
    testImplementation("junit:junit:4.13.2")

    // TODO(§6)：接入数据层时再启用，届时需一并配置 KSP（版本须与 Kotlin 对齐）
    // implementation("androidx.room:room-runtime:2.6.1")
    // implementation("androidx.room:room-ktx:2.6.1")
    // ksp("androidx.room:room-compiler:2.6.1")
    // implementation("androidx.work:work-runtime-ktx:2.9.0")
    // implementation("androidx.datastore:datastore-preferences:1.1.1")
    // implementation("dev.rikka.shizuku:api:13.1.5")
    // implementation("dev.rikka.shizuku:provider:13.1.5")
    // implementation("com.squareup.okhttp3:okhttp:4.12.0") // §5.10 云同步
}
