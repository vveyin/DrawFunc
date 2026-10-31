// 注意：在 Kotlin DSL 里 `java` 会解析成 Gradle 的 JavaPluginExtension，
// 而不是 java 包，所以 Properties 必须显式 import。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 1.2.71：APK 产物名跟着应用名走（用户要求"APK 名字改成 f(x)"）。
// Gradle 的 `base.archivesName` 会传给 AGP 的打包任务：产物从 `app-release.apk`
// 变成 `f(x)-release.apk`（`app-` 前缀是 AGP 的模块名，不是这一项能改的）。
base.archivesName.set("f(x)")

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
        //   * 用户说"要上传了"（或点名要改号）→ 才提一档并把 versionCode **加一**
        //     （第三方商店要求每次上传的 versionCode 必须比上一版大）。
        // 历史：1.0 / 1 → **1.0fix / 2**（用户 2026-10-05 点名："把安装包版本号升到 1.0fix，
        // versioncode 变成 2"）→ **1.1 / 3**（用户 2026-10-05 点名："签名完之后再安装、
        // 升版本号和 versioncode"，选项里选的是 `1.1 / 3`）→ **1.3 / 167**
        // （用户 2026-10-31 点名："把应用版本改成1.3，apk发给我"）→ **1.3.1 / 168**
        // （用户 2026-10-31 点名："接下来正常升版本，下一个版本是1.3.1"）→ **1.3.2 / 169**
        // （本轮修 bug + 新增两项功能，接着 1.3.1 正常升一档）→ **1.3.3 / 170**
        // （修"半径输入只能输入一位数"：文本从局部 remember 提升到状态层）→ **1.3.4 / 171**
        // （描点新增原点 / 坐标轴吸附：原点 > 坐标轴 > 函数曲线）→ **1.3.5 / 172**
        // （同上 1.3.5：描点吸附曲线交点（含自绘曲线），交点空心并跟随两条曲线移动）→ **1.3.5 / 172**
        // （修"自绘两直线取交点描不到"：线线求交不再受函数对上限/采样结果影响）→ **1.3.6 / 173**
        // （修"曲线上的点拖动时会跳到别的曲线"：拖动期间锁死在开始吸上的那条对象上）→ **1.3.7 / 174**
        // （修"重开软件后，描点派生的直线不跟着点动"：读盘时按锚点还原两点定线的 recipe）→ **1.3.8 / 175**
        // （修"曲线也没做"：文件与本地存档开始存 recipe（`R` 段），点/线/度量/曲线全都恢复跟随）→ **1.3.9 / 176**
        // （吸附关系存盘（`O|P` 第 10 格）+ 撤销补两处：曲线构造关系、滑动条拖值）→ **1.3.10 / 177**
        // （截图界面（长按提示条 2 秒）+ 教程两步 + 长按 sin 时禁点击 sin）→ **1.3.11 / 178**
        // （截图改浮窗（预览可平移缩放、圆屏空白垫片）+ 另存 Download/DrawFunc + 按键同款）→ **1.3.12 / 179**
        // （浮窗模糊背景、去阴影；预览 cover 裁切让网格/坐标轴画到图片边缘之外）→ **1.3.13 / 180**
        // （截图改回整屏路由；背景按模糊开关取模糊图/纯色；预览按自身像素重新采样，网格坐标轴铺满图片）→ **1.3.14 / 181**
        // （截图背景强制不透明；预览改 3 倍超采画布 + 窗口裁切，网格/坐标轴一定延伸到图片外）→ **1.3.15 / 182**
        // （截图界面改**静态截图**（录当前画布一帧，形状按圆屏/方屏），去掉勾选项与预览手势）→ **1.3.16 / 183**
        // （截图框自带不透明背景（存储的 PNG 不再是透明底）；补回坐标轴/坐标数字/网格三个勾选项）→ **1.3.17 / 184**
        // （截图图片去掉灰色描边；长按提示条加"1 秒后蓄力：阴影加深 + 中央蓝色进度条，松手反向收回"；
        //   修教程第 9/10 步把截图整屏路由当成"乱跑"导致失败）→ **1.3.18 / 185**
        // （截图预览视口的 unitsPerPixel 方向修正：图片和图像界面显示**同一片世界范围**）→ **1.3.19 / 186**
        versionCode = 186
        versionName = "1.3.19"
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

                // ---- 签名方案：v1 + v2 + v3 都要（用户报"安卓 7 装不了"）----
                // 现象：`apksigner verify` 显示 v1=false / v2=true / v3=false —— 只有 v2。
                // 为什么只在 Android 7 上出问题：Android 7.0（API 24）才开始支持 v2 方案，
                // 而**老的安装器/国产 ROM 的包管理器很多会先去验 JAR 签名（v1）**，
                // v1 缺失时就报"解析软件包时出现问题 / 应用未安装"。
                // v3 是 Android 9 起的分级签名（key rotation 用），加上它不花钱、兼容性只增不减。
                // 结论：三个方案同时开启 —— 覆盖 API 24 及以上的所有设备与各家商店的要求。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
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
    // 毛玻璃统一用 BlurView（Dimezis），而不是 Compose 的 `Modifier.blur`。
    //
    // 原因：`Modifier.blur` 背后是 RenderEffect，**API 31（Android 12）起才有**，
    // 用户的 Android 11 手表上它是空操作。BlurView 2.x 在 12 以下会退到
    // RenderScript 的 ScriptIntrinsicBlur（API 17 起就有），所以全版本都能糊。
    //
    // 版本钉在 2.0.6，不用 3.x：
    //   * 3.x 的 `BlurTarget` 用 RenderNode 抓内容，`canUseHardwareRendering`
    //     在 SDK < 31 时是 false，那条路等于是给 12+ 写的；2.x 的
    //     `setupWith(ViewGroup)` 是"把根 View 画进一张 Bitmap 再糊"，
    //     走的是最普通的 draw，不挑 API。
    //   * 2.x 支持 `setOverlayColor/setBlurRadius/setBlurEnabled`，
    //     正好够本项目用（不需要 Compose 侧的 BlurTarget）。
    //
    // 抓帧对象是 `LocalView.current`（即 ComposeView 本身），也就是**整个界面**：
    // 函数图、抽屉都画在里面，BlurView 抓到的是"当前屏幕上真实的样子"。
    implementation(libs.blurview)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // 数学引擎是纯 Kotlin、零 Android 依赖，所以可以直接在 JVM 上跑单元测试。
    testImplementation(libs.junit)
}
