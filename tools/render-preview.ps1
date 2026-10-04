# 无头渲染验证：把四种绘图模式的采样结果画成 PNG，人眼核对数学。
#
# 为什么要有这个脚本：
#   引擎是纯 Kotlin、零 Android 依赖，所以可以脱离手表和模拟器直接跑。
#   tools/RenderPreview.kt 用 java.awt 把 Plotter 输出的屏幕像素折线画进
#   600x600 的 PNG，跑完就能用眼睛看正弦波 / 心形线 / 单位圆 / tan 渐近线
#   是不是对的 —— 这是纯数值单测看不到的一层。
#
# 依赖全部取本机已有的东西（都在 D:\gradle-home 的 Gradle 依赖缓存里）：
#   * kotlin-compiler-embeddable 2.0.20 —— 注意不用 Android Studio 自带的
#     kotlinc.bat，那份缺 org.jetbrains.kotlin.preloading.Preloader，跑不起来。
#   * java.awt / javax.imageio 是 JDK 自带的，不需要额外依赖。
#
# 用法：  & 'D:\FunctionDrawer\tools\render-preview.ps1'
#         （先 Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force）
#
# 结果：  D:\FunctionDrawer\build\preview\01-sin.png ... 07-tan.png
#
# 注意：这个文件必须以 **带 BOM 的 UTF-8** 保存。Windows PowerShell 5.1
#       在无 BOM 时会按 GBK 解码，中文注释会把脚本语法搞坏。

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$cache = 'D:\gradle-home\caches\modules-2\files-2.1'

if (-not (Test-Path $cache)) { throw "找不到 Gradle 依赖缓存：$cache" }

# 只认 .jar，别把 .pom / -sources.jar / -javadoc.jar 挑进来
function Find-Jar([string] $dir) {
    $hit = Get-ChildItem $dir -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $hit) { throw "缓存里找不到 jar：$dir" }
    return $hit
}

$kotlin = '2.0.20'
$stdlib = Find-Jar "$cache\org.jetbrains.kotlin\kotlin-stdlib\$kotlin"
# 编译器本体（kotlin-compiler-embeddable）自己还依赖 coroutines / reflect / annotations，
# 少一个都会以 NoClassDefFoundError 的形式在启动阶段就炸掉。
$compilerCp = (@(
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-compiler-embeddable\$kotlin"),
        $stdlib,
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-script-runtime\$kotlin"),
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-daemon-embeddable\$kotlin"),
        (Find-Jar "$cache\org.jetbrains.intellij.deps\trove4j\1.0.20200330"),
        (Find-Jar "$cache\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm"),
        (Find-Jar "$cache\org.jetbrains.kotlin\kotlin-reflect\1.9.22"),
        (Find-Jar "$cache\org.jetbrains\annotations")
    ) -join ';')

$engineDir = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\engine'
$toolSrc = Join-Path $root 'tools\RenderPreview.kt'
$outDir = Join-Path $root 'build\preview-classes'
$pngDir = Join-Path $root 'build\preview'

if (-not (Test-Path $toolSrc)) { throw "找不到渲染工具源码：$toolSrc" }

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $pngDir | Out-Null

Write-Host '== 编译引擎 + 渲染工具 ==' -ForegroundColor Cyan
Write-Host "   输出目录 : $pngDir"

# -Xjvm-default 之类一概不需要：RenderPreview.kt 不依赖任何第三方库。
& java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -jvm-target 17 `
    -cp $stdlib `
    -d $outDir $engineDir $toolSrc
if ($LASTEXITCODE -ne 0) { throw "Kotlin 编译失败 (exit $LASTEXITCODE)" }

Write-Host '== 无头渲染 PNG ==' -ForegroundColor Cyan
# -Djava.awt.headless=true 双保险（源码里也 setProperty 了）。
# 主类名是文件名 + Kt：RenderPreview.kt -> RenderPreviewKt（没有 package 声明）。
# 注意：这个 JVM 参数必须加引号。PowerShell 5.1 把裸写的 -Djava.awt.headless=true
# 交给 java 时会拆错参数，java 报 "找不到或无法加载主类 .awt.headless=true"。
& java '-Djava.awt.headless=true' -cp "$outDir;$stdlib" RenderPreviewKt $pngDir
$code = $LASTEXITCODE

$pngs = Get-ChildItem $pngDir -Filter '*.png' -ErrorAction SilentlyContinue
Write-Host "== 产出 $($pngs.Count) 张 PNG ==" -ForegroundColor Green
$pngs | Sort-Object Name | ForEach-Object { Write-Host ("   {0,-22} {1,8} bytes" -f $_.Name, $_.Length) }

if ($code -ne 0) { throw "渲染过程中有用例返回错误 (exit $code)" }
Write-Host '== 全部用例渲染完成，无错误 ==' -ForegroundColor Green
