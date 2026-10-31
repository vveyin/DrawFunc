# 离线跑数学键盘模型的单元测试 —— 不需要 Gradle，也不需要联网。
#
# 为什么要有这个脚本：
#   键盘布局 + editText 的光标语义都在 ui/keyboard/KeyboardModel.kt 里，
#   那个文件是纯 Kotlin、零 Android/Compose 依赖（只 import engine.PlotMode），
#   所以可以直接用编译器 + JUnitCore 在 JVM 上验证。
#   改键盘布局时用它验证只要十几秒，比装一次 APK 到表上试快得多。
#
# 依赖全部取本机已有的东西（都在 D:\gradle-home 的 Gradle 依赖缓存里）：
#   * kotlin-compiler-embeddable 2.0.20 —— 注意不用 Android Studio 自带的
#     kotlinc.bat，那份缺 org.jetbrains.kotlin.preloading.Preloader，跑不起来。
#   * junit 4.13.2 + hamcrest-core 1.3
#
# 用法：  powershell -ExecutionPolicy Bypass -File tools\run-keyboard-tests.ps1
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

# 键盘模型依赖 engine.PlotMode，所以 engine 目录必须一起编进去；
# 但只编 KeyboardModel.kt 这一个 UI 文件 —— MathKeyboard.kt 里有 Compose 代码，
# 离线环境编译不了（也不该在这里编）。
$engineDir = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\engine'
$modelFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\keyboard\KeyboardModel.kt'
# 内置英文字母表键盘的模型：同样"纯 Kotlin、只依赖 engine.PlotMode"（它连 import 都没有），
# 所以和数学键盘的模型一起编进来，布局/大小写/插入文本那几件事也就能离线测。
$alphabetModelFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\keyboard\AlphabetModel.kt'
$testDir = Join-Path $root 'app\src\test\java\com\functiondrawer\watch\keyboard'
$outDir = Join-Path $root 'build\keyboard-test-classes'

if (-not (Test-Path $modelFile)) { throw "找不到键盘模型：$modelFile" }
if (-not (Test-Path $alphabetModelFile)) { throw "找不到字母表模型：$alphabetModelFile" }

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host '== 编译键盘模型 + 测试 ==' -ForegroundColor Cyan
Write-Host "   model    : $modelFile"
Write-Host "   alphabet : $alphabetModelFile"
Write-Host "   junit    : $junit"

& java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -jvm-target 17 `
    -cp "$stdlib;$junit;$hamcrest" `
    -d $outDir $engineDir $modelFile $alphabetModelFile $testDir
if ($LASTEXITCODE -ne 0) { throw "Kotlin 编译失败 (exit $LASTEXITCODE)" }

Write-Host '== 运行 JUnit ==' -ForegroundColor Cyan
& java -cp "$outDir;$stdlib;$junit;$hamcrest" org.junit.runner.JUnitCore `
    com.functiondrawer.watch.keyboard.KeyboardModelTest `
    com.functiondrawer.watch.keyboard.AlphabetModelTest

if ($LASTEXITCODE -ne 0) { throw "单元测试未通过 (exit $LASTEXITCODE)" }
Write-Host '== 全部通过 ==' -ForegroundColor Green
