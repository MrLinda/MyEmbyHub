# 在线更新：GitHub Release 主源 + 国内镜像

这台电视装在客厅，改一次代码要走过去按遥控器，所以「能不能自己更新自己」不是锦上添花，
是日常开发循环的一部分。本文记清楚它怎么搭、为什么会踩坑。

> 相关代码：`app/src/main/java/work/luxiaoxiao/myembyhub/net/UpdateManager.kt`（取清单 / 下载 / 调安装器）、
> `app/src/main/java/work/luxiaoxiao/myembyhub/ui/UpdateUi.kt`（三段对话框）、
> `tools/publish-release.ps1`（发版）、`app/build.gradle.kts` 末尾生成 `dist/version.json` 的那段。

---

## 1. 为什么选 GitHub Release

| 方案 | 结论 |
|---|---|
| **GitHub Release** | ✅ 采用。发布有历史、有 tag、能写变更说明；仓库和包放在一处；`gh` 一条命令搞定 |
| 甲骨文服务器 | 手上在跑别的服务，不想让「发个 APK」和那些服务抢同一个入口；证书链虽然已经验通，但多一处要维护 |
| 局域网静态服务（`python -m http.server`） | 只留作调试，不作为交付路径 —— 出门在外就更新不了 |

Emby 服务器是别人的，跟更新链路无关，别混在一起。

---

## 2. 客户端要的两样东西

发版时把这两个文件上传到**同一个 Release**，资产名不能改：

```
myembyhub-debug.apk     ← 装到电视上的包
version.json            ← 版本清单
```

`version.json` 由 `gradlew :app:assembleDebug` 自动生成（`app/build.gradle.kts` 末尾）：

```json
{
  "versionCode": 1794918005,
  "versionName": "1.0.20261117.2",
  "url": "https://github.com/<owner>/<repo>/releases/latest/download/myembyhub-debug.apk",
  "size": 2174365,
  "notes": "TV 客户端：扫码登录 / 搜索 / 排序 / 在线更新"
}
```

`versionCode` 是**秒级时间戳**，永远递增 → 不会被系统以「版本号相同」拒绝覆盖安装。

`versionName` 是 `1.0.yyyyMMdd.N`，`N` 是**当天第几次打包**，从 1 起。
一天给电视推好几个版本是常态（改个焦点、调个间距都值得装一次看），
只写到日期就没法区分了。序号记在仓库根目录的 `.version-seq`（已 gitignore，
格式 `20261117=2`），只有真正跑 `assemble*` 时才自增 —— 跑单元测试、IDE 同步不吃号。
换台机器编号会从头开始，无所谓：决定「谁更新」的是 `versionCode`，`versionName` 只给人看。

---

## 3. 地址为什么不带 tag

```
https://github.com/<owner>/<repo>/releases/latest/download/version.json
```

`latest/download/<资产名>` 永远指向**最新一个正式 Release** 里的同名资产。
好处：

- 客户端里的默认地址是**固定值**，发多少个版本都不用改；
- 不用先调 GitHub API 查 tag（省一次请求，也不吃 API 速率限制）；
- 换 tag 命名习惯也不影响客户端。

⚠️ `releases/latest` **不包含 draft 和 prerelease**。打成 prerelease 的话，客户端看不到新版本 ——
`tools/publish-release.ps1` 默认发正式版就是为了这个。

---

## 4. 镜像加速（电视在国内）

`github.com` 和 `objects.githubusercontent.com` 在电视那边经常连不上，或者只有几 KB/s。
所以请求按下面的顺序试（`UpdateManager.MIRROR_PREFIXES`）：

```
https://ghfast.top/https://github.com/.../version.json
https://gh-proxy.com/https://github.com/.../version.json
https://ghproxy.net/https://github.com/.../version.json
https://github.com/.../version.json                 ← 直连兜底
```

这类站只是「反代 + 缓存」，把原始 GitHub 地址原样拼在后面。

**两条关键设计**：

1. **哪个前缀把清单取回来，下载 APK 就优先用同一个前缀**（`goodPrefix`）。
   否则会出现「清单走 ghfast 通了、APK 又回去直连 GitHub 卡死」这种诡异现象。
2. **非 GitHub 地址不套镜像**：用户自己填的 `http://192.168.x.x:8765/version.json`
   套上镜像只会把请求发到不相干的站点。`UpdateManager.candidates()` 有单测盯这条
   （`app/src/test/java/work/luxiaoxiao/myembyhub/net/UpdateCandidatesTest.kt`）。

镜像站来来去去（被封 / 改名 / 限速），所以做成常量列表而不是写死一个。
某个失效了，改 `MIRROR_PREFIXES` 重新发版，或在应用里改 `updateManifestUrl` 指到自定义地址。

