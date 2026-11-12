plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
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

    testOptions {
        unitTests {
            // Room / Robolectric 管道测试需要读取 manifest 与资源
            isIncludeAndroidResources = true
        }
    }
}

// P2-1（6a 评审）：导出 Room schema JSON。注意与「备份 schemaVersion」是两套版本号——
// 前者是数据库结构版本（本文件 version = 1），后者是备份文件格式版本（§5.10 C07，首版 2）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // 批 6a 评审 P1-2：管道测试（Room + Robolectric 跑真 SQLite 语义，含 SQLite 变量上限）
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ---- 批 6a：数据层（KSP 已配置，版本 1.9.24-1.0.20 与 Kotlin 对齐）----
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- 批 6b：Shizuku 主通道（§2.1）+ provider 注册见 AndroidManifest（S05）----
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 批 8：备份 JSON（schemaVersion=4，§5.10）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // CSV 多文件导出走 SAF 目录（七张表各一个文件）
    implementation("androidx.documentfile:documentfile:1.0.1")

    // TODO(批 8)：云同步
    // implementation("com.squareup.okhttp3:okhttp:4.12.0") // §5.10 云同步
}
