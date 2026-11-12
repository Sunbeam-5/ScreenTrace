// 顶层构建文件：插件版本集中在这里，模块里不要重复写版本。
// 版本组合（相互兼容、已避开实验性 API）：
//   Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / Compose Compiler 1.5.14 / Compose BOM 2024.06.00
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP 版本必须与 Kotlin 1.9.24 对齐（README 预警的首次 Sync 最常见失败点）
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // 批 8：备份 JSON 序列化（§6 依赖白名单「JSON 序列化库」）
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
