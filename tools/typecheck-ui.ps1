# 离线给 Compose UI 做类型检查 —— 不需要 Gradle，也不需要联网。
#
# 为什么需要它：
#   Compose 代码在本机上没法用 `kotlinc` 直接编（缺 Compose 的 classpath），
#   而走一遍完整 Gradle 构建要 2~4 分钟。这个脚本把「Compose 各构件的
#   classes.jar + android.jar + kotlin-stdlib」拼成 classpath，
#   再用缓存里的 kotlin-compose-compiler-plugin 直连 K2JVMCompiler 编译，
#   十几秒就能把整个 app 的 Kotlin 源码类型检查一遍。
#   改 UI 时先跑它，能省掉大量"构建 4 分钟、只为看一行报错"的循环。
#
# 它**不是**完整构建：不处理资源(R)、不 dex、不打包，所以
#   * 只能发现**类型/API 用法**错误（这正是 Compose 最容易踩的坑）
#   * 不能替代 `:app:assembleDebug`
#
# 实测抓到过的真问题：Compose 1.7 没有 `Modifier.scale(scale, transformOrigin)`
# 这个重载（只有 `scale(Float)` 和 `scale(sx, sy)`）。
#
# 用法：  powershell -ExecutionPolicy Bypass -File tools\typecheck-ui.ps1
#
# 注意：本文件必须以**带 BOM 的 UTF-8** 保存，否则 Windows PowerShell 5.1
#       会按 GBK 解码，中文注释会把脚本语法搞坏。

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$cache = 'D:\gradle-home\caches\modules-2\files-2.1'
$work = Join-Path $root 'build\typecheck\cp'

if (-not (Test-Path $cache)) { throw "找不到 Gradle 依赖缓存：$cache" }

function Find-FirstFile([string] $dir, [string] $filter) {
    if (-not (Test-Path $dir)) { return $null }
    return (Get-ChildItem $dir -Recurse -File -Filter $filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Select-Object -First 1 -ExpandProperty FullName)
}

# 缓存里同一个构件可能存着好几个版本（core-ktx 就有 1.6.0 ~ 1.15.0）。
# 随手挑第一个会挑到旧版本，于是出现"类之间对不上"的**假报错** ——
# 实测踩过一次：旧版 androidx.core 缺少 MenuHost.addMenuProvider 的默认实现，
# 于是 MainActivity 被误报成"没有实现抽象成员 addMenuProvider"。
# 所以这里把版本钉成项目 libs.versions.toml 里实际用的那一版；
# 万一钉的版本不存在就自动退回"不钉版本"的老行为，不会把脚本弄挂。
$PinnedVersions = @{
    'androidx.core\core'                       = '1.13.1'
    'androidx.core\core-ktx'                   = '1.13.1'
    'androidx.activity\activity'               = '1.9.2'
    'androidx.activity\activity-compose'       = '1.9.2'
    'androidx.activity\activity-ktx'           = '1.9.2'
    'androidx.lifecycle\lifecycle-runtime'     = '2.8.7'
    'androidx.lifecycle\lifecycle-runtime-ktx' = '2.8.7'
    'androidx.lifecycle\lifecycle-common'      = '2.8.7'
    'androidx.savedstate\savedstate'           = '1.2.1'
    'androidx.annotation\annotation-jvm'       = '1.8.1'
    'androidx.collection\collection-jvm'       = '1.4.4'
    # 协程也要钉：缓存里有 1.5.0 ~ 1.9.0 六个版本，挑到旧版会把
    # `Dispatchers.Default` / `withContext` 这类完全正常的 API 误报成
    # 「internal kotlinx.coroutines API」（实测踩过一次）。
    'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm' = '1.8.1'
    'org.jetbrains.kotlinx\kotlinx-coroutines-android'  = '1.8.1'
}

function Resolve-VersionedDir([string] $cacheRoot, [string] $relative) {
    $base = Join-Path $cacheRoot $relative
    $pin = $PinnedVersions[$relative]
    if ($pin) {
        $versioned = Join-Path $base $pin
        if (Test-Path $versioned) { return $versioned }
    }
    return $base
}

