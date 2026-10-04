# 离线跑数学引擎的单元测试 —— 不需要 Gradle，也不需要联网。
#
# 为什么要有这个脚本：
#   引擎（app/src/main/java/.../engine）是纯 Kotlin、零 Android 依赖，
#   所以可以直接用编译器 + JUnitCore 跑。改算法时用它验证只要十几秒，
#   比走一遍完整 Gradle 构建快得多，而且不受沙箱网络限制。
#
# 依赖全部取本机已有的东西（都在 D:\gradle-home 的 Gradle 依赖缓存里）：
#   * kotlin-compiler-embeddable 2.0.20 —— 注意不用 Android Studio 自带的
#     kotlinc.bat，那份缺 org.jetbrains.kotlin.preloading.Preloader，跑不起来。
#   * junit 4.13.2 + hamcrest-core 1.3
#
# 用法：  powershell -ExecutionPolicy Bypass -File tools\run-engine-tests.ps1
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

$junit = Find-Jar "$cache\junit\junit"
$hamcrest = Find-Jar "$cache\org.hamcrest\hamcrest-core"

$engineDir = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\engine'
$testDir = Join-Path $root 'app\src\test\java\com\functiondrawer\watch\engine'
$outDir = Join-Path $root 'build\engine-test-classes'

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host '== 编译引擎 + 测试 ==' -ForegroundColor Cyan
Write-Host "   junit    : $junit"

& java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -jvm-target 17 `
    -cp "$stdlib;$junit;$hamcrest" `
    -d $outDir $engineDir $testDir
if ($LASTEXITCODE -ne 0) { throw "Kotlin 编译失败 (exit $LASTEXITCODE)" }

Write-Host '== 运行 JUnit ==' -ForegroundColor Cyan
& java -cp "$outDir;$stdlib;$junit;$hamcrest" org.junit.runner.JUnitCore `
    com.functiondrawer.watch.engine.ExpressionTest `
    com.functiondrawer.watch.engine.ViewportTest `
    com.functiondrawer.watch.engine.PlotterTest `
    com.functiondrawer.watch.engine.GeometryTest `
    com.functiondrawer.watch.engine.ComplexMathTest `
    com.functiondrawer.watch.engine.CurveTransformTest

if ($LASTEXITCODE -ne 0) { throw "单元测试未通过 (exit $LASTEXITCODE)" }
Write-Host '== 全部通过 ==' -ForegroundColor Green
