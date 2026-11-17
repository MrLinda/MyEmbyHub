# ============================================================================
# 发布一个 GitHub Release，并把在线更新需要的两个资产上传上去。
#
#   pwsh tools/publish-release.ps1 -Repo owner/repo -Tag v1.0.20261117 -Notes "修复扫码登录"
#
# 上传的资产必须**同名**（客户端写死的就是这两个名字）：
#   myembyhub-debug.apk    ← dist/ 里的包
#   version.json           ← 打包时自动生成的清单
#
# 客户端取的是
#   https://github.com/<repo>/releases/latest/download/version.json
# 这个地址不带 tag，永远指向「最新一个正式 Release」，所以发新版只要重跑本脚本。
#
# ⚠️ 先决条件
#   ① 装 GitHub CLI（gh）并 `gh auth login`
#   ② 打包前在 local.properties 里写 updateRepo=<owner>/<repo>，
#      否则生成的 version.json 里 url 还指着内网静态服务
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Notes = "",
    [string]$Title = "",
    # Release 默认设为正式版（不是 draft / prerelease）：
    # `releases/latest` 会把 draft 和 prerelease 排除掉，打成 prerelease 客户端就看不到了
    [switch]$Prerelease
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$apk = Join-Path $dist 'myembyhub-debug.apk'
$manifest = Join-Path $dist 'version.json'

if (-not (Test-Path $apk)) {
    # AGENTS.md 记过：gradlew :app:assembleDebug 只生成 dist/version.json，
    # APK 要手动同步进来。这里顺手补上，省得每次忘。
    $built = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $built)) { throw "找不到 APK：$built 也不存在，先跑 gradlew :app:assembleDebug" }
    Copy-Item $built $apk -Force
    Write-Host "已把构建产物同步到 $apk"
}
else {
    # dist/ 里已经有一个旧包时**必须比时间**：只判「存在」会把上一次的包传上去，
    # 而清单是这次生成的 → 电视装完还提示有新版（就是下面一致性校验拦的那种）。
    $built = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $built) {
        $b = (Get-Item $built).LastWriteTime
        $d = (Get-Item $apk).LastWriteTime
        if ($b -gt $d) {
            Copy-Item $built $apk -Force
            Write-Host "构建产物比 dist/ 里的新，已覆盖同步"
        }
    }
}
if (-not (Test-Path $manifest)) { throw "找不到清单：$manifest，先跑 gradlew :app:assembleDebug" }

# 发版前最后一道检查：清单里的下载地址必须是公网地址
$json = Get-Content $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($json.url -match '^http://\d+\.\d+\.\d+\.\d+') {
    Write-Warning "version.json 里的 url 还是内网地址（$($json.url)）。"
    Write-Warning "在 local.properties 里写 updateRepo=$Repo 再重新打包，否则电视更新会下载失败。"
}

# ── 清单与 APK 必须是同一次打包出来的 ────────────────────────────────────
# 两者版本号对不上时的表现极有迷惑性：电视装完包、再点检查更新，
# 还是提示「发现新版本」—— 因为它拿清单里的 versionCode 去比，
# 而那个数字比包里的大。看起来像更新坏了，其实只是清单和包错位。
# 常见成因：手工改过 version.json、从别处拷了个 APK 进 dist/、或者只重打了其中一个。
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = 'E:\Programs\Android\Sdk' }
$aapt = Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools') -Filter 'aapt.exe' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($aapt) {
    $badging = & $aapt.FullName dump badging $apk 2>&1 | Select-String -Pattern "package: name='([^']+)' versionCode='(\d+)'" | Select-Object -First 1
    if ($badging) {
        $m = [regex]::Match($badging.Line, "versionCode='(\d+)'")
        $apkCode = [long]$m.Groups[1].Value
        if ($apkCode -ne [long]$json.versionCode) {
            throw ("清单和 APK 不是同一次打包出来的：" +
                "version.json 说 $($json.versionCode)，APK 里是 $apkCode。" +
                "重新跑 gradlew :app:assembleDebug 并把 APK 同步进 dist/ 再发。")
        }
        Write-Host "  校验    : 清单与 APK 版本一致（$apkCode）"
    } else {
        Write-Warning "aapt 读不出 APK 的 versionCode，跳过一致性校验"
    }
} else {
    Write-Warning "找不到 aapt.exe（ANDROID_HOME=$sdkRoot），跳过清单/APK 一致性校验"
}

Write-Host "发布 $Tag"
Write-Host "  APK     : $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB)"
Write-Host "  版本    : $($json.versionName) / $($json.versionCode)"
Write-Host "  下载地址: $($json.url)"

$assets = @($apk, $manifest)
# ⚠️ 变量名不能叫 $args —— 那是 PowerShell 的自动变量（本脚本的参数数组），
#    赋值会把它顶掉，后面的 & gh @ghArgs 行为就不可预期了。
$ghArgs = @('release', 'create', $Tag, '--repo', $Repo, '--title', $(if ($Title) { $Title } else { $Tag }))
if ($Notes) { $ghArgs += @('--notes', $Notes) } else { $ghArgs += '--generate-notes' }
if ($Prerelease) { $ghArgs += '--prerelease' }
$ghArgs += $assets

& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release create 失败（exit $LASTEXITCODE）" }

Write-Host ""
Write-Host "完成。客户端会从这里取清单："
Write-Host "  https://github.com/$Repo/releases/latest/download/version.json"
Write-Host "国内电视实际会先试镜像前缀（ghfast.top / gh-proxy.com / ghproxy.net），"
Write-Host "都不通才直连 github.com —— 见 UpdateManager.MIRROR_PREFIXES。"