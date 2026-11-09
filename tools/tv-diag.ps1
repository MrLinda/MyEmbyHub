#!/usr/bin/env pwsh
<#
  电视端 TLS 自动诊断：一键完成「触发 → 等待 → 拉回结果」。

  用法:
      tools\tv-diag.ps1                       # 默认诊断 tv.example.com:443
      tools\tv-diag.ps1 -Host 203.0.113.10 -Port 443
      tools\tv-diag.ps1 -Capture             # 额外让电脑抓 ClientHello（需先起 tls-capture.py）

  原理:
      tvcheck 收到 intent extra `cmd=diag` 后会自动跑完整 TLS 矩阵，
      结果写到 /sdcard/tvcheck-tls.txt。这里用 adb 触发并把文件拉回电脑，
      全程不需要在电视上按任何按钮（screencap 在这台 ROM 上输出 0 字节，
      靠截图回传不可行）。
#>
param(
    [string]$Host_ = "",
    [string]$Ip = "192.168.1.100:5555",
    [int]$Port = 443,
    [switch]$Capture,
    [int]$WaitSec = 90
)

$ErrorActionPreference = 'Stop'
$adb = 'E:\Programs\Android\Sdk\platform-tools\adb.exe'
$root = Split-Path -Parent $PSScriptRoot
$outFile = Join-Path $root 'dist\tv-tls-diag.txt'

if (-not $Host_) {
    # 从电视上的 conf 里读 server
    $conf = & $adb -s $Ip shell "cat /sdcard/tvcheck.conf" 2>$null
    $line = $conf | Where-Object { $_ -match '^server=' } | Select-Object -First 1
    if ($line) {
        $Host_ = ($line -replace '^server=', '') -replace '^https?://', '' -replace '/.*$', ''
    }
    if (-not $Host_) { $Host_ = 'tv.example.com' }
}

Write-Host "==> 目标: $Host_ : $Port" -ForegroundColor Cyan

# 0) 先删旧结果，避免读到上一次的（必须在触发之前删）
& $adb -s $Ip shell "rm -f /sdcard/tvcheck-tls.txt" | Out-Null

# 1) 触发自动诊断
$cmd = "am start --user 0 -n work.luxiaoxiao.tvcheck/.MainActivity --es cmd diag --es host $Host_ --ei port $Port"
& $adb -s $Ip shell $cmd | Out-Null
Write-Host "==> 已触发，等待结果…" -ForegroundColor Cyan

# 2) 等待结果文件生成
$deadline = (Get-Date).AddSeconds($WaitSec)
$done = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $exists = & $adb -s $Ip shell "ls -l /sdcard/tvcheck-tls.txt" 2>$null
    if ($exists -match 'tvcheck-tls.txt') {
        # 再等一下，确保写完
        Start-Sleep -Seconds 2
        $done = $true
        break
    }
}

if (-not $done) {
    Write-Host "!! 超时：没有生成 /sdcard/tvcheck-tls.txt" -ForegroundColor Red
    Write-Host "   可能原因：App 没装好，或 intent 启动失败。检查 logcat：" -ForegroundColor Yellow
    Write-Host "   adb -s $Ip logcat -d -s TvCheck" -ForegroundColor Yellow
    exit 1
}

# 3) 拉回结果
& $adb -s $Ip pull /sdcard/tvcheck-tls.txt $outFile | Out-Null
Write-Host "==> 结果已保存: $outFile" -ForegroundColor Green
Write-Host ""
Get-Content $outFile -Encoding UTF8