---

## 5. 断点续传（不是可选项）

`tryDownloadOnce()` 按 `Range: bytes=N-` 续传，最多 `MAX_RESUME_ROUNDS = 6` 轮。

**为什么必须做**（实测证据）：

1. **模拟器的用户态网络会丢尾巴**。QEMU 的 SLIRP 在服务端 `close()` 时，如果接收缓冲里
   还有没被读走的数据，会直接发 RST 把它扔掉。日志：

   ```
   W/Update: 读流中断：已读 2174304 / 2174370 字节（unexpected end of stream）
   I/Update: 开始下载 …（第 2 轮，已有 2174304 字节）
   I/Update: 读流结束：共 2174370 / 2174370 字节
   I/Update: 下载完成 …/myembyhub-update.apk
   ```

   **只差 66 个字节**，但 Android 的 `FixedLengthInputStream` 认为这是
   `IOException: unexpected end of stream`，整个下载被判失败。
   换成 `Range: bytes=2174304-` 再要一次，尾巴就到了。

2. **镜像站限速会中途掐断**。ghfast / gh-proxy 这类站传一半就断，
   重头下 2MB 纯浪费。

3. 服务器不认 `Range`（回 200 而不是 206）时退化成重头下载。
   GitHub 的 `objects.githubusercontent.com` 和各家镜像都支持 206，走不到那条路。

> ⚠️ 调试这条链路时**必须用 `tools/serve-range.py`，不能用 `python -m http.server`** ——
> 后者不支持 Range，续传会被退化成「每次都从头下、每次都差几十字节、永远失败」，
> 看起来像客户端 bug。

---

## 6. 一处放宽的校验（以及它为什么不危险）

更新链路的 HTTPS **用 `TrustMode.ALL`**（信任任意证书），而不是 `BUNDLED`。

原因：镜像站的证书五花八门（Let's Encrypt / ZeroSSL / 有的干脆自签），
内置锚点覆盖不全，硬校验的结果是「更新时通时不通」—— 恰恰在最需要更新的时候挂。

为什么可以放宽：

- 这条链路**不传任何凭据**，只有一个公开 JSON 和一个公开 APK；
- 下载下来的 APK 还要过**系统安装器的签名校验** ——
  签名对不上的包**装不上**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
  所以中间人顶不掉这个 App。

⚠️ 但这意味着**不要把这条链路用来传密钥**。将来真要在更新里带敏感数据，得换成固定证书校验。

---

## 7. 发版流程

一次性准备：

```bash
# ① 建仓库（私有也行，但 Release 资产对私有仓库要带 token → 客户端拿不到，建议 public）
# ② 装 GitHub CLI 并登录
gh auth login

# ③ 让打包时知道仓库地址：local.properties（已 gitignore）
echo "updateRepo=MrLinda/MyEmbyHub" >> local.properties
```

之后每次发版：

```bash
set JAVA_HOME=E:\Programs\Android\Android Studio\jbr
gradlew :app:assembleDebug                       # 生成 dist/version.json，版本号自增
pwsh tools/publish-release.ps1 -Repo MrLinda/MyEmbyHub -Notes "修复扫码登录"
```

`-Tag` 不用写：脚本直接用 `v` + `versionName`，也就是 `v1.0.20261117.3` 这种。
两者一一对应，不会出现「Release 叫 A、装上去的包叫 B」。

脚本会：同步 APK 到 `dist/` → 检查清单里的地址是不是还是内网 → 校验清单与 APK 的
`versionCode` 一致 → 用推导出的 tag 调 `gh release create` 上传两个资产。
tag 已经存在说明这个包发过了，脚本直接报错（重发请先删旧 Release 或用 `-Tag` 另起名）。

> ⚠️ **只判「`dist/` 里有没有 APK」是不够的**：里面留着上一次的包、清单却是这次生成的，
> 传上去就是「装完还提示有新版」。所以脚本会比对
> `app/build/outputs/apk/debug/app-debug.apk` 与 `dist/myembyhub-debug.apk` 的修改时间，
> 构建产物更新就覆盖同步。

⚠️ 打包时没给 `updateRepo`，`version.json` 里的 `url` 会是
`http://<updateHost>/myembyhub-debug.apk`（局域网调试用）—— 这种包**别发 Release**，
电视在别的网络下下载会失败。脚本会对此 `Write-Warning`。

### 本仓库的实际状态（2026-11-17）

- 仓库：<https://github.com/MrLinda/MyEmbyHub>（public）
- 已发布：`v1.0.20261117.1`，资产 `myembyhub-debug.apk` + `version.json`
- 客户端取清单的实际地址：
  <https://github.com/MrLinda/MyEmbyHub/releases/latest/download/version.json>
