# verify-apk.ps1 —— 离线校验 APK 的关键元数据（不需要设备，也不需要 Gradle）
#
# 用途：
#   构建完之后、装机之前跑一遍，确认产物真的是给「圆屏 Android 手表」用的，
#   而不是被误配成了 Wear OS 或者别的包名 / 最低版本。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1
#   powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1 -ApkPath D:\some\other.apk
#
# 不带 -ApkPath 时默认校验 **release** 产物（app\build\outputs\apk\release\app-release.apk）；
# 没构建过 release 才退回 debug 产物。
#
# 退出码：
#   0 = 全部检查通过
#   1 = 至少有一项 FAIL
#   2 = 前置条件不满足（APK 不存在 / 工具链缺失）
#
# 注意：这个文件必须以 **带 BOM 的 UTF-8** 保存。Windows PowerShell 5.1
#       在无 BOM 时会按 GBK 解码，中文会破坏脚本语法。

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $ApkPath
)

# ---- 让原生命令（aapt2 / apksigner / zipalign）输出的 UTF-8 中文能被正确解码 ----
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$ErrorActionPreference = 'Continue'   # 不要用 Stop：原生命令写 stderr 会变成终止性错误

# ------------------------------------------------------------------ 常量
$scriptRoot  = $PSScriptRoot
$projectRoot = Split-Path -Parent $scriptRoot

$SdkRoot        = 'D:\android sdk'
$BuildTools     = Join-Path $SdkRoot 'build-tools\34.0.0'
$Aapt2          = Join-Path $BuildTools 'aapt2.exe'
$Apksigner      = Join-Path $BuildTools 'apksigner.bat'
$Zipalign       = Join-Path $BuildTools 'zipalign.exe'
$JdkHome        = 'D:\jdk17'

$ExpectedPackage  = 'com.functiondrawer.watch'
$ExpectedMinSdk   = '24'      # Android 7.0（用户要求老表 / 老手机也能装）
$ExpectedTargetSdk = '34'
$ExpectedLabel    = 'DrawFunc'
$ExpectedActivity = 'com.functiondrawer.watch.MainActivity'
$ForbiddenFeature = 'android.hardware.type.watch'   # Wear OS 专用，本项目刻意不声明

$DefaultApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $DefaultApk -PathType Leaf)) {
    # 没构建过 release（比如没配 keystore.properties）时退回 debug 产物
    $DefaultApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not $ApkPath) { $ApkPath = $DefaultApk }
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)