function Find-Jar([string] $dir) {
    $hit = Find-FirstFile $dir '*.jar'
    if (-not $hit) { throw "缓存里找不到 jar：$dir" }
    return $hit
}

# AAR 本质上就是 zip，但要先改成 .zip 才能用 Expand-Archive。
# 解出来的 classes.jar 里才是真正的 class 文件。
function Get-AarClassesJar([string] $dir) {
    $aar = Find-FirstFile $dir '*.aar'
    if (-not $aar) { return $null }
    # 解包目录必须用「构件名-版本」，不能只用最后一段路径：
    # 钉了版本之后最后一段是版本号，多个构件会算出同一个目录名
    # （都变成 "...\1.9.2"），于是 classes.jar 互相覆盖、只有第一个生效 ——
    # 这个 bug 实测表现为 activity-compose 的类整个不见了。
    $artifact = Split-Path (Split-Path $dir -Parent) -Leaf
    $version = Split-Path $dir -Leaf
    $name = "$artifact-$version"
    $dest = Join-Path $work $name
    $jar = Join-Path $dest 'classes.jar'
    if (-not (Test-Path $jar)) {
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $zip = Join-Path $dest 'a.zip'
        Copy-Item $aar $zip -Force
        Expand-Archive -Path $zip -DestinationPath $dest -Force
        Remove-Item $zip -Force
    }
    if (-not (Test-Path $jar)) { throw "AAR 里没有 classes.jar：$dir" }
    return $jar
}

New-Item -ItemType Directory -Force -Path $work | Out-Null

# Compose 与 AndroidX 的 AAR（多平台构件的 artifact 目录带 -android 后缀）
$aarDirs = @(
    'androidx.compose.runtime\runtime-android',
    'androidx.compose.runtime\runtime-saveable-android',
    'androidx.compose.ui\ui-android',
    'androidx.compose.ui\ui-graphics-android',
    'androidx.compose.ui\ui-text-android',
    'androidx.compose.ui\ui-unit-android',
    'androidx.compose.ui\ui-geometry-android',
    'androidx.compose.ui\ui-util-android',
    'androidx.compose.foundation\foundation-android',
    'androidx.compose.foundation\foundation-layout-android',
    'androidx.compose.animation\animation-android',
    'androidx.compose.animation\animation-core-android',
    'androidx.compose.material\material-android',
    'androidx.compose.material\material-ripple-android',
    'androidx.compose.material\material-icons-core-android',
    'androidx.activity\activity',
    'androidx.activity\activity-compose',
    'androidx.activity\activity-ktx',
    'androidx.core\core',
    'androidx.core\core-ktx',
    'androidx.lifecycle\lifecycle-runtime',
    'androidx.lifecycle\lifecycle-runtime-android',
    'androidx.lifecycle\lifecycle-runtime-ktx',
    'androidx.lifecycle\lifecycle-runtime-ktx-android',
    'androidx.savedstate\savedstate-android'
)

$cpEntries = New-Object System.Collections.Generic.List[string]

$androidJar = 'D:\android sdk\platforms\android-34\android.jar'
if (-not (Test-Path $androidJar)) { throw "找不到 android.jar：$androidJar" }
$cpEntries.Add($androidJar)

$cpEntries.Add((Find-Jar "$cache\org.jetbrains.kotlin\kotlin-stdlib\2.0.20"))

# 纯 JAR 形式的依赖（找不到就跳过，别让脚本因为某个可选件挂了）
$jarDirs = @(
    'androidx.annotation\annotation-jvm',
    'androidx.annotation\annotation',
    'androidx.collection\collection-jvm',
    'androidx.collection\collection',
    'androidx.lifecycle\lifecycle-common',
    'androidx.arch.core\core-common',
    'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm',
    'org.jetbrains.kotlinx\kotlinx-coroutines-android'
)
foreach ($rel in $jarDirs) {
    $d = Resolve-VersionedDir $cache $rel
    $j = Find-FirstFile $d '*.jar'
    if ($j) { $cpEntries.Add($j) }
}

