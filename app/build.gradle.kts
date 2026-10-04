// 注意：在 Kotlin DSL 里 `java` 会解析成 Gradle 的 JavaPluginExtension，
// 而不是 java 包，所以 Properties 必须显式 import。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.functiondrawer.watch"

    // 只能用本机已装的 android-34（SDK 管理器不能联网补装 35/36）。
    compileSdk = 34

    defaultConfig {
        applicationId = "com.functiondrawer.watch"
        // 目标设备是 Android 11 的原屏手表，但用户要求**支持范围放大到 Android 7.0**
        // （API 24）—— 国产原屏表里跑 7.x/8.x 的机型不少。
        //
        // 降下来会牵出"只有高版本才有"的 API，逐个都做了处理（改这一行时一起查过一遍）：
        //   * `View.setSystemGestureExclusionRects`（API 29）→ GraphScreen 里判版本，
        //     低版本不做（这个能力 7.x 上没有等价替代），设置页那一行也会拦下并提示；
        //   * `MotionEvent.AXIS_SCROLL`（API 26）→ MainActivity 里 26 以下不列这个轴；
        //   * 沉浸式全屏本来就用的是 AndroidX 的 `WindowCompat`/`WindowInsetsControllerCompat`
        //     （兼容封装，API 30 以下自动退化成 `systemUiVisibility`），**没有**直接调
        //     平台那个 API 30 的 `setDecorFitsSystemWindows`；
        //   * `LocalConfiguration.isScreenRound`（API 23）、`Bitmap.compress`、
        //     `getExternalFilesDir`、`Uri.encode/decode`、`String.format` 等都在 24 以下就有。
        // lint 的 `abortOnError = false` 保持不变（低版本分支只靠上面的显式守卫，不靠 lint）。
        // 注意：改动后**尚未**跑 Gradle 构建验证（构建要联网、且会写工作区外目录）。
        minSdk = 24
        targetSdk = 34
        // ---- 版本号（用户定的规则，2026-10-04）----
        // 用户原话："每次我说要上传你再更新 versionCode，不然中间太多小版本了，版本号跳跃很快。"
        // 所以**不要**做"每次构建自增"，也不要在小改动里动这两个数：
        //   * 平时改代码 → 保持原样，反复编译出来的包版本号都一样（方便对照、也方便覆盖安装）；
        //   * 用户说"要上传了" → 才把 versionName 提一档（1.0 → 1.1 …）
        //     并把 versionCode **加一**（第三方商店要求每次上传的 versionCode 必须比上一版大）。
        // 现在的首发版本：versionName 1.0 / versionCode 1。
        versionCode = 1
        versionName = "1.0"
    }

    // release 签名：密钥路径与口令放在项目根目录的 keystore.properties 里
    // （该文件已被 .gitignore 忽略，不会进版本库）。
    // 文件不存在时也能正常构建，只是产出的 release 包未签名。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 对 Compose 是安全的（不需要额外 keep 规则），
            // 能把 release 包从 ~19MB 压到 1.58MB —— 手表存储紧张，这很重要。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // 调试包不混淆，方便反复装到表上试。
            isMinifyEnabled = false
            // **用和 release 同一套签名**：debug 默认签名和 release 不一样，
            // 直接 `adb install -r` 会因为签名不匹配失败，只能先卸载 ——
            // 那会把表上已经存好的函数和设置一起清掉。
            // 签成同一个 key 之后 debug 包可以直接覆盖安装，数据保留。
            // （debug 包不需要 R8，构建要快得多，所以日常迭代只装 debug。）
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    // Compose Material 2 + 手工实现的 MD3 令牌层（见 ui/WatchColors.kt）。
    // 不用 material3：本机缓存里没有该构件，而且 Maven Central 不可达。
    implementation(libs.androidx.material)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // 数学引擎是纯 Kotlin、零 Android 依赖，所以可以直接在 JVM 上跑单元测试。
    testImplementation(libs.junit)
}
