#!/usr/bin/env pwsh
<#
  电视守候脚本：等电视醒来 → 装新版 → 自动跑完全部探针 → 拉结果回电脑。

  用法（后台跑，不阻塞）:
      tools\tv-wait-and-run.ps1

  流程:
      1. 循环尝试 adb connect（电视休眠时 5555 端口是关的）
      2. 一旦连上 → 把 dist/tvcheck-debug.apk 推过去并调起系统安装器
         （这一步需要人在电视上按一次「安装」）
      3. 等待安装完成（用 decode 探针文件里是否出现「解码器探测」判断版本）
      4. 自动依次跑 stream / decode / diag 三个探针
      5. 全部结果 adb pull 到 dist/

  所有步骤除「按一次安装」外都不需要人参与。
#>
param(
    [string]$Ip = "192.168.1.100:5555",
    [int]$ConnectTimeoutMin = 40,
    [int]$WaitInstallMin = 20
)

$ErrorActionPreference = 'Continue'
$adb = 'E:\Programs\Android\Sdk\platform-tools\adb.exe'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$apk = Join-Path $dist 'tvcheck-debug.apk'

function Say($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

# ---------- 1. 等电视醒来 ----------
$deadline = (Get-Date).AddMinutes($ConnectTimeoutMin)
$online = $false
Say "等待电视上线（$Ip）…"
while ((Get-Date) -lt $deadline) {
    $out = & $adb connect $Ip 2>&1
    if ($out -match 'connected to') { $online = $true; break }
    if ($out -match 'already connected') { $online = $true; break }
    Start-Sleep -Seconds 15
}
if (-not $online) {
    Say "超时：电视仍未上线（可能还在休眠）。"
    exit 1
}
Say "电视已上线！"

# ---------- 2. 推送并调起安装器 ----------
Say "推送 APK 并调起系统安装器…"
& $adb -s $Ip push $apk /sdcard/Download/tvcheck.apk | Out-Null
& $adb -s $Ip shell "am start --user 0 -a android.intent.action.VIEW -d 'file:///sdcard/Download/tvcheck.apk' -t 'application/vnd.android.package-archive'" | Out-Null
Say "请在电视上按一次「安装」（这一步只能人操作）"

# ---------- 3. 等安装完成 ----------
$deadline = (Get-Date).AddMinutes($WaitInstallMin)
$installed = $false
while ((Get-Date) -lt $deadline) {
    & $adb -s $Ip shell "rm -f /sdcard/tvcheck-decode.txt" | Out-Null
    & $adb -s $Ip shell "am start --user 0 -n com.example.tvcheck/.MainActivity --es cmd decode" | Out-Null
    Start-Sleep -Seconds 50
    $r = & $adb -s $Ip shell "cat /sdcard/tvcheck-decode.txt" 2>&1
    if ($r -and ($r -match '解码器探测')) { $installed = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $installed) {
    Say "等待安装超时（没检测到新版）。"
    exit 1
}
Say "新版已安装，开始自动跑探针"

# ---------- 4. 跑三个探针 ----------
$probes = @(
    @{ cmd = 'stream'; file = 'tvcheck-stream.txt'; wait = 40 },
    @{ cmd = 'decode'; file = 'tvcheck-decode.txt'; wait = 55 },
    @{ cmd = 'diag';   file = 'tvcheck-tls.txt';    wait = 45 }
)

foreach ($p in $probes) {
    Say "跑探针 cmd=$($p.cmd) …"
    & $adb -s $Ip shell "rm -f /sdcard/$($p.file)" | Out-Null
    & $adb -s $Ip shell "am start --user 0 -n com.example.tvcheck/.MainActivity --es cmd $($p.cmd)" | Out-Null
    # 等文件出现
    $ok = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 3
        $sz = & $adb -s $Ip shell "ls -l /sdcard/$($p.file)" 2>&1
        if ($sz -match [regex]::Escape($p.file)) { $ok = $true; break }
    }
    if ($ok) {
        Start-Sleep -Seconds 2
        & $adb -s $Ip pull "/sdcard/$($p.file)" (Join-Path $dist "tv-$($p.cmd).txt") | Out-Null
        Say "  → 已拉回 dist/tv-$($p.cmd).txt"
    } else {
        Say "  → 探针 $($p.cmd) 没产出文件"
    }
}

Say "全部完成。结果在 dist/tv-stream.txt / tv-decode.txt / tv-diag.txt"
