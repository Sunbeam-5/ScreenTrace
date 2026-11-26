pluginManagement {
    repositories {
        // 国内镜像优先（CI 环境跳过镜像直连官方源，避免海外 Runner 被限流/污染插件解析）
        if (!System.getenv("CI").equals("true", ignoreCase = true)) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!System.getenv("CI").equals("true", ignoreCase = true)) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ScreenTrace"
include(":app")
