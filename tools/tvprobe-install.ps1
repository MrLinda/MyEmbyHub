<#
.SYNOPSIS
    把 tvprobe 探针 App 装到乐视 S50 Air（Android TV）上，并回读系统版本。

.EXAMPLE
    # 无线 adb：电视 IP 写 192.168.1.50
    .\tools\tvprobe-install.ps1 -Ip 192.168.1.50

.EXAMPLE
    # 已经用 USB 连好并授权了 adb
    .\tools\tvprobe-install.ps1

.NOTES
    电视端需要开启「开发者选项 / USB 调试」。乐视多数机型默认已开 adbd，
    无线调试端口一般是 5555。若都不通，可在电视设置里打开「开发者选项」找 USB 调试开关。
#>
param(
    [string]$Ip,
    [int[]]$Ports = @(5555, 5554, 5553),
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

$adb = Join-Path 'E:\Programs\Android\Sdk\platform-tools' 'adb.exe'
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source } else { throw "找不到 adb.exe，请检查路径" }
}

$apk = Join-Path $PSScriptRoot '..\tvprobe\build\outputs\apk\debug\tvprobe-debug.apk'
$apk = (Resolve-Path $apk).Path

Write-Host "adb  : $adb" -ForegroundColor DarkGray
Write-Host "apk  : $apk" -ForegroundColor DarkGray

# ---------- 1. 连接电视 ----------
if ($Ip) {
    $connected = $false
    foreach ($p in $Ports) {
        Write-Host "尝试 adb connect ${Ip}:${p} ..." -ForegroundColor Cyan
        & $adb connect "${Ip}:${p}"
        Start-Sleep -Milliseconds 800
        $list = & $adb devices
        if ($list -match "^${Ip}:${p}\s+device") { $connected = $true; break }
    }
    if (-not $connected) {
        Write-Warning "所有端口都没连上。若电视是 USB 连接，直接重跑本脚本（不加 -Ip）。"
    }
}

Write-Host "`n=== 设备列表 ===" -ForegroundColor Yellow
& $adb devices -l

$dev = (& $adb devices | Select-String '\sdevice$' | Select-Object -First 1)
if (-not $dev) {
    Write-Host "`n没有可用设备，脚本结束。" -ForegroundColor Red
    exit 1
}
$serial = ($dev -split '\s+')[0]
Write-Host "`n目标设备: $serial`n" -ForegroundColor Green

# ---------- 2. 直接读系统版本（不装 App 也能拿到）----------
Write-Host "=== 电视系统版本 ===" -ForegroundColor Yellow
foreach ($p in @(
    'ro.build.version.release',
    'ro.build.version.sdk',
    'ro.build.version.incremental',
    'ro.product.model',
    'ro.product.manufacturer',
    'ro.product.device',
    'ro.build.display.id',
    'ro.build.fingerprint'
)) {
    $v = (& $adb -s $serial shell getprop $p) -join ' '
    "{0,-32} {1}" -f $p, $v
}

# ---------- 3. 安装并启动 ----------
if (-not $SkipInstall) {
    Write-Host "`n=== 安装 ===" -ForegroundColor Yellow
    & $adb -s $serial install -r -t $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n安装失败。常见原因：签名冲突（先 uninstall work.luxiaoxiao.tvprobe）、电视存储不足、系统版本低于 minSdk 19。" -ForegroundColor Red
        exit 1
    }

    Write-Host "`n=== 启动 ===" -ForegroundColor Yellow
    & $adb -s $serial shell am start -n work.luxiaoxiao.tvprobe/.MainActivity
    Write-Host "`n已在电视上打开 TV Probe，屏幕上会大字显示 Android 版本。" -ForegroundColor Green
}
