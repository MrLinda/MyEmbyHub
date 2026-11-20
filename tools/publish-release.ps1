# ============================================================================
# 发布一个 GitHub Release，并把在线更新需要的资产上传上去。
#
#   pwsh tools/publish-release.ps1 -Repo MrLinda/MyEmbyHub -Notes "修复扫码登录"
#   pwsh tools/publish-release.ps1 -Repo MrLinda/MyEmbyHub -Tag v1.0.20261117.3
#
# tag 不传就用 `v` + versionName（`v1.0.20261117.2`）。
# versionName 本身是 `1.0.yyyyMMdd.N`，N = 当天第几次打包（由 app/build.gradle.kts 管，
# 序号存在根目录 .version-seq 里）—— 一天推好几个版本时，第二个不会没名字可用。
# tag 和 versionName 一一对应；撞名说明这个包已经发过，脚本会直接报错。
#
# 上传的资产：
#   myembyhub.apk          ← 签名过的 release 包（客户端写死的就是这个名字）
#   myembyhub-debug.apk    ← 同一个包的旧名字副本，只为兼容老版本客户端（见下）
#   version.json           ← 打包时自动生成的清单
#
# 为什么还要传 myembyhub-debug.apk
#   老版本 App 的 BuildConfig 里烤的是
#   `.../releases/latest/download/myembyhub-debug.apk`。不传这个资产，
#   那些装了老包的电视点「检查更新」会拿到 404 —— 虽然它们最终也装不上去
#   （签名换了，见 MyEmbyHub-keys/README.txt），但至少不该让它们连下载都失败。
#   等确认没有老包在外面跑了，可以加 -NoLegacyAlias 停掉。
#
# 客户端取的是
#   https://github.com/<repo>/releases/latest/download/version.json
# 这个地址不带 tag，永远指向「最新一个正式 Release」，所以发新版只要重跑本脚本。
# 换句话说：**tag 怎么命名都不影响客户端**，改名/改序号不需要重新打包。
#
# ⚠️ 先决条件
#   ① 装 GitHub CLI（gh）并 `gh auth login`
#   ② 打包前在 local.properties 里写 updateRepo=<owner>/<repo>，
#      否则生成的 version.json 里 url 还指着内网静态服务
#   ③ local.properties 里要有 storeFile / storePassword / keyAlias / keyPassword，
#      否则打出来的 release 包是 debug 签名的 —— 脚本会拦住
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    # 不传就按 v<major.minor.yyyyMMdd>.<当天序号> 自动推导
    [string]$Tag = "",
    [string]$Notes = "",
    [string]$Title = "",
    # Release 默认设为正式版（不是 draft / prerelease）：
    # `releases/latest` 会把 draft 和 prerelease 排除掉，打成 prerelease 客户端就看不到了
    [switch]$Prerelease,
    # 不再上传 myembyhub-debug.apk 这个兼容用的旧资产名
    [switch]$NoLegacyAlias
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$apk = Join-Path $dist 'myembyhub.apk'
$legacyApk = Join-Path $dist 'myembyhub-debug.apk'
$manifest = Join-Path $dist 'version.json'
$built = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'

if (-not (Test-Path $built)) { throw "找不到构建产物：$built，先跑 gradlew :app:assembleRelease" }

# dist/ 里已经有一个旧包时**必须比时间**：只判「存在」会把上一次的包传上去，
# 而清单是这次生成的 → 电视装完还提示有新版（就是下面一致性校验拦的那种）。
if (-not (Test-Path $apk) -or (Get-Item $built).LastWriteTime -gt (Get-Item $apk).LastWriteTime) {
    Copy-Item $built $apk -Force
    Write-Host "已把构建产物同步到 $apk"
}
if (-not (Test-Path $manifest)) { throw "找不到清单：$manifest，先跑 gradlew :app:assembleRelease" }

# ── SDK 工具路径 ─────────────────────────────────────────────────────────
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = 'E:\Programs\Android\Sdk' }
$buildTools = Get-ChildItem -Path (Join-Path $sdkRoot 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^\d.]', '') } -Descending | Select-Object -First 1
$aapt = if ($buildTools) { Join-Path $buildTools.FullName 'aapt.exe' } else { $null }
$apksigner = if ($buildTools) { Join-Path $buildTools.FullName 'apksigner.bat' } else { $null }

# ── 发版前最后一道检查：清单里的下载地址必须是公网地址 ────────────────────
$json = Get-Content $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($json.url -match '^http://\d+\.\d+\.\d+\.\d+') {
    Write-Warning "version.json 里的 url 还是内网地址（$($json.url)）。"
    Write-Warning "在 local.properties 里写 updateRepo=$Repo 再重新打包，否则电视更新会下载失败。"
}

