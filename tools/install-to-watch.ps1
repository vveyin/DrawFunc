# install-to-watch.ps1 —— 把手表 App 装到真机（圆屏 Android 11 手表）上的一条命令流程
#
# 用途：
#   构建完之后：查设备 -> 打印设备信息（重点是分辨率和 density）-> 安装 -> 启动 ->（可选）抓崩溃日志。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1
#   powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1 -Uninstall
#   powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1 -Serial 0123456789ABCDEF
#   powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1 -Logcat
#
# 退出码：
#   0 = 安装并启动成功
#   1 = 安装或启动失败
#   2 = 前置条件不满足（adb 缺失 / 没有设备 / 设备未授权 / 多设备未指定 -Serial / APK 不存在）
#
# 注意：这个文件必须以 **带 BOM 的 UTF-8** 保存。Windows PowerShell 5.1
#       在无 BOM 时会按 GBK 解码，中文会破坏脚本语法。

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $ApkPath,

    [string] $Serial,

    [switch] $Uninstall,

    [switch] $Logcat
)

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$ErrorActionPreference = 'Continue'

# ------------------------------------------------------------------ 常量
$scriptRoot  = $PSScriptRoot
$projectRoot = Split-Path -Parent $scriptRoot

$SdkRoot  = 'D:\android sdk'
$Adb      = Join-Path $SdkRoot 'platform-tools\adb.exe'
$Package  = 'com.functiondrawer.watch'
$Activity = 'com.functiondrawer.watch/.MainActivity'

$DefaultApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not $ApkPath) { $ApkPath = $DefaultApk }
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)

function Write-Rule {
    param([string] $Title = '')
    if ($Title) {
        $w = 0
        foreach ($ch in $Title.ToCharArray()) {
            $c = [int][char]$ch
            if (($c -ge 0x2E80 -and $c -le 0xA4CF) -or ($c -ge 0xFF00 -and $c -le 0xFF60)) { $w += 2 } else { $w += 1 }
        }
        Write-Output ('  ---- ' + $Title + ' ' + ('-' * [Math]::Max(0, 74 - $w)))
    } else {
        Write-Output ('  ' + ('-' * 78))
    }
}

# 统一的 adb 调用：自动带上 -s <序列号>，返回输出行数组
function Invoke-Adb {
    param([string[]] $AdbArgs)
    $full = @()
    if ($Serial) { $full += @('-s', $Serial) }
    $full += $AdbArgs
    $out = & $Adb @full 2>&1
    $script:LastAdbExit = $LASTEXITCODE
    $lines = @()
    foreach ($item in $out) { $lines += [string]$item }
    return $lines
}

# ------------------------------------------------------------------ 头
Write-Output ''
Write-Rule '安装到手表（圆屏 Android 11，非 Wear OS）'
Write-Output ("  项目根目录 : " + $projectRoot)
Write-Output ("  目标 APK   : " + $ApkPath)
Write-Output ("  包名 / 入口: " + $Package + '  ->  ' + $Activity)
Write-Output ''

# ------------------------------------------------------------------ 1) adb 是否存在
Write-Rule '1/6 检查 adb'
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    Write-Output ("  [前置条件不满足] 找不到 adb.exe： " + $Adb)
    Write-Output ''
    Write-Output '    怎么办：'
    Write-Output ('      * 确认 Android SDK 装在 "' + $SdkRoot + '"，且里面有 platform-tools\adb.exe；')
    Write-Output  '      * 没装的话在 Android Studio 的 SDK Manager 里勾选 "Android SDK Platform-Tools"；'
    Write-Output  '      * 如果 SDK 在别的路径，改本脚本顶部的 $SdkRoot 变量。'
    Write-Output ''
    Write-Output '  退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}
Write-Output ("  OK : " + $Adb)
Write-Output ''

# 先把 adb server 以「独立、不继承管道」的方式拉起来。
# 原因：adb 会 fork 一个常驻的 server 进程，它会继承调用方 stdout 的写端句柄。
# 如果本脚本自身的输出正被管道 / 重定向捕获（CI、外层脚本、Tee 等），
# 那个常驻 server 会让管道的写端一直不关闭，于是调用方永远等不到 EOF，
# 表现就是「脚本卡住不动」——而其实脚本早就跑完并 exit 了。
# 提前 detach 地起好 server，后面 adb devices 就不会再 fork，也就没这个问题。
try {
    Start-Process -FilePath $Adb -ArgumentList 'start-server' -WindowStyle Hidden -ErrorAction Stop | Out-Null
    Start-Sleep -Milliseconds 500
} catch {
    # 起不来也不致命：正常调用 adb 时会自己再试一次
}
Write-Output ''

# ------------------------------------------------------------------ 2) 设备列表
Write-Rule '2/6 查找设备'
$devOut = Invoke-Adb @('devices', '-l')
Write-Output '  adb devices -l 输出：'
foreach ($ln in $devOut) { Write-Output ('    | ' + $ln) }
Write-Output ''

$ready = New-Object System.Collections.ArrayList   # 状态 = device
$other = New-Object System.Collections.ArrayList   # 状态 = unauthorized / offline 等
foreach ($ln in $devOut) {
    if ($ln -match '^\s*List of devices attached') { continue }
    if ($ln -match '^\s*\*\s') { continue }
    if ($ln -match '^\s*$') { continue }
    $m = [regex]::Match($ln, '^(\S+)\s+(\S+)(.*)$')
    if (-not $m.Success) { continue }
    $entry = [pscustomobject]@{
        Serial = $m.Groups[1].Value
        State  = $m.Groups[2].Value
        Rest   = $m.Groups[3].Value.Trim()
    }
    if ($entry.State -eq 'device') { $null = $ready.Add($entry) } else { $null = $other.Add($entry) }
}

if ($other.Count -gt 0) {
    Write-Output '  [注意] 有设备处于非就绪状态：'
    foreach ($d in $other) { Write-Output ('    * ' + $d.Serial + '  ->  ' + $d.State) }
    Write-Output '    - unauthorized : 手表屏幕上会弹「允许 USB 调试吗？」，必须在表上点「允许」'
    Write-Output '                     （记得勾选「一律允许来自这台计算机」）。没点之前一切命令都会被拒。'
    Write-Output '    - offline      : 通常是 adb 版本和手表不匹配，或连接不稳；试试 adb kill-server 后重连。'
    Write-Output ''
}