$added = 0
foreach ($rel in $aarDirs) {
    $d = Resolve-VersionedDir $cache $rel
    $j = Get-AarClassesJar $d
    if ($j) { $cpEntries.Add($j); $added++ }
}

Write-Host "== 依赖 classpath ==" -ForegroundColor Cyan
Write-Host "   android.jar + stdlib + $added 个 AAR + JAR 若干"

# ---- 编译器自身 ----
$stdlib = Find-Jar "$cache\org.jetbrains.kotlin\kotlin-stdlib\2.0.20"
$compilerCp = (@(
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-compiler-embeddable\2.0.20"),
        $stdlib,
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-script-runtime\2.0.20"),
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-daemon-embeddable\2.0.20"),
        (Find-Jar "$cache\org.jetbrains.intellij.deps\trove4j\1.0.20200330"),
        (Find-Jar "$cache\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm"),
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-reflect\1.9.22"),
        (Find-Jar "$cache\org.jetbrains\annotations")
    ) -join ';')
$composePlugin = Find-Jar "$cache\org.jetbrains.kotlin\kotlin-compose-compiler-plugin-embeddable\2.0.20"

$out = Join-Path $root 'build\typecheck\classes'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

# 整个 app 的 Kotlin 源码一起编译，但**跳过 MainActivity.kt**：
# 它继承 androidx.activity.ComponentActivity，而 ComponentActivity 要靠
# androidx.core 里 MenuHost 的默认方法才能满足接口；在"手工拼出来"的
# classpath 上这一点会被误报成「没有实现抽象成员 addMenuProvider」
# （Gradle 用完整依赖解析时完全正常）。它只有 60 行、极少改动，
# 交给真正的 Gradle 构建去验证即可，不值得为了它让这个快速检查工具失真。
$srcRoot = Join-Path $root 'app\src\main\java'
$src = Get-ChildItem $srcRoot -Recurse -File -Filter '*.kt' |
    Where-Object { $_.Name -ne 'MainActivity.kt' } |
    ForEach-Object { $_.FullName }

Write-Host "== 类型检查 Compose UI 源码（$($src.Count) 个文件，跳过 MainActivity）==" -ForegroundColor Cyan

# 关键：**先把 ErrorActionPreference 放回 Continue**。
# kotlinc 把编译警告（warning）写到 stderr，而本脚本开头是 `$ErrorActionPreference = 'Stop'`，
# 于是 PowerShell 会把一条无害的警告当成**终止错误**，脚本在 java 这一行就中断 ——
# 表现是"类型检查失败"但其实一个 error 都没有（真被这个骗过一次：
# ComplexMath.kt 里 `1.0/0.0` 这种有意为之的 IEEE 运算触发 warning）。
# 所以这里改成：捕获全部输出，只在**真的出现 `error:`** 时才判失败。
$ErrorActionPreference = 'Continue'
$raw = & java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 17 `
    -Xplugin="$composePlugin" `
    -cp ($cpEntries -join ';') -d $out @src 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

$lines = @($raw | ForEach-Object { $_.ToString() })
$errorLines = @($lines | Where-Object { $_ -match ':\s*error:' })
$warnCount = @($lines | Where-Object { $_ -match ':\s*warning:' }).Count

# 警告照常打出来（有用），但不影响结论
if ($warnCount -gt 0) {
    Write-Host "  （$warnCount 条 warning，不影响通过判定）" -ForegroundColor DarkYellow
    $lines | Where-Object { $_ -match ':\s*warning:' } | Select-Object -First 5 |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
}

if ($errorLines.Count -gt 0) {
    $errorLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host "== 类型检查失败（$($errorLines.Count) 个 error）==" -ForegroundColor Red
    exit 1
}
if ($code -ne 0) {
    # 退出码非 0 但没解析出 error 行：把原始输出打出来，别把问题藏起来
    $lines | Select-Object -Last 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
    Write-Host "== 类型检查失败（exit $code，但没有可解析的 error 行）==" -ForegroundColor Red
    exit $code
}
Write-Host "== 类型检查通过（0 error）==" -ForegroundColor Green
exit 0