# ── 签名校验：必须是固定密钥签的、且不可调试 ─────────────────────────────
# 这两件事一旦漏掉，装到电视上的就是一个「换台电脑就断更」+「adb 能 run-as 读 token」
# 的包，而且**从 APK 外观上完全看不出来**。所以放在发版流程里强制拦。
$expectedSha256 = '94aef9a9550d4281657e8dcf0a4e005341b962464080e9d7e8146a095f99ae54'
if ($apksigner -and (Test-Path $apksigner)) {
    $verify = & $apksigner verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK 签名校验失败：`n$($verify -join "`n")" }

    $sha = [regex]::Match($verify -join "`n", 'SHA-256 digest:\s*([0-9a-f]{64})')
    if (-not $sha.Success) { throw "apksigner 没输出证书 SHA-256，无法确认签名身份" }
    if ($sha.Groups[1].Value -ne $expectedSha256) {
        throw ("这个 APK 不是用发布密钥签的（实际 $($sha.Groups[1].Value)）。" +
            "多半是 local.properties 里缺 storeFile/storePassword/keyAlias —— " +
            "补上后重新跑 gradlew :app:assembleRelease。见 MyEmbyHub-keys/README.txt")
    }
    Write-Host "  签名    : 发布密钥 ✅（v1/v2 均已校验）"
} else {
    Write-Warning "找不到 apksigner.bat（build-tools=$($buildTools.FullName)），跳过签名校验"
}

if ($aapt -and (Test-Path $aapt)) {
    $badging = & $aapt dump badging $apk 2>&1
    $dbg = $badging | Select-String -Pattern 'application-debuggable'
    if ($dbg) { throw "这个 APK 带 android:debuggable=true，是 debug 包，不能发版（adb 能 run-as 读走 token）" }
    Write-Host "  调试位  : 不可调试 ✅"

    # ── 清单与 APK 必须是同一次打包出来的 ──────────────────────────────
    # 两者版本号对不上时的表现极有迷惑性：电视装完包、再点检查更新，
    # 还是提示「发现新版本」—— 因为它拿清单里的 versionCode 去比，
    # 而那个数字比包里的大。看起来像更新坏了，其实只是清单和包错位。
    $badge = $badging | Select-String -Pattern "package: name='([^']+)' versionCode='(\d+)'" | Select-Object -First 1
    if ($badge) {
        $apkCode = [long][regex]::Match($badge.Line, "versionCode='(\d+)'").Groups[1].Value
        if ($apkCode -ne [long]$json.versionCode) {
            throw ("清单和 APK 不是同一次打包出来的：" +
                "version.json 说 $($json.versionCode)，APK 里是 $apkCode。" +
                "重新跑 gradlew :app:assembleRelease 再发。")
        }
        Write-Host "  校验    : 清单与 APK 版本一致（$apkCode）"
    } else {
        Write-Warning "aapt 读不出 APK 的 versionCode，跳过一致性校验"
    }
} else {
    Write-Warning "找不到 aapt.exe（build-tools=$buildTools），跳过 debuggable / 版本一致性校验"
}

# ── tag 命名：v<major.minor.yyyyMMdd>.<当天序号> ──────────────────────────
# 一天可能给电视推好几个版本，tag 只写到日期就会撞名。
# 序号以**远端**已有的 Release 为准：gh release create 是直接在远端建 tag 的，
# 本地仓库里一个 tag 都没有（除非你 fetch 过）。
if (-not $Tag) {
    # versionName 现在是 `1.0.yyyyMMdd.N`，tag 就是 `v` + versionName —— 一一对应，
    # 不用再查远端算序号（序号在打包时就定了，.version-seq 管着）。
    if ([string]$json.versionName -notmatch '^1\.0\.\d{8}\.\d+$') {
        throw "versionName 格式不认识：$($json.versionName)（期望 1.0.yyyyMMdd.N），请手动指定 -Tag"
    }
    $Tag = "v$($json.versionName)"

    # 撞名说明这个包已经发过了（重复跑脚本），直接报错比覆盖更安全
    $exists = $false
    try {
        & gh api "repos/$Repo/releases/tags/$Tag" 2>$null | Out-Null
        $exists = ($LASTEXITCODE -eq 0)
    }
    catch { $exists = $false }
    if ($exists) {
        throw "$Tag 已经存在（这个版本发过了）。要重发就先删掉旧 Release，或用 -Tag 指定别的名字。"
    }
    Write-Host "  tag     : $Tag（由 versionName 推导）"
}
elseif ($Tag -notmatch '^v\d+\.\d+\.\d{8}\.\d+$') {
    Write-Warning "tag 不符合约定格式（v1.0.20261117.1）：$Tag"
}

Write-Host "发布 $Tag"
Write-Host "  APK     : $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB)"
Write-Host "  版本    : $($json.versionName) / $($json.versionCode)"
Write-Host "  下载地址: $($json.url)"

$assets = @($apk, $manifest)
if (-not $NoLegacyAlias) {
    Copy-Item $apk $legacyApk -Force
    $assets = @($apk, $legacyApk, $manifest)
    Write-Host "  兼容    : 额外上传 myembyhub-debug.apk（老版本客户端烤的就是这个名字）"
}

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
