// FunctionDrawer —— Android 11「原屏」手表函数图像绘制器
//
// 仓库配置说明（都是这台机器上实测出来的结论，改动前请先看 README 的「构建」一节）：
//   * repo.maven.apache.org 在这台机器上 TLS 会被重置（curl exit 35），
//     但 repo1.maven.org（同一个 Maven Central）和阿里云镜像是通的，
//     所以镜像放最前面，并且用 repo1 取代 mavenCentral()。
//   * D:\gradle-home 的依赖缓存里没有 metadata-* 目录，因此 --offline 不可用，
//     必须能联网解析依赖元数据。
//   * AGP 8.5.2 / Kotlin 2.0.20 插件自身的依赖（guava、asm、protobuf、sdklib…）
//     本机缓存里是缺的，必须从仓库下载。
//   * SDK 管理器不能联网补装，只能用已装的 platforms/android-34 → compileSdk 34。

pluginManagement {
    repositories {
        // 国内镜像优先：快，而且实测可通
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        // 直接用 repo1：mavenCentral() 指向的 repo.maven.apache.org 在本机不通
        maven("https://repo1.maven.org/maven2")
    }
}

rootProject.name = "FunctionDrawer"
include(":app")