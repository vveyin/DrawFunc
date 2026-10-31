# sign-release.ps1 —— 给 release 包补上 **v1（JAR）签名**，做成 v1+v2+v3 三签
#
# 为什么需要这个脚本（真机上踩过的坑）：
#   * `apksigner` 默认**只签"这台设备的 minSdkVersion 需要的那几档"** —— 本项目的
#     `minSdk = 24`（Android 7.0）已经支持 v2，于是默认只产出 v2（+v3），
#     **v1 是关的**；
#   * AGP 的 `signingConfigs { enableV1Signing = true }` 在 AGP 8.5.2 上被忽略
#     （写进去了，产物仍然是 v1=false），所以"改 gradle 配置"这条路走不通；
#   * 结果就是老设备（Android 6/7 那批只认 JAR 签名的安装器）装不上。
#     用户原话："你加 v1 签名啊，实在要装什么工具找我要权限。"
#
# 所以：**构建之后跑一次本脚本**，把 `app-release.apk` 重新签成三签。
# 工具全部是本机已有的（`D:\jdk17` 的 java + `D:\android sdk\build-tools` 的
# `zipalign` / `apksigner`），**不需要额外安装任何东西**。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\sign-release.ps1
#   powershell -ExecutionPolicy Bypass -File tools\sign-release.ps1 -ApkPath <别的apk> -OutPath <输出>
#
# 顺序（很重要，反过来都不行）：
#   1. `zipalign -p -f 4` 先对齐（**签名之前**对齐；签完再对齐就把签名挪坏了）；
#   2. `apksigner sign --v1 --v2 --v3` 一次性签三档（apksigner 自己就会做 JAR 签名，
#      不需要 `jarsigner` 那一步）；
#   3. 校验：三档都要 true、`zipalign -c` 要过。
#
# 退出码：0 = 三签成功且校验通过；1 = 失败；2 = 前置条件不满足。
#
# 注意：这个文件必须以 **带 BOM 的 UTF-8** 保存。Windows PowerShell 5.1
#       在无 BOM 时会按 GBK 解码，中文注释会把脚本语法搞坏。

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $ApkPath,

    [string] $OutPath
)

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$ErrorActionPreference = 'Continue'   # 原生命令写 stderr 不该变成终止性错误

# ------------------------------------------------------------------ 常量
$scriptRoot  = $PSScriptRoot
$projectRoot = Split-Path -Parent $scriptRoot

$SdkRoot    = 'D:\android sdk'
$BuildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
if (-not (Test-Path -LiteralPath (Join-Path $BuildTools 'apksigner.bat'))) {
    # 36.0.0 不在就退回 34.0.0（两台机器上都装过的两套）
    $BuildTools = Join-Path $SdkRoot 'build-tools\34.0.0'
}
$Apksigner = Join-Path $BuildTools 'apksigner.bat'
$Zipalign  = Join-Path $BuildTools 'zipalign.exe'
$JdkHome   = 'D:\jdk17'

if (-not $ApkPath) { $ApkPath = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk' }
if (-not $OutPath) {
    # 默认输出到 build\apk-signed\：**不覆盖** Gradle 的产物，方便对比"签之前/之后"
    $OutPath = Join-Path $projectRoot 'build\apk-signed\DrawFunc-v1v2v3.apk'
}
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
$OutPath = [System.IO.Path]::GetFullPath($OutPath)
$aligned = Join-Path (Split-Path -Parent $OutPath) 'DrawFunc-aligned.apk'

$keystoreProps = Join-Path $projectRoot 'keystore.properties'

function Write-Rule {
    param([string] $Title = '')
    if ($Title) {
        Write-Output ('  ---- ' + $Title + ' ' + ('-' * [Math]::Max(0, 70 - $Title.Length)))
    } else {
        Write-Output ('  ' + ('-' * 78))
    }
}

function Fail {
    param([string] $Message, [int] $Code = 1)
    Write-Output ''
    Write-Output ('  [FAIL] ' + $Message)
    Write-Output ("  退出码 $Code")
    Write-Output ''
    exit $Code
}

Write-Output ''
Write-Rule 'release 包三签（v1 JAR + v2 + v3）'
Write-Output ('  输入 APK   : ' + $ApkPath)
Write-Output ('  输出 APK   : ' + $OutPath)
Write-Output ('  构建工具   : ' + $BuildTools)
Write-Output ''

# ------------------------------------------------------------------ 前置检查
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Fail "找不到输入 APK：$ApkPath（先跑 :app:assembleRelease）" 2 }
if (-not (Test-Path -LiteralPath $Apksigner -PathType Leaf)) { Fail "找不到 apksigner.bat：$Apksigner" 2 }
if (-not (Test-Path -LiteralPath $Zipalign -PathType Leaf)) { Fail "找不到 zipalign.exe：$Zipalign" 2 }
if (-not (Test-Path -LiteralPath $JdkHome)) { Fail "找不到 JDK：$JdkHome（apksigner 要 java）" 2 }
if (-not (Test-Path -LiteralPath $keystoreProps -PathType Leaf)) {
    Fail "找不到 keystore.properties：$keystoreProps（签名口令与密钥路径都在里面）" 2
}

# ---- 读 keystore.properties（口令经**环境变量**传给 apksigner，不出现在命令行里）----
$props = @{}
foreach ($line in Get-Content -LiteralPath $keystoreProps) {
    $m = [regex]::Match($line, '^\s*([^#=]+?)\s*=\s*(.*)$')
    if ($m.Success) { $props[$m.Groups[1].Value.Trim()] = $m.Groups[2].Value.Trim() }
}
$storeName = $props['storeFile']
$alias     = $props['keyAlias']
if (-not $storeName) { Fail 'keystore.properties 里没有 storeFile' 2 }
if (-not $alias)     { Fail 'keystore.properties 里没有 keyAlias' 2 }
$storePath = Join-Path $projectRoot $storeName
if (-not (Test-Path -LiteralPath $storePath -PathType Leaf)) { Fail "找不到密钥库：$storePath" 2 }

$env:JAVA_HOME = $JdkHome
if ($env:PATH -notlike "*$JdkHome\bin*") { $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH }
$env:FD_KS_PASS  = $props['storePassword']
$env:FD_KEY_PASS = $props['keyPassword']
if (-not $env:FD_KS_PASS)  { Fail 'keystore.properties 里没有 storePassword' 2 }
if (-not $env:FD_KEY_PASS) { Fail 'keystore.properties 里没有 keyPassword' 2 }

Write-Output ('  密钥库     : ' + $storePath)
Write-Output ('  别名       : ' + $alias)
Write-Output ''

# ------------------------------------------------------------------ 1) 对齐
$outDir = Split-Path -Parent $OutPath
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Remove-Item -LiteralPath $aligned, $OutPath -Force -ErrorAction SilentlyContinue

Write-Rule '1) zipalign -p -f 4（必须在签名之前）'
$alignOut = & $Zipalign '-p' '-f' '4' $ApkPath $aligned 2>&1
if ($LASTEXITCODE -ne 0) {
    foreach ($ln in $alignOut) { Write-Output ('  | ' + [string]$ln) }
    Fail "zipalign 失败（退出码 $LASTEXITCODE）" 1
}
Write-Output ('  已对齐输出：' + $aligned)
Write-Output ''

# ------------------------------------------------------------------ 2) 三签
Write-Rule '2) apksigner sign --v1 --v2 --v3'
$signOut = & $Apksigner 'sign' `
    '--ks' $storePath `
    '--ks-key-alias' $alias `
    '--ks-pass' 'env:FD_KS_PASS' `
    '--key-pass' 'env:FD_KEY_PASS' `
    '--v1-signing-enabled' 'true' `
    '--v2-signing-enabled' 'true' `
    '--v3-signing-enabled' 'true' `
    '--out' $OutPath `
    $aligned 2>&1
$signExit = $LASTEXITCODE
foreach ($ln in $signOut) { Write-Output ('  | ' + [string]$ln) }
if ($signExit -ne 0) { Fail "apksigner sign 失败（退出码 $signExit）" 1 }
Write-Output ('  已三签输出：' + $OutPath)
Write-Output ''

# ------------------------------------------------------------------ 3) 校验
Write-Rule '3) 校验：三档签名 + 对齐'
# ⚠️ 必须显式给 `--min-sdk-version 21`：apksigner verify **默认只检"本包 minSdk 需要的那几档"**，
#    本包 minSdk = 24（Android 7 已支持 v2）→ 默认输出会写 `v1 scheme: false`，
#    哪怕 v1 签名**好好地在那儿**。这一条正是当初"以为 v1 没签上"的误会来源。
$verifyOut = & $Apksigner 'verify' '--verbose' '--min-sdk-version' '21' $OutPath 2>&1
$verifyExit = $LASTEXITCODE
$v1 = $false; $v2 = $false; $v3 = $false
foreach ($ln in $verifyOut) {
    $s = [string]$ln
    if ($s -match 'Verified using v1 scheme \(JAR signing\):\s*(true|false)') { $v1 = ($Matches[1] -eq 'true') }
    if ($s -match 'Verified using v2 scheme \(APK Signature Scheme v2\):\s*(true|false)') { $v2 = ($Matches[1] -eq 'true') }
    if ($s -match 'Verified using v3 scheme \(APK Signature Scheme v3\):\s*(true|false)') { $v3 = ($Matches[1] -eq 'true') }
}
Write-Output ('  v1（JAR 签名） : ' + $(if ($v1) { 'true  ← 本脚本存在的意义' } else { 'false ← 没签上！' }))
Write-Output ('  v2             : ' + $v2)
Write-Output ('  v3             : ' + $v3)
Write-Output ('  verify 退出码  : ' + $verifyExit)

$alignCheck = & $Zipalign '-c' '-v' '4' $OutPath 2>&1
$alignExit = $LASTEXITCODE
Write-Output ('  zipalign -c    : ' + $(if ($alignExit -eq 0) { 'PASS' } else { 'FAIL' }))

if (-not ($v1 -and $v2 -and $v3)) { Fail '三档签名没有全部通过（见上面几行）' 1 }
if ($alignExit -ne 0) { Fail '签名之后对齐校验没过 —— 说明有工具在签名后动过字节' 1 }
Write-Output ''

# ------------------------------------------------------------------ 摘要
$item = Get-Item -LiteralPath $OutPath
$sha  = (Get-FileHash -LiteralPath $OutPath -Algorithm SHA256).Hash
Write-Rule '结果'
Write-Output ('  文件   : ' + $OutPath)
Write-Output ('  大小   : ' + $item.Length.ToString('N0') + ' 字节')
Write-Output ('  SHA256 : ' + $sha)
Write-Output ''
Write-Output '  下一步 : 直接用这个包安装 / 上传：'
Write-Output ('           & ''D:\android sdk\platform-tools\adb.exe'' install -r "' + $OutPath + '"')
Write-Output ''
Write-Output '  退出码 0（三签成功）'
Write-Output ''
exit 0
