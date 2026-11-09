#!/usr/bin/env pwsh
<#
  以「乐视 S50 Air」的参数启动 API 18 模拟器（LeTV），并校验关键指标。

  为什么要特殊处理堆上限:
      emulator 37 完全无视 config.ini 里的 vm.heapSize，一律按 ramSize/4 算
      （1536MB → 384MB），而电视上应用堆上限是 96MB。
      实测过的三条路：
        - vm.heapSize=96 / 48  → 无效，恒为 384m
        - /data/local.prop     → 4.3 镜像的 init 不加载（非 eng 版）
        - /system/build.prop   → 只在 -writable-system 启动时生效，普通启动用只读镜像
      唯一可靠的是启动参数 -prop dalvik.vm.heapsize=96m。

  用法:
      tools\start-tv-emulator.ps1              # 带 -prop 启动并等待 bootcomplete
      tools\start-tv-emulator.ps1 -Force       # 先杀掉已在跑的实例再启动
      tools\start-tv-emulator.ps1 -HeapMb 48   # 换堆上限
      tools\start-tv-emulator.ps1 -Patch       # 给 Studio 启动的实例补上堆上限

  -Patch 的用法:
      从 Android Studio 的 Device Manager 直接启动模拟器（它没法传 -prop），
      启动完跑一次 -Patch：setprop + stop/start，让 zygote 重新读取属性。
      然后照常在 Studio 点 Run 即可。

  注意:
      启动后 Android Studio 直接 Run 会复用已在运行的实例，不会重新带参数启动。
#>
param(
    [string]$Avd = 'LeTV',
    [int]$HeapMb = 96,
    [int]$WaitSec = 180,
    [switch]$Patch,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$sdk = 'E:\Programs\Android\Sdk'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

function Get-Screen {
    # stop/start 之后 bootcomplete 先到、WindowManager 后起，直接 wm 会抛 AndroidException
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        $size = (& $adb shell wm size 2>$null | Out-String).Trim()
        if ($size -match 'Physical size') {
            $density = (& $adb shell wm density 2>$null | Out-String).Trim()
            return "$size / $density"
        }
        Start-Sleep -Seconds 2
    }
    return 'unknown（WM 未就绪）'
}

function Show-Status {
    $api = (& $adb shell getprop ro.build.version.sdk).Trim()
    $heap = (& $adb shell getprop dalvik.vm.heapsize).Trim()
    $mem = (& $adb shell cat /proc/meminfo | Select-String 'MemTotal').ToString().Trim()
    $screen = Get-Screen
    Write-Host ""
    Write-Host "当前状态：" -ForegroundColor Green
    Write-Host "  API    : $api"
    Write-Host "  堆上限 : $heap"
    Write-Host "  内存   : $mem"
    Write-Host "  屏幕   : $screen"
    if ($heap -ne "${HeapMb}m") {
        Write-Host "⚠ 堆上限没生效，期望 ${HeapMb}m" -ForegroundColor Yellow
    }
}

function Wait-BootComplete {
    $deadline = (Get-Date).AddSeconds($WaitSec)
    while ((Get-Date) -lt $deadline) {
        if ((& $adb shell getprop dev.bootcomplete 2>$null) -match '1') { return $true }
        Start-Sleep -Seconds 2
    }
    Write-Host "⚠ ${WaitSec}s 内没等到 bootcomplete" -ForegroundColor Yellow
    return $false
}

$devices = & $adb devices
$running = $devices -match '^emulator-\d+\s+device'

if ($Patch) {
    if (-not $running) {
        Write-Host "没有在跑的模拟器，直接启动请去掉 -Patch" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "==> 给运行中的模拟器打堆上限补丁（dalvik.vm.heapsize=${HeapMb}m）" -ForegroundColor Cyan
    & $adb root | Out-Null
    Start-Sleep -Seconds 3
    & $adb wait-for-device
    & $adb shell "setprop dalvik.vm.heapsize ${HeapMb}m"
    # zygote 只在启动时读这个属性，必须重启框架
    & $adb shell stop
    Start-Sleep -Seconds 2
    & $adb shell start
    Wait-BootComplete | Out-Null
    Show-Status
    exit 0
}

if ($running) {
    if (-not $Force) {
        Write-Host "已有模拟器在跑：" -ForegroundColor Yellow
        $devices
        Write-Host "要重启请加 -Force；只想修堆上限请用 -Patch" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "==> 停止当前模拟器" -ForegroundColor Cyan
    & $adb emu kill | Out-Null
    Start-Sleep -Seconds 5
}

Write-Host "==> 启动 $Avd（dalvik.vm.heapsize=${HeapMb}m）" -ForegroundColor Cyan
Start-Process -FilePath $emulator -ArgumentList @(
    '-avd', $Avd,
    '-prop', "dalvik.vm.heapsize=${HeapMb}m",
    '-no-snapshot-load',
    '-gpu', 'swiftshader_indirect'
) | Out-Null

& $adb wait-for-device
Wait-BootComplete | Out-Null
Show-Status
