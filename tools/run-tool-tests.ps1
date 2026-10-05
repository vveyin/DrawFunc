# 离线跑「功能界面」工具状态机的单元测试 —— 不需要 Gradle，也不需要联网。
#
# 为什么要有这个脚本：
#   ui/FunctionTools.kt 是**纯 Kotlin、零 Android/Compose 依赖**（只 import engine.GPoint），
#   里面装的是"五栏工具目录 + 每步要选什么 + 什么时候算完成"这些规则。
#   这些规则最容易在改界面时被悄悄改坏（比如把"面积要点回起点"写成"点够三个点就完"），
#   用这个脚本十几秒就能验一遍，比装一次 APK 到表上试快得多。
#
# 依赖全部取本机已有的东西（都在 D:\gradle-home 的 Gradle 依赖缓存里），
# 与 run-keyboard-tests.ps1 / run-engine-tests.ps1 同一套做法。
#
# 用法：  powershell -ExecutionPolicy Bypass -File tools\run-tool-tests.ps1
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

# 工具状态机依赖 engine.GPoint，所以 engine 目录要一起编；
# 只编 FunctionTools.kt 这一个 UI 文件 —— 其它 UI 文件都带 Compose，离线编不了。
$engineDir = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\engine'
$toolsFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\FunctionTools.kt'
# 保存图像的文件名规则（#8）：同样是纯字符串逻辑，没有 Compose 依赖，一起编进来测。
$saveNameFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\SaveName.kt'
# 复平面的"数值运算"层（#1）：把引擎的复数求值翻译成界面要用的东西（点/标签/错误），
# 同样没有 Compose 依赖 —— 它是这一批里最"新"的语义，最需要离线钉住。
$complexOpsFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\ComplexOps.kt'
# 「函数文件」(.fdf) 的格式：整个应用状态 ⇄ 紧凑文本。
# 它同样是**纯 Kotlin**（只 import engine.*），而且是最该离线钉住的一份 ——
# 转义/版本/坏行这三条规则一旦写错，用户存下去的文件就再也读不回来。
$fileFormatFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\FunctionFileFormat.kt'
$fileExportFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\FileExport.kt'
# 「取坐标」的文本替换（x(A) / y(A) → 点坐标数值）：同样是纯 Kotlin、只 import engine.GPoint。
# 它改的是**用户写的表达式文本**，多改一个字符就会画出另一条曲线，所以必须离线钉住。
$pointCoordsFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\PointCoordinates.kt'
# 绘图尺度（网格按 dp 走 + 刻度数字贴边）：同样是纯 Kotlin（只 import kotlin.math），
# 用这个脚本把'格距取整'和'边缘吸附'这两段算术钉住 —— 它们决定了手机上网格多密、
# 刻度数字会不会被屏幕边裁掉，装到设备上试的代价太大。新加的测试类见 PlotMetricsTest。
$plotMetricsFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\PlotMetrics.kt'
# 几何对象的**改名规则**（字符集 / 唯一性 / 引用同步）：纯 Kotlin，规则写在 GraphObjects.kt 末尾。
# 它决定'改了名字之后用户写的 x(A) / a*x 还对得上吗'，必须离线钉住。
$graphObjectsFile = Join-Path $root 'app\src\main\java\com\functiondrawer\watch\ui\GraphObjects.kt'
$testDir = Join-Path $root 'app\src\test\java\com\functiondrawer\watch\tools'
$outDir = Join-Path $root 'build\tool-test-classes'

if (-not (Test-Path $toolsFile)) { throw "找不到工具模型：$toolsFile" }
if (-not (Test-Path $saveNameFile)) { throw "找不到文件名规则：$saveNameFile" }
if (-not (Test-Path $complexOpsFile)) { throw "找不到数值运算层：$complexOpsFile" }
if (-not (Test-Path $fileFormatFile)) { throw "找不到函数文件格式：$fileFormatFile" }
if (-not (Test-Path $pointCoordsFile)) { throw "找不到取坐标替换逻辑：$pointCoordsFile" }
if (-not (Test-Path $plotMetricsFile)) { throw "找不到绘图尺度换算：$plotMetricsFile" }
if (-not (Test-Path $graphObjectsFile)) { throw "找不到几何对象模型：$graphObjectsFile" }
if (-not (Test-Path $fileExportFile)) { throw "找不到导出纯规则：$fileExportFile" }

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host '== 编译功能工具模型 + 测 ==' -ForegroundColor Cyan
Write-Host "   tools : $toolsFile"
Write-Host "   save  : $saveNameFile"
Write-Host "   cplx  : $complexOpsFile"
Write-Host "   fdf   : $fileFormatFile"
Write-Host "   coord : $pointCoordsFile"
Write-Host "   plot  : $plotMetricsFile"
Write-Host "   obj   : $graphObjectsFile"
Write-Host "   junit : $junit"

& java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -jvm-target 17 `
    -cp "$stdlib;$junit;$hamcrest" `
    -d $outDir $engineDir $toolsFile $saveNameFile $complexOpsFile $fileFormatFile $pointCoordsFile $plotMetricsFile $graphObjectsFile $fileExportFile $testDir
if ($LASTEXITCODE -ne 0) { throw "Kotlin 编译失败 (exit $LASTEXITCODE)" }

Write-Host '== 运行 JUnit ==' -ForegroundColor Cyan
& java -cp "$outDir;$stdlib;$junit;$hamcrest" org.junit.runner.JUnitCore `
    com.functiondrawer.watch.tools.ToolFlowTest `
    com.functiondrawer.watch.tools.SaveNameTest `
    com.functiondrawer.watch.tools.ComplexOpsTest `
    com.functiondrawer.watch.tools.FunctionFileFormatTest `
    com.functiondrawer.watch.tools.PointCoordinatesTest `
    com.functiondrawer.watch.tools.PlotMetricsTest `
    com.functiondrawer.watch.tools.FileExportTest

if ($LASTEXITCODE -ne 0) { throw "单元测试未通过 (exit $LASTEXITCODE)" }
Write-Host '== 全部通过 ==' -ForegroundColor Green
