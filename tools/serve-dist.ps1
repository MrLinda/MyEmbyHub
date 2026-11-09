<#
.SYNOPSIS
    把 dist 目录用 HTTP 暴露出去，供电视上的 MyEmbyHub 在线下载 APK。

.EXAMPLE
    .\tools\serve-dist.ps1
    .\tools\serve-dist.ps1 -Port 8765

.NOTES
    电视上的 MyEmbyHub → 「在线更新」→ 填 http://<本机IP>:8765/myembyhub-debug.apk
    本机 IP 可用 ipconfig 查看（要和电视在同一个 WiFi 下）。
    ⚠️ 端口 8000 落在 Windows 保留段 7928-8027，绑定会报 WinError 10013 → 统一用 8765。
#>
param(
    [int]$Port = 8765,
    [string]$Root = (Join-Path $PSScriptRoot '..\dist')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $Root).Path

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
$listener.Prefixes.Add("http://*:$Port/")

try {
    $listener.Start()
} catch {
    Write-Host "监听失败（端口 $Port 可能被占用，或需要管理员权限）：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "可以换个端口再试：-Port 8080" -ForegroundColor Yellow
    exit 1
}

Write-Host "已启动静态服务：" -ForegroundColor Green
Write-Host "  根目录: $root"
Write-Host "  本机  : http://127.0.0.1:$Port/"
Write-Host "  局域网: http://<本机IP>:$Port/"
Write-Host ""
Write-Host "电视上填这个地址即可在线更新（Ctrl+C 停止）：" -ForegroundColor Cyan
Get-ChildItem $root -Filter *.apk | ForEach-Object {
    Write-Host "  http://<本机IP>:$Port/$($_.Name)   ($([int]($_.Length/1024)) KB)"
}

$mime = @{
    '.apk' = 'application/vnd.android.package-archive'
    '.html' = 'text/html; charset=utf-8'
    '.conf' = 'text/plain; charset=utf-8'
    '.png'  = 'image/png'
    '.json' = 'application/json'
}

while ($listener.IsListening) {
    try {
        $ctx = $listener.GetContext()
        $path = $ctx.Request.Url.AbsolutePath.TrimStart('/')
        if ($path -eq '') { $path = 'index.html' }
        $file = Join-Path $root $path

        # 防目录穿越
        $full = [System.IO.Path]::GetFullPath($file)
        if (-not $full.StartsWith($root)) {
            $ctx.Response.StatusCode = 403
            $ctx.Response.Close()
            continue
        }

        if (Test-Path -LiteralPath $full -PathType Leaf) {
            $ext = [System.IO.Path]::GetExtension($full).ToLower()
            $ctx.Response.ContentType = if ($mime.ContainsKey($ext)) { $mime[$ext] } else { 'application/octet-stream' }
            $bytes = [System.IO.File]::ReadAllBytes($full)
            $ctx.Response.ContentLength64 = $bytes.Length
            $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            Write-Host ("{0}  {1}  ({2} bytes)" -f (Get-Date -Format 'HH:mm:ss'), $path, $bytes.Length) -ForegroundColor DarkGray
        } else {
            $ctx.Response.StatusCode = 404
            $ctx.Response.Close()
            Write-Host "404 $path" -ForegroundColor Yellow
        }
        $ctx.Response.Close()
    } catch {
        Write-Host "处理请求出错: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}