if ($ready.Count -eq 0) {
    Write-Output '  [前置条件不满足] 没有检测到任何可用设备（adb devices 里一台 device 都没有）。'
    Write-Output ''
    Write-Rule '没有设备时的排查清单'
    Write-Output  '  按下面的顺序一条条试，绝大多数情况都在前四条里：'
    Write-Output ''
    Write-Output  '  1) 手表上的「USB 调试」开关必须是打开的'
    Write-Output  '     设置 -> 关于手表 -> 连点「版本号」7 次进入开发者模式，'
    Write-Output  '     然后 设置 -> 系统 -> 开发者选项 -> 打开「USB 调试」。'
    Write-Output ''
    Write-Output  '  2) 很多原屏手表还要额外打开「USB 安装」/「允许通过 USB 安装应用」'
    Write-Output  '     这类机器在开发者选项里有一个单独的安装授权开关（名字各厂商不一样，'
    Write-Output  '     常见写法：USB 安装、允许通过 USB 安装应用、Install via USB）。'
    Write-Output  '     只开 USB 调试不开它，adb devices 能看到设备，但 adb install 会被拒。'
    Write-Output ''
    Write-Output  '  3) 数据线必须是「数据线」，不能是纯充电线'
    Write-Output  '     很多磁吸充电线只有电源针脚，没有 D+/D-。换一根能传数据的线（最好用原装）。'
    Write-Output ''
    Write-Output  '  4) 有的机型要装厂商的 ADB / USB 驱动'
    Write-Output  '     设备管理器里如果手表显示成未知设备或带感叹号，先装厂商驱动；'
    Write-Output  '     通用方案是 Google USB Driver，或在设备管理器里手动指定 Android ADB Interface。'
    Write-Output ''
    Write-Output  '  5) 重启这条链路：先拔插数据线，再重置 adb 服务'
    Write-Output  '       & "D:\android sdk\platform-tools\adb.exe" kill-server'
    Write-Output  '       & "D:\android sdk\platform-tools\adb.exe" start-server'
    Write-Output  '       & "D:\android sdk\platform-tools\adb.exe" devices -l'
    Write-Output ''
    Write-Output  '  6) 其它小坑'
    Write-Output  '     * 手表电量太低时会拒绝 USB 调试，先充一会儿；'
    Write-Output  '     * 手表上如果弹过授权框但点了「拒绝」，去开发者选项里「撤销 USB 调试授权」再插一次；'
    Write-Output  '     * USB 口换成主板直出的口，别用前面板/USB Hub；'
    Write-Output  '     * 确认手表不是在充电座上走「仅充电」模式（下拉通知栏可以切成「传输文件/MTP」）。'
    Write-Output ''
    Write-Output  '  修好之后再跑一次本脚本即可。'
    Write-Output ''
    Write-Output  '  退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}

if ($ready.Count -gt 1 -and -not $Serial) {
    Write-Output ('  [前置条件不满足] 检测到 ' + $ready.Count + ' 台设备，无法确定装到哪一台。')
    Write-Output ''
    Write-Output  '    请用 -Serial 指定序列号，例如：'
    Write-Output ('      powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1 -Serial ' + $ready[0].Serial)
    Write-Output ''
    Write-Output  '    也可以用 adb 原生的 -s：'
    Write-Output ('      adb -s ' + $ready[0].Serial + ' install -r "' + $ApkPath + '"')
    Write-Output ''
    Write-Output  '    当前可用设备：'
    foreach ($d in $ready) { Write-Output ('      * ' + $d.Serial + '   ' + $d.Rest) }
    Write-Output ''
    Write-Output  '  退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}

if ($Serial) {
    $hit = $ready | Where-Object { $_.Serial -eq $Serial }
    if (-not $hit) {
        Write-Output ('  [前置条件不满足] -Serial 指定的设备不在线： ' + $Serial)
        Write-Output  '    当前在线设备：'
        foreach ($d in $ready) { Write-Output ('      * ' + $d.Serial + '   ' + $d.Rest) }
        Write-Output ''
        Write-Output  '  退出码 2（前置条件不满足）'
        Write-Output ''
        exit 2
    }
    Write-Output ('  使用 -Serial 指定的设备： ' + $Serial)
} else {
    Write-Output ('  检测到 1 台设备： ' + $ready[0].Serial)
}
Write-Output ''

# ------------------------------------------------------------------ 3) 设备信息
Write-Rule '3/6 设备信息'
function Get-Prop {
    param([string] $Name)
    $v = Invoke-Adb @('shell', 'getprop', $Name)
    if ($v.Count -gt 0) { return ($v[0]).Trim() } else { return '' }
}

$model  = Get-Prop 'ro.product.model'
$brand  = Get-Prop 'ro.product.brand'
$rel    = Get-Prop 'ro.build.version.release'
$sdkv   = Get-Prop 'ro.build.version.sdk'
$abi    = Get-Prop 'ro.product.cpu.abi'
$abis   = Get-Prop 'ro.product.cpu.abilist'
$device = Get-Prop 'ro.product.device'

Write-Output ("  型号           : " + $brand + ' ' + $model + '   (' + $device + ')')
Write-Output ("  Android 版本   : " + $rel + '   (API ' + $sdkv + ')')
Write-Output ("  CPU ABI        : " + $abi + '     [abilist: ' + $abis + ']')
Write-Output ''

$wmSizeLines    = Invoke-Adb @('shell', 'wm', 'size')
$wmDensityLines = Invoke-Adb @('shell', 'wm', 'density')
$sizeText    = ($wmSizeLines -join ' / ').Trim()
$densityText = ($wmDensityLines -join ' / ').Trim()

Write-Output '  ************************************************************'
Write-Output '  **  屏幕参数（本项目 UI 按屏占比自适应，这两项必须确认！） **'
Write-Output '  ************************************************************'
Write-Output ('     分辨率 (wm size)    : ' + $sizeText)
Write-Output ('     密度   (wm density) : ' + $densityText)
$phys = [regex]::Match($sizeText, 'Physical size:\s*(\d+)x(\d+)')
if ($phys.Success) {
    $w = [int]$phys.Groups[1].Value
    $h = [int]$phys.Groups[2].Value
    $ratio = [Math]::Round($w / $h, 3)
    Write-Output ('     物理像素            : ' + $w + ' x ' + $h + '   宽高比 = ' + $ratio)
    if ($w -eq $h) {
        Write-Output  '     形状推断            : 正方形 -> 圆屏手表，符合本项目目标设备'
    } else {
        Write-Output  '     形状推断            : 非正方形 -> 方形/长条屏，布局按自适应逻辑处理，建议人工看一眼'
    }
}
if ($phys.Success -and $densityText -match '(\d+)') {
    $dpi = [int]$Matches[1]
    Write-Output ('     dp 尺寸推算         : ' + [Math]::Round(($w / ($dpi / 160.0)), 0) + ' dp 宽 x ' + [Math]::Round(($h / ($dpi / 160.0)), 0) + ' dp 高')
}
Write-Output '  ************************************************************'
Write-Output ''

# ------------------------------------------------------------------ 4) APK 检查
Write-Rule '4/6 检查 APK'
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    Write-Output ("  [前置条件不满足] 找不到 APK： " + $ApkPath)
    Write-Output ''
    Write-Output  '    说明 : 构建可能还没跑完，或者路径不对。本脚本不会自己触发 Gradle 构建。'
    Write-Output  '    怎么办：'
    Write-Output  '      1) 等构建结束再重试；'
    Write-Output  '      2) 自己构建：.\gradlew.bat :app:assembleDebug'
    Write-Output  '         （若已有 Gradle 进程在跑，请等它结束，避免同时写构建目录）'
    Write-Output  '      3) 指定别的 APK：-ApkPath <你的apk路径>'
    Write-Output  '      4) 只想校验不想装：tools\verify-apk.ps1'
    Write-Output ''
    Write-Output  '  退出码 2（前置条件不满足）'
    Write-Output ''
    exit 2
}
$apkItem = Get-Item -LiteralPath $ApkPath
Write-Output ('  OK : ' + $ApkPath)
Write-Output ('       大小 ' + [Math]::Round($apkItem.Length / 1MB, 2).ToString('N2') + ' MB，修改时间 ' + $apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
Write-Output ''

# ------------------------------------------------------------------ 5) 安装
Write-Rule '5/6 安装'
if ($Uninstall) {
    Write-Output ('  -Uninstall 已指定：先卸载 ' + $Package + '（失败不中断，可能本来就没装）')
    $unOut = Invoke-Adb @('uninstall', $Package)
    foreach ($ln in $unOut) { Write-Output ('    | ' + $ln) }
    Write-Output ''
}

$installArgs = @('install', '-r', $ApkPath)
Write-Output ('  执行 : adb ' + $(if ($Serial) { '-s ' + $Serial + ' ' } else { '' }) + 'install -r "' + $ApkPath + '"')
$instOut = Invoke-Adb $installArgs
$instExit = $script:LastAdbExit
foreach ($ln in $instOut) { Write-Output ('    | ' + $ln) }
Write-Output ''

$joined = ($instOut -join "`n")
if ($joined -match 'Success' -and $instExit -eq 0) {
    Write-Output '  安装结果 : SUCCESS'
} else {
    Write-Output '  安装结果 : FAILED'
    Write-Output ''
    Write-Output '  常见原因：'
    if ($joined -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' -or $joined -match 'signatures do not match') {
        Write-Output '    * 签名不一致 —— 机器上装的是另一个签名的同包名 App。'
        Write-Output '      处理：加 -Uninstall 参数先卸载再装。'
    } elseif ($joined -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
        Write-Output '    * 版本降级被拒。处理：加 -Uninstall，或提高 versionCode。'
    } elseif ($joined -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') {
        Write-Output '    * 手表存储不足。清理一下再装。'
    } elseif ($joined -match 'INSTALL_PARSE_FAILED') {
        Write-Output '    * APK 解析失败，产物可能是坏的。先跑 tools\verify-apk.ps1 校验。'
    } elseif ($joined -match 'no devices|device offline|device unauthorized') {
        Write-Output '    * 设备掉线/未授权。回到第 2 步的排查清单。'
    } else {
        Write-Output '    * 先跑 tools\verify-apk.ps1 确认 APK 本身没问题；'
        Write-Output '    * 再确认手表上「USB 安装 / 允许通过 USB 安装应用」是打开的；'
        Write-Output '    * 实在不行加 -Uninstall 先卸载再试。'
    }
    Write-Output ''
    Write-Output '  退出码 1（安装失败）'
    Write-Output ''
    exit 1
}
Write-Output ''

# ------------------------------------------------------------------ 6) 启动 + 可选日志
Write-Rule '6/6 启动'
Write-Output ('  执行 : adb shell am start -n ' + $Activity)
$startOut = Invoke-Adb @('shell', 'am', 'start', '-n', $Activity)
$startExit = $script:LastAdbExit
foreach ($ln in $startOut) { Write-Output ('    | ' + $ln) }
Write-Output ''

$startOk = ($startExit -eq 0) -and -not (($startOut -join "`n") -match 'Error|Exception|does not exist')

if (-not $startOk) {
    Write-Output '  启动结果 : FAILED'
    Write-Output '  提示 : 用 -Logcat 再跑一次抓崩溃日志，能看到最直接的报错栈。'
    Write-Output ''
    Write-Output '  退出码 1（启动失败）'
    Write-Output ''
    exit 1
}
Write-Output '  启动结果 : OK —— App 应该已经在手表上打开了。'
Write-Output ''

if ($Logcat) {
    Write-Rule '-Logcat：抓最近 200 行日志（只高亮可疑行）'
    $logLines = Invoke-Adb @('logcat', '-d', '-t', '200')
    $total = 0
    $hits  = 0
    foreach ($ln in $logLines) {
        $total++
        $isCrash = ($ln -match 'AndroidRuntime') -or ($ln -match 'FATAL') -or ($ln -match '(?i)functiondrawer')
        if ($isCrash) {
            $hits++
            $hl = '  >> ' + $ln
            # 交互式终端里用红色高亮；但输出一旦被重定向/捕获，Write-Host 的内容
            # 不会进管道，最关键的崩溃行反而会丢，所以那种情况必须走 Write-Output。
            if ([Console]::IsOutputRedirected) { Write-Output $hl }
            else { Write-Host $hl -ForegroundColor Red }
        }
    }
    Write-Output ''
    Write-Output ('  共读取 ' + $total + ' 行，其中可疑行（AndroidRuntime / FATAL / functiondrawer）' + $hits + ' 行（上面以 >> 标出的就是）。')
    if ($hits -eq 0) {
        Write-Output '  没有可疑行 —— 说明这次启动没崩，或者日志被冲掉了。'
    } elseif ($logLines -join "`n" -match 'FATAL EXCEPTION') {
        Write-Output '  看到 FATAL EXCEPTION：崩溃栈就在上面的 >> 行里，重点看第一行 "Caused by" 和你的包名那一帧。'
    }
    Write-Output ''
    Write-Output '  想要实时日志：'
    Write-Output ('    & "' + $Adb + '"' + $(if ($Serial) { ' -s ' + $Serial } else { '' }) + ' logcat -v time | Select-String "AndroidRuntime|FATAL|functiondrawer"')
    Write-Output ''
}

Write-Rule '完成'
Write-Output ('  包名 : ' + $Package)
Write-Output ('  设备 : ' + $(if ($Serial) { $Serial } else { $ready[0].Serial }))
Write-Output ('  屏幕 : ' + $sizeText + '   ' + $densityText)
Write-Output ''
Write-Output '  退出码 0（安装并启动成功）'
Write-Output ''
exit 0