- 本机 `local.properties` 里已写 `updateRepo=MrLinda/MyEmbyHub`，
  以后直接 `gradlew :app:assembleDebug` 生成的就是公网地址。

> 本机 curl 有个坑：不带 `--ssl-no-revoke` 会报
> `schannel: ... CRYPT_E_NO_REVOCATION_CHECK`（本机证书栈查不了吊销列表），
> 那不是仓库的问题。验证下载链路时加上这个参数即可。

---

## 8. 电视上那一步绕不过去

下载完调 `Intent.ACTION_VIEW` 拉起系统安装器，**必须有人按一次「安装」**。

原因（`AGENTS.md` 第 5 节记着）：乐视禁了 `adb root`，shell 用户 uid=2000 没有
`INSTALL_PACKAGES` 权限，`adb install` 会被 `SecurityException` 拒。

由此带来的使用体验问题，得认：

- **下载成功但用户没按「安装」**，下次检查更新还是提示有新版 —— 这**不是 bug**；
- 系统安装器装完会把源 APK 从 `/sdcard/Download/` **删掉**（logcat 里 `delete done`），
  「下载的文件凭空消失」是正常现象；
- 装完第一次启动会慢（new dex 要 dexopt），别当成卡死。

---

## 9. 排查清单

| 现象 | 先看哪里 |
|---|---|
| 「检查更新失败」+ 所有地址 | `adb logcat -s Update` —— 每个候选的失败原因都打了 `W/Update` |
| **「怎么一直提示有更新」** | 先比两个数字：`adb shell dumpsys package work.luxiaoxiao.myembyhub \| findstr versionCode` 和 `dist/version.json` 里的 `versionCode`。见下面第 9.1 节 |
| 下载到一半失败 | 镜像限速会返回半截内容；`tryDownloadOnce` 里有 `Range` 续传（最多 6 轮），日志里能看到「第 N 轮，已有 X 字节」。一直续不上说明这个地址真不通，换下一个 |
| 「下载失败：无写权限」 | `WRITE_EXTERNAL_STORAGE` 没声明，或 `/sdcard` 没挂载。`resolveDownloadDir()` 会依次试 `/sdcard/Download` → 应用外部私有目录 → 应用内部目录，日志里 `下载目录 …（来源）` 会说明落在哪 |
| 模拟器上更新装不上 | 模拟器 `/storage/sdcard` 是只读的（`dr-xr-xr-x`），下载会落到 `files/update`。这个目录通过 `makeReadableForInstaller()` 放开成 0755/0644 后**系统安装器确实能装上**（已验证：`App installed.`） |
| 清单能取、APK 下不动 | 大概率是「镜像通过、直连 GitHub 卡住」。日志里看 `开始下载 <url>` 用的是什么前缀 |

### 9.1 「一直提示有更新」的三个成因

按可能性从高到低：

**① 每打一次包就是一个新版本（最常见，而且不是 bug）**

`versionCode` 是**秒级时间戳**（`app/build.gradle.kts` 里
`(System.currentTimeMillis() / 1000).toInt()`）。所以只要重新跑过一次
`gradlew :app:assembleDebug`，`dist/version.json` 里的版本就比电视上装的新 ——
哪怕代码只改了一行、甚至什么都没改。

调试期反复打包时，检查更新会一直报「有新版本」，这是设计使然。
不想看到的话，把 `dist/version.json` 里的 `versionCode` 手动改成和电视上一样即可
（只影响「要不要提示」，不影响别的）。

**② 清单和 APK 不是同一次打包出来的（真正的死循环）**

`version.json` 说 100，`myembyhub-debug.apk` 里其实是 99 —— 那么装完之后再检查，
拿 100 去比 99，**还是提示有更新**。用户看到的就是「更新了无数次还是提示更新」，
而且没有任何报错。

成因：手工改过 `version.json`、从别处拷了个 APK 进 `dist/`、或者只重打了其中一个。

现在 `tools/publish-release.ps1` 会用 `aapt dump badging` 读 APK 的 `versionCode`
和清单比对，不一致直接 `throw` 中止发版。

> 为什么不在客户端下载完就校验？试过 —— Android 4.3 的
> `PackageManager.getPackageArchiveInfo()` 对这类 APK 返回 **null**
> （解析不出来），拿不到版本号。所以校验放在发版环节（PC 上有 aapt，可靠）。

**③ 读不到当前版本号（已加固）**

`UpdateManager.currentVersionCode()` 先反射读 `BuildConfig.VERSION_CODE`，
失败时**兜底改用 `PackageManager` 读已安装 APK 的 `versionCode`**。
以前这里失败会返回 0 —— 任何版本都比 0 大，于是永远提示有更新，
`AGENTS.md` 里记的「BuildConfig 被 R8 优化掉」就是这条。