# ------------------------------------------------------------------ 排版小工具
function Get-DisplayWidth {
    param([string] $Text)
    if ($null -eq $Text) { return 0 }
    $w = 0
    foreach ($ch in $Text.ToCharArray()) {
        $c = [int][char]$ch
        if (($c -ge 0x1100 -and $c -le 0x115F) -or
            ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
            ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
            ($c -ge 0xF900 -and $c -le 0xFAFF) -or
            ($c -ge 0xFE30 -and $c -le 0xFE6F) -or
            ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { $w += 2 } else { $w += 1 }
    }
    return $w
}

function Format-Cell {
    param([string] $Text, [int] $Width)
    if ($null -eq $Text) { $Text = '' }
    $pad = $Width - (Get-DisplayWidth $Text)
    # 内容超宽时不能直接返回原文，否则会和右边一列串在一起；至少补一个空格分隔。
    if ($pad -le 0) { return $Text + ' ' }
    return $Text + (' ' * $pad)
}

# 断言表格的列宽（按显示宽度算，中文按 2 列）
$ColName   = 22
$ColExpect = 40
$ColActual = 40

function Write-Rule {
    param([string] $Title = '')
    $line = '  ' + ('-' * 78)
    if ($Title) {
        Write-Output ("  ---- " + $Title + " " + ('-' * [Math]::Max(0, 78 - 6 - (Get-DisplayWidth $Title))))
    } else {
        Write-Output $line
    }
}

# 一次检查的结果
$script:Checks = New-Object System.Collections.ArrayList

function Add-Check {
    param(
        [string] $Name,
        [string] $Expect,
        [string] $Actual,
        [bool]   $Pass,
        [string] $Note = ''
    )
    $null = $script:Checks.Add([pscustomobject]@{
        Name   = $Name
        Expect = $Expect
        Actual = $Actual
        Pass   = $Pass
        Note   = $Note
    })
}

# 从 aapt2 badging 输出里取第一条匹配的捕获组
function Get-FirstMatch {
    param([string[]] $Lines, [string] $Pattern)
    foreach ($ln in $Lines) {
        $m = [regex]::Match($ln, $Pattern)
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return $null
}

# ------------------------------------------------------------------ 头
Write-Output ''
Write-Rule 'APK 产物校验（离线，不依赖设备与 Gradle）'
Write-Output ("  项目根目录 : " + $projectRoot)
Write-Output ("  待校验 APK : " + $ApkPath)
Write-Output ("  构建工具   : " + $BuildTools)
Write-Output ''

# ------------------------------------------------------------------ 前置：APK 是否存在
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    Write-Output '  [前置条件不满足] 找不到 APK 文件。'
    Write-Output ''
    Write-Output ("    期望路径 : " + $ApkPath)
    Write-Output  '    说明     : 这个 APK 目前还不存在，通常是因为构建还没跑完（或还没开始）。'
    Write-Output  '               本脚本只做产物校验，不会自己触发构建。'
    Write-Output ''
    Write-Output  '    怎么办（按顺序试）：'
    Write-Output  '      1) 等正在进行的 Gradle 构建结束，再重新运行本脚本；'
    Write-Output  '      2) 需要自己构建时，在项目根目录执行：'
    Write-Output  '           .\gradlew.bat :app:assembleDebug'
    Write-Output  '         （注意：如果另有一个 Gradle 进程正在跑，请等它结束，避免同时占用构建目录）'
    Write-Output  '      3) 如果 APK 在别的位置，用参数指定：'
    Write-Output  '           powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1 -ApkPath <你的apk路径>'
    Write-Output ''
    Write-Output  '  退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}

$apkItem = Get-Item -LiteralPath $ApkPath
$sizeMb  = [Math]::Round($apkItem.Length / 1MB, 2)
$apkSha  = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash

# ------------------------------------------------------------------ 前置：aapt2
if (-not (Test-Path -LiteralPath $Aapt2 -PathType Leaf)) {
    Write-Output ("  [前置条件不满足] 找不到 aapt2.exe： " + $Aapt2)
    Write-Output  '    Android SDK build-tools 34.0.0 没有装全，无法解析 APK 元数据。'
    Write-Output  '    退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}

# ------------------------------------------------------------------ 解析 badging
Write-Rule 'aapt2 dump badging'
$badgingLines = @()
$badgingRaw = & $Aapt2 'dump' 'badging' $ApkPath 2>&1
$badgingExit = $LASTEXITCODE
foreach ($item in $badgingRaw) { $badgingLines += [string]$item }

if ($badgingExit -ne 0 -or $badgingLines.Count -eq 0) {
    Write-Output ("  [FAIL] aapt2 dump badging 执行失败（退出码 " + $badgingExit + '）。')
    foreach ($ln in $badgingLines) { Write-Output ('    | ' + $ln) }
    Write-Output ''
    Write-Output '  退出码 1（校验失败）'
    Write-Output ''
    exit 1
}
Write-Output ("  解析成功，共 " + $badgingLines.Count + " 行输出。")
Write-Output ''

function Get-BadgingLine {
    param([string[]] $Lines, [string] $Pattern)
    foreach ($ln in $Lines) { if ($ln -match $Pattern) { return $ln.Trim() } }
    return $null
}

# ---- 1) 包名
$pkg = Get-FirstMatch $badgingLines "^package:\s+name='([^']+)'"
Add-Check 'package 名' $ExpectedPackage $(if ($pkg) { $pkg } else { '(缺失)' }) ($pkg -eq $ExpectedPackage)

# ---- 2) minSdk
$minSdk = Get-FirstMatch $badgingLines "sdkVersion:'([^']+)'"
Add-Check 'minSdkVersion' ("minSdkVersion:'" + $ExpectedMinSdk + "'") $(if ($minSdk) { "minSdkVersion:'$minSdk'" } else { '(缺失)' }) ($minSdk -eq $ExpectedMinSdk) 'minSdk = 24 即 Android 7.0：圆屏手表跑的是完整 Android（不是 Wear OS），往下兼容到 7.0'

# ---- 3) targetSdk
$tgtSdk = Get-FirstMatch $badgingLines "targetSdkVersion:'([^']+)'"
Add-Check 'targetSdkVersion' ("targetSdkVersion:'" + $ExpectedTargetSdk + "'") $(if ($tgtSdk) { "targetSdkVersion:'$tgtSdk'" } else { '(缺失)' }) ($tgtSdk -eq $ExpectedTargetSdk)

# ---- 4) application-label（附加项，非必需）
$labelLine = Get-BadgingLine $badgingLines "^application-label"
$label  = Get-FirstMatch $badgingLines "^application-label:'([^']*)'"
$labelZh = Get-FirstMatch $badgingLines "^application-label-zh[^:]*:'([^']*)'"
$labelShown = if ($label) { $label } elseif ($labelZh) { $labelZh } else { '(缺失)' }
$labelPass = ($label -eq $ExpectedLabel) -or ($labelZh -eq $ExpectedLabel)
$labelNote = ''
if (-not $labelLine) { $labelNote = '完全没有 application-label' }
elseif (-not $labelPass) { $labelNote = '有 label 但不是期望的中文名' }
Add-Check 'application-label' $ExpectedLabel $labelShown $labelPass $labelNote

# ---- 5) launchable-activity
$act = Get-FirstMatch $badgingLines "^launchable-activity:\s+name='([^']+)'"
$actPass = ($act -eq $ExpectedActivity)
$actNote = ''
if (-not $act) { $actNote = '没有可启动 Activity，手表上点不开' }
Add-Check 'launchable-activity' $ExpectedActivity $(if ($act) { $act } else { '(缺失)' }) $actPass $actNote

# ---- 6) application-icon
$iconLines = @($badgingLines | Where-Object { $_ -match '^application-icon' })
$icon = Get-FirstMatch $badgingLines "^application-icon-[^:]*:'([^']+)'"
if (-not $icon) { $icon = Get-FirstMatch $badgingLines "^application-icon:'([^']+)'" }
$iconPass = ($iconLines.Count -gt 0) -and [bool]$icon
$iconNote = if ($iconPass) { '图标路径：' + $icon } else { '没有任何 application-icon 行' }
Add-Check 'application-icon' '至少 1 条图标资源' $(if ($iconLines.Count -gt 0) { '共 ' + $iconLines.Count + ' 条' } else { '(缺失)' }) $iconPass $iconNote

# ---- 7) 不应该出现 Wear OS 标记
$watchLine = Get-BadgingLine $badgingLines "uses-feature[^:]*:\s*name='$([regex]::Escape($ForbiddenFeature))'"
$watchPass = -not [bool]$watchLine
Add-Check 'Wear OS 标记' '不得出现' $(if ($watchLine) { '出现了！' } else { '未出现，正确' }) $watchPass ("不得出现 uses-feature: " + $ForbiddenFeature + ' —— 本项目刻意不声明，声明了手表的安装器可能拒装')

# ------------------------------------------------------------------ 打印断言行
Write-Rule '关键项断言'
Write-Output ('  ' + (Format-Cell '检查项' $ColName) + (Format-Cell '期望' $ColExpect) + (Format-Cell '实际' $ColActual) + '结论')
Write-Output ('  ' + ('-' * $ColName) + ('-' * $ColExpect) + ('-' * $ColActual) + ('-' * 6))
foreach ($c in $script:Checks) {
    $verdict = if ($c.Pass) { 'PASS' } else { 'FAIL' }
    Write-Output ('  ' + (Format-Cell $c.Name $ColName) + (Format-Cell $c.Expect $ColExpect) + (Format-Cell $c.Actual $ColActual) + $verdict)
    if ($c.Note) { Write-Output ('  ' + (' ' * $ColName) + '└─ ' + $c.Note) }
}
Write-Output ''

# 顺便把关键原始行打出来，便于人工核对
Write-Rule 'aapt2 原始关键行（同类最多列 3 行，其余折叠）'
$rawPatterns = @(
    @{ P = '^package:';             Label = 'package' },
    @{ P = '^sdkVersion:';          Label = 'sdkVersion' },
    @{ P = '^targetSdkVersion:';    Label = 'targetSdkVersion' },
    @{ P = '^application-label:';   Label = 'application-label（默认）' },
    @{ P = '^application-label-zh'; Label = 'application-label-zh*' },
    @{ P = '^application-icon';     Label = 'application-icon*' },
    @{ P = '^launchable-activity:'; Label = 'launchable-activity' },
    @{ P = '^uses-feature';         Label = 'uses-feature' },
    @{ P = '^native-code';          Label = 'native-code' }
)
foreach ($rp in $rawPatterns) {
    $matched = @($badgingLines | Where-Object { $_ -match $rp.P })
    if ($matched.Count -eq 0) {
        Write-Output ('  [无 ' + $rp.Label + ' 行]')
        continue
    }
    $showN = [Math]::Min(3, $matched.Count)
    for ($i = 0; $i -lt $showN; $i++) { Write-Output ('  ' + $matched[$i].Trim()) }
    if ($matched.Count -gt $showN) {
        Write-Output ('  ...（' + $rp.Label + ' 另有 ' + ($matched.Count - $showN) + ' 行，已折叠）')
    }
}
Write-Output ''

# ------------------------------------------------------------------ 文件大小
Write-Rule 'APK 文件'
Write-Output ('  ' + (Format-Cell '文件大小' 26) + (Format-Cell ($sizeMb.ToString('N2') + ' MB') 34) + (Format-Cell ($apkItem.Length.ToString('N0') + ' 字节') 40) + 'INFO')
Write-Output ('  ' + (Format-Cell '最后写入时间' 26) + (Format-Cell ($apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) 34) + (Format-Cell 'SHA256' 40) + 'INFO')
Write-Output ('  ' + (' ' * 26) + $apkSha)
Write-Output ''

# ------------------------------------------------------------------ 签名
Write-Rule '签名校验（apksigner）'
$signOk = $true
if (Test-Path -LiteralPath $Apksigner -PathType Leaf) {
    if (Test-Path -LiteralPath $JdkHome) {
        $env:JAVA_HOME = $JdkHome
        if ($env:PATH -notlike "*$JdkHome\bin*") { $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH }
    }
    $signOut = & $Apksigner 'verify' '--print-certs' $ApkPath 2>&1
    $signExit = $LASTEXITCODE
    foreach ($ln in $signOut) { Write-Output ('  | ' + [string]$ln) }
    if ($signExit -eq 0) {
        Write-Output ''
        Write-Output '  结论 : PASS —— 签名有效（debug 签名即可，侧载安装不要求正式签名）'
    } else {
        $signOk = $false
        Write-Output ''
        Write-Output ("  结论 : FAIL —— apksigner 校验失败（退出码 " + $signExit + '），APK 可能未签名或被改坏')
    }

    # ---- 签名**档位**（v1 / v2 / v3）单独看一遍 ----
    #
    # ⚠️ 为什么要显式加 `--min-sdk-version 21`：`apksigner verify` **默认只检"本包
    #    minSdkVersion 需要的那几档"**。本包 minSdk = 24（Android 7 已支持 v2），
    #    所以**默认那条命令即使 APK 里有完好的 v1（JAR）签名，也会打印
    #    `Verified using v1 scheme (JAR signing): false`** —— 当初"以为 v1 没签上"
    #    的误会就是这么来的（AGP 的 `enableV1Signing = true` 在 8.5.2 上确实被忽略，
    #    但"看默认 verify 输出"并不是判断 v1 在不在的正确方法）。
    #    显式给 21 之后，v1 那行才是真的"在不在"。
    Write-Output ''
    Write-Output '  ---- 签名档位（显式按 min-sdk 21 检，才看得见 v1）----'
    $schemeOut = & $Apksigner 'verify' '--verbose' '--min-sdk-version' '21' $ApkPath 2>&1
    $v1 = $null; $v2 = $null; $v3 = $null
    foreach ($ln in $schemeOut) {
        $s = [string]$ln
        if ($s -match 'Verified using v1 scheme \(JAR signing\):\s*(true|false)') { $v1 = ($Matches[1] -eq 'true') }
        if ($s -match 'Verified using v2 scheme \(APK Signature Scheme v2\):\s*(true|false)') { $v2 = ($Matches[1] -eq 'true') }
        if ($s -match 'Verified using v3 scheme \(APK Signature Scheme v3\):\s*(true|false)') { $v3 = ($Matches[1] -eq 'true') }
    }
    Write-Output ('  v1（JAR 签名，老设备用）: ' + $(if ($v1) { 'true' } else { 'false' }))
    Write-Output ('  v2（Android 7+）        : ' + $(if ($v2) { 'true' } else { 'false' }))
    Write-Output ('  v3（Android 9+）        : ' + $(if ($v3) { 'true' } else { 'false' }))
    if (-not $v1) {
        Write-Output '  INFO : 缺 v1 —— 只认 JAR 签名的老设备（Android 6/7 那批安装器）会装不上。'
        Write-Output '         release 包补三签： powershell -ExecutionPolicy Bypass -File tools\sign-release.ps1'
        Write-Output '         （v1 不是本脚本的判定项：debug 包本来就只有 v2，侧载够用）'
    }
} else {
    Write-Output ("  [跳过] 找不到 apksigner.bat： " + $Apksigner)
    Write-Output  '         无法校验签名；这不影响其余检查项。'
}
Write-Output ''

# ------------------------------------------------------------------ 对齐
Write-Rule 'zipalign 4 字节对齐校验'
$alignOk = $true
if (Test-Path -LiteralPath $Zipalign -PathType Leaf) {
    $alignOut = & $Zipalign '-c' '-v' '4' $ApkPath 2>&1
    $alignExit = $LASTEXITCODE
    $alignLines = @()
    foreach ($ln in $alignOut) { $alignLines += [string]$ln }
    $show = $alignLines | Select-Object -Last 12
    foreach ($ln in $show) { Write-Output ('  | ' + $ln) }
    if ($alignLines.Count -gt $show.Count) {
        Write-Output ('  | ...（前面还有 ' + ($alignLines.Count - $show.Count) + ' 行，已省略）')
    }
    Write-Output ''
    if ($alignExit -eq 0) {
        Write-Output '  结论 : PASS —— 已按 4 字节对齐（zipalign 保证 mmap 读取效率）'
    } else {
        $alignOk = $false
        Write-Output ("  结论 : FAIL —— 未对齐（退出码 " + $alignExit + '）')
    }
} else {
    Write-Output ("  [跳过] 找不到 zipalign.exe： " + $Zipalign)
    Write-Output  '         无法校验对齐；这不影响其余检查项。'
}
Write-Output ''

# ------------------------------------------------------------------ 汇总
$failed  = @($script:Checks | Where-Object { -not $_.Pass })
$passedN = @($script:Checks | Where-Object { $_.Pass }).Count
$totalN  = $script:Checks.Count

Write-Rule '汇总'
Write-Output ("  关键项   : " + $passedN + ' / ' + $totalN + ' 通过')
Write-Output ("  签名     : " + $(if ($signOk) { 'OK' } else { 'FAIL' }))
Write-Output ("  对齐     : " + $(if ($alignOk) { 'OK' } else { 'FAIL' }))
Write-Output ("  文件大小 : " + $sizeMb.ToString('N2') + ' MB')
Write-Output ''

if ($failed.Count -gt 0 -or -not $signOk -or -not $alignOk) {
    Write-Output '  结果 : FAIL'
    foreach ($f in $failed) { Write-Output ('    ! ' + $f.Name + ' —— 期望 ' + $f.Expect + '，实际 ' + $f.Actual) }
    if (-not $signOk) { Write-Output  '    ! 签名校验未通过' }
    if (-not $alignOk) { Write-Output '    ! zipalign 未通过' }
    Write-Output ''
    Write-Output '  提示 : 包名 / minSdk / targetSdk / label / 图标 都来自 app 模块的构建配置，'
    Write-Output '         改完请重新构建再跑本脚本。'
    Write-Output ''
    Write-Output '  退出码 1（校验失败）'
    Write-Output ''
    exit 1
}

Write-Output '  结果 : PASS —— APK 满足圆屏 Android 11 手表的全部关键项。'
Write-Output ''
Write-Output  '  下一步 : 用 tools\install-to-watch.ps1 装到手表上。'
Write-Output ''
Write-Output  '  退出码 0（全部通过）'
Write-Output ''
exit 0
