# AGENTS.md — MyEmbyHub

Emby 电视客户端项目。**主战场是一台乐视 S50 Air 智能电视**（Android 4.3 / API 18），
所有技术选型都被这台机器的能力上限锁死。动代码前先读本文件，再读
[docs/tv-device-report.md](docs/tv-device-report.md)（电视实测底细）。

> ⚠️ **本文件（以及整个仓库）里的服务器域名和 IP 都是占位符**：
> 为了能公开发布，真实的服务器域名、电视/电脑的内网 IP 全部替换成了
> `example.com` / `192.168.1.x`。本机日常要用的真实地址在
> **`AGENTS.local.md`**（同一份内容的私有副本，已 gitignore，不进仓库）。

---

## 1. 目标设备：乐视 S50 Air（硬约束）

| 项目 | 实测值 | 影响 |
|---|---|---|
| 系统 | **Android 4.3 / API 18** | minSdk 最高只能 18 |
| 机型 | Letv S50 Air，`full_mstarnapoli` / `mstarnapoli`，BOARD `BD_MST181B_D01A` | — |
| 固件 | V2202RCN02C080080B07241S，**userdebug + test-keys** | 有 adbd，但不可 root |
| 屏幕 | 1920×1080 @60Hz，density 1.5 (240dpi) → 1280×720 dp | — |
| 内存 | 1512MB 物理，**应用堆上限约 96MB** | 图片/缓冲必须抠着用 |
| 存储 | 内外各约 3.3GB 可用 | — |
| CPU | armeabi-v7a / armeabi，MStar（MSTARYA/napoli）方案 | 解码能力靠实测定 |
| **WebView** | **没有** | 不能网页套壳，必须原生 UI |
| **GMS / Play 商店** | **都没有** | 不能依赖任何 Play Services 库 |

### 被否掉的方案（别再尝试）

- **Compose** —— 要求 minSdk 21，这台机器装不上。UI 只能走 XML/Views。
- **media3 / ExoPlayer 3** —— 要求 minSdk 21。用 **ExoPlayer 2.19.1**（最后支持 API 18 的版本）。
- **androidx.tv / tv-material** —— 要求 minSdk 21，用不了，焦点导航要自己实现。
- **WebView 承载 Emby 网页** —— 设备根本没有 WebView。
- **依赖 GMS/Firebase** —— 设备没有。

---

## 2. 网络与 TLS（最容易踩坑的地方）

**目标服务器**：`https://tv.example.com`（公网，Let's Encrypt 证书）。实测（openssl 3.2）：
- 服务器**只协商出 TLS 1.2**，且首选 `ECDHE-RSA-CHACHA20-POLY1305`
  → **ChaCha20 是 Android 4.4+ 才有的套件，4.3 用不了**，得靠对端回落到 AES 套件。
- 证书链是 **Let's Encrypt 2024 新链**：`example.com → Let's Encrypt YR2 → ISRG Root YR → ISRG Root X1`
  → **ISRG Root YR 是 2024 年的根，Android 4.3 那套 2013 年的 CA 库绝对没有**。

由此得出三条硬性要求：

1. Android 4.3 **默认只启用 TLS 1.0**，必须显式开启 1.1/1.2。
   `app` 的 `net/TlsStack.kt` 里已实现 `forceTls12`（包一层 `SSLSocketFactory` 抬高 enabledProtocols）。
2. **必须自带锚点证书**。`app/src/main/assets/` 已内置 `isrg_root_x1.pem`、
   `isrg_root_yr.pem`、`letsencrypt_yr2.pem`，由 `BundledCa.kt` 手写逐级验签
   （不用 KeyStore，老系统上 KeyStore 类型支持参差不齐）。
3. ~~如果 4.3 连 AES 套件都协商不出来，就得上 Conscrypt~~
   → **已排除**。2026-11-03 真机实测：4.3 的系统 JSSE 用 TLSv1.2 + 内置锚点
   **握手成功**（`TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`）。
   **不需要 Conscrypt，也不需要 Bouncy Castle。**
   （2026-11-18 补充：Conscrypt 后来**还是接进来了**，但目的不是救这台服务器，
   而是救「只开 GCM 套件的新式服务器」——见第 6 节「TLS 实现」。
   系统 JSSE 那条路一条都没删，仍是兜底。）
4. **必须带 SNI**（2026-11-09 新增，见第 6 节「SNI 缺失」）。

> ⚠️ **测服务器时务必先确认本机 DNS 有没有被 Clash 劫持**：
> 本机跑着 Clash Verge（fake-IP 模式），`tv.example.com` 会解析成 `198.18.0.206`，
> 所有 `openssl s_client tv.example.com:443` 测的都是代理出口，不是电视要连的那台机器。
> 正确做法：用 DoH 查真实 IP，再 `s_client -connect <真实IP>:443 -servername tv.example.com`。
> 详见 `docs/tv-device-report.md` 第 5.3 节。
>
> 另：服务器**最低要 TLS 1.1**（TLS 1.0 会被回 `tlsv1 alert protocol version`），
> 且**不挑 cipher** —— 这两条放宽了设计约束。
> ⚠️ 但**「不要求 SNI」只对当时那台 openresty 后端成立**：
> 换成挂在 CDN（Cloudflare）前面的源站后，**没有 SNI 会被直接拒握手**。

自签名证书同理，客户端必须显式信任。

> ⚠️ **换一台服务器就连不上？先查它支不支持 CBC 套件**（2026-11-17 实测）：
> Android 4.3 的 JSSE **只有 CBC 套件，没有 GCM/AEAD**。所以哪怕证书链完全正确，
> 只要对端 nginx 只开 GCM（现代默认配置就这样），4.3 上永远是
> `SSL handshake aborted`，客户端只能报一句「安全连接失败，检查服务器地址和网络」——
> **看着像证书问题，其实是套件谈不拢**。
>
> 一分钟判定（在任意一台 Linux 上打，不要在本机打，本机 Clash 会劫持 DNS）：
> ```bash
> # 4.3 能用得上的那类套件；回 "New, ... Cipher is ..." 就是支持
> echo | openssl s_client -tls1_2 -cipher 'ECDHE-ECDSA-AES128-SHA' \
>   -connect <服务器>:<端口> -servername <域名> 2>&1 | grep -E '^New,|alert'
> ```
> 实测对照：老服务器 `tv.example.com:443` → `New, TLSv1.0, Cipher is ECDHE-ECDSA-AES128-SHA`（能登）；
> 新服务器 `<新服务器>:<端口>` → `alert handshake failure`（登不上）。
>
> 修法在服务端，把 CBC 加回 `ssl_ciphers`：
> ```nginx
> ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-SHA:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-SHA;
> ssl_prefer_server_ciphers on;
> ```
> 客户端救不回来 —— 4.3 的 JSSE 里根本没有 GCM 实现，除非整包换 Conscrypt。
>
> ✅ **2026-11-18 已换**：`app` 现在内置 Conscrypt 2.5.2（见第 6 节「TLS 实现」），
> 只开 GCM 的服务端也能连上了。上面那段服务端 nginx 改法仍然有用
> （老客户端 / 别的设备还要靠它），但**不再是本 App 的前提条件**。

---

## 3. 模块结构

仓库只有一个模块：

| 模块 | 语言 | minSdk | 作用 |
|---|---|---|---|
| `app` | Kotlin + XML/Views | 18 | **正式 TV 客户端**（已改造并装到电视上）：登录/媒体库/播放/搜索/排序/在线更新 |

`app` 模块的 UI 是 XML/Views（**不是 Compose**，Compose 要 API 21），
网络层在 `app/src/main/java/work/luxiaoxiao/myembyhub/net/`：
`TlsStack` / `BundledCa` / `UrlKit` / `EmbyHttpDataSource` / `TrustMode` /
`EmbyApi` / `Session` / `QrUtil` / `UpdateManager` / `ImageLoader` / `PhoneControlServer` /
`DeviceReport`（设备反馈：采集 + 上报）/ `PlaybackSnapshot`（最近一次播放的内存快照 + 错误环形缓冲）/
`Udf` / `IsoPlayback`（光盘镜像直放）。

> 历史上还有两个探针模块：`tvcheck`（TLS 协商 / 播放解码 / 5102 远程配置）和
> `tvprobe`（设备自检，零依赖 Java）。真机验证全部通过后已删除，
> `tvcheck` 里唯一长期有价值的 `UrlKit` 回归测试已迁到 `app/src/test/`。

`dist/` 是交付产物目录（APK、在线更新清单 `version.json`）；`tools/` 是辅助脚本。

> **在线更新走 GitHub Release**（主源）+ 国内镜像前缀加速，细节见
> [docs/online-update.md](docs/online-update.md)。发版：
> `pwsh tools/publish-release.ps1 -Repo <owner>/<repo> -Notes "改了什么"`。
> `-Tag` 一般不用写：脚本用 `v` + `versionName`，也就是 `v1.0.20261117.3`。
> 打包时用 `local.properties` 里的 `updateRepo=<owner>/<repo>` 决定清单里的下载地址；
> 不填则退回 `updateHost` 指向的局域网静态服务（只用于调试，别发 Release）。

> **发版用 `:app:assembleRelease`，不是 `assembleDebug`**（2026-11-19 改）。
> 发布包是**固定密钥签名的正式包**，资产名 `myembyhub.apk`，不再带 `-debug` 后缀、不可调试。
> 密钥在仓库外：`../MyEmbyHub-keys/myembyhub.jks`，口令在同目录 `STORE-PASSWORD.txt`，
> `local.properties` 里用 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 指向它。
> 换密钥 = 所有已装设备都装不上新包（必须卸载重装），所以**这把钥匙不能换、要备份**。

> ⚠️ `gradlew :app:assembleDebug` **只生成 `dist/version.json`，不会把 APK 拷进 `dist/`** ——
> 打包后要手动同步（`tools/publish-release.ps1` 会自动补这一步），
> 否则电视在线更新会下载到旧包。
> 在线更新清单里的 `versionCode` 必须是**秒级时间戳**，永远递增才能覆盖安装。
> （实测：乐视 4.3 的安装器**允许同 versionCode 覆盖安装**，`status=0`，
> 递增只是保险做法，不是这台机器上的硬性要求。）
>
> ⚠️ **系统安装器装完会把源 APK 从 `/sdcard/Download/` 删掉**（logcat:
> `delete done`）→ 下载完的文件「凭空消失」是正常现象，不是下载失败。
> 手动 `adb push` 的 `myembyhub.apk` 同理，装完就没了。

---

## 4. 常用命令

```bash
# 构建（用 Studio 自带 JBR，JDK 25）
set JAVA_HOME=E:\Programs\Android\Android Studio\jbr
gradlew :app:assembleDebug                        # 调试包（会自动生成 dist/version.json）
gradlew :app:assembleRelease                      # **发版用这个**：固定密钥签名的正式包
gradlew :app:assembleDebug -PupdateHost=1.2.3.4:8765   # 把更新源烤进清单
gradlew :app:testDebugUnitTest                    # UrlKit 的 URL / 重定向解析回归测试（不需要电视）

# 电脑端静态服务（给电视在线下载 APK 用）
cd dist && python -m http.server 8765 --bind 0.0.0.0
# ⚠️ 端口 8000 落在 Windows 保留段 7928-8027，绑定报 WinError 10013 → 统一用 8765
```

> `gradle.properties` 里 **configuration cache 是关闭的**：`app` 每次打包要在配置期算
> 时间戳版本号，configuration cache 会把配置期的值缓存住，导致 versionName 不刷新。

### 4.1 真机验证方式

这台电视 `screencap` 输出 0 字节、shell 无 DUMP 权限，**没法自动取界面截图**。
所以验证靠这几招：

```bash
# 1) 拉起界面（不用装新包就能复现已装版本的行为）
adb shell am start -n work.luxiaoxiao.myembyhub/.ui.HomeActivity

# 2) 看日志（崩溃、异常都从这里抓）
adb logcat | Select-String -Pattern 'myembyhub|AndroidRuntime|FATAL'

# 3) 看界面层级（代替截图：能拿到文本、bounds、selected 状态）
adb shell uiautomator dump /data/local/tmp/w.xml
adb pull /data/local/tmp/w.xml dist/
```

> ⚠️ **模拟器上截图能取到，真机上不行** —— UI 改动先在模拟器上截图看效果，
> 再推真机用 `uiautomator dump` 验层级。
> 解析 bounds 要用 `[regex]::Match($a.bounds,'\[(\d+),')`，
> 用 `-replace '\D',' '` split 取 index 0 会拿到空串导致误判。
>
> ⚠️ **`uiautomator dump` 会被 OOM Killer 干掉**（2026-11-17 实测）：电视内存吃紧时
> 它起不来，`adb shell "uiautomator dump /data/local/tmp/w.xml 2>&1"` 只回一个字 `Killed`，
> 文件根本不生成，`adb pull` 报 `remote object ... does not exist`。
> → 看着像「命令写错了 / 路径不对」，其实是内存不够，**别去改路径、别去建目录**。
> → 先 `adb shell "ps | grep myembyhub"` 看进程在不在，再决定要不要放弃 dump 改成人工看。
>
> ⚠️ **壳里没有 `tail` / `grep` / `head`**（这台 ROM 的 toybox 裁得很狠）：
> `adb shell "df /sdcard | tail -2"` 报 `tail: not found`；
> `pm list packages | grep xxx` 静默返回空（看起来像「没装」，其实是 grep 不存在）。
> → 过滤一律在**本机 PowerShell 里做**（`& $adb shell "pm list packages" | Select-String xxx`）。
>
> ⚠️ **`am start` 必须带 `--user 0`**：不带会报
> `SecurityException: startActivity asks to run as user -2 but is calling from user 0`。
> **`am force-stop` 直接没权限**：`Permission Denial: forceStopPackage() ... requires
> android.permission.FORCE_STOP_PACKAGES` → 想重启 App 只能靠人按遥控器退出，
> 或者 `am start` 到另一个 Activity 顶掉。
>
> ⚠️ `adb shell input keyevent` **无法操作别的应用的窗口**：
> `input` 是从 shell 进程（uid 2000）注入，目标是别的 uid（如系统安装器 uid 10014）时会报
> `SecurityException: Injecting to another application requires INJECT_EVENTS permission`。
> → **弹系统安装器/系统对话框时只能靠人按遥控器**，脚本按键无效。
> → 但对本 App 自己的界面，`input keyevent` / `input touchscreen swipe` 是可以用的。

---

## 5. 部署到电视的流程

电视没有网络 adb 的历史误解已澄清：**adb over TCP 5555 一直是开着的**
（`192.168.1.100:5555`），只是别用高并发+短超时去扫网段（会把 adbd 的 SYN backlog 挤爆）。

```bash
adb connect 192.168.1.100:5555
```

但乐视禁掉了 `adb root`，shell 用户 uid=2000 **没有 INSTALL_PACKAGES 权限**，
所以 `adb install` / `pm install` 会被 `SecurityException` 拒。可行路径：

```bash
# 1) 推送
adb push dist/myembyhub.apk /sdcard/Download/myembyhub.apk
# 2) 调起系统安装器（电视上需要人按一次「安装」）
adb shell am start --user 0 -a android.intent.action.VIEW \
  -d 'file:///sdcard/Download/myembyhub.apk' -t 'application/vnd.android.package-archive'
```

**装上带「在线更新」的版本之后就不用再手动推了**：电脑起 `python -m http.server 8765`，
电视上「在线更新」填 `http://192.168.1.50:8765/myembyhub.apk` 即可。

---

## 6. 这台机器上暴露出来的平台坑（务必记住）

- **Android 4.3 的 `java.net.Socket` 没有实现 `java.io.Closeable`** → Kotlin 的 `socket.use {}`
  在真机上抛 `ClassCastException`，而桌面 JVM 上单元测试全绿。Socket 一律手写 try/finally。
- `DisplayMetrics.densityDpi`(API 17)、`Build.SERIAL`(19)、`Build.VERSION.SECURITY_PATCH`(23)、
  `ApplicationInfo.minSdkVersion` 在老系统上访问会抛 `NoSuchFieldError` → **一律走反射**。
- `SSLSocket.getProtocol()` / `getCipherSuite()` / `SSLSocketFactory.getSupportedProtocols()`
  在公开 SDK stub 里是 `@hide`，编译期引用不到 → **只能反射**。
- `screencap` 在这台 ROM 上输出 0 字节（截图不可用）。
- `dumpsys activity` / `dumpsys window` / **`dumpsys package`** 都需要 DUMP 权限，shell 没有
  （`Permission Denial: can't dump ActivityManager ... without permission android.permission.DUMP`）→
  判断状态用 `pm path`、`ls -l /data/app/...`、`ps`、`logcat`。
- **`run-as` 在这台 ROM 上是坏的**：`run-as <包名> ...` 一律回
  `run-as: Package '<包名>' is unknown`，哪怕 `pm path` 能查出包、`/data/data/<包名>` 也在。
  → 想看 App 的 SharedPreferences / 内部文件（比如确认有没有登录态），这条路走不通，
  只能让 App 自己把状态显示在界面上，或者靠服务端/日志推断。
- **`adb shell input keyevent` 无法操作别的应用的窗口**：`input` 是从 shell 进程（uid 2000）注入，
  目标是别的 uid（如系统安装器 uid 10014）时会报
  `SecurityException: Injecting to another application requires INJECT_EVENTS permission`。
  → **弹系统安装器/系统对话框时只能靠人按遥控器**，脚本按键无效。
- adb 装包后想确认版本，用 `ls -l /data/app/<包名>-1.apk` 看大小和时间戳是否变了
  （`pm path` 给路径），这是绕过 DUMP 权限最实用的办法。
- 电视内存极紧张：`ActivityManager-Killer` 频繁触发，各进程 heap 只有 4~13MB，APK 别太大。
- **后台线程碰 View 会抛 `CalledFromWrongThreadException`**：配置服务的回调跑在 socket 线程上，
  直接 `editText.setText()` 会崩，而且异常会把 HTTP 响应吞掉，浏览器只看到 `ERR_EMPTY_RESPONSE`。
  → 所有 UI 更新必须 `runOnUiThread` / `Handler.post`。
- `SSLSocketFactory.getDefault()` 在 Android stub 里返回 `SocketFactory`，要强转。
- ExoPlayer 2.19.1 的 `AnalyticsListener` 是 **interface**（`object : AnalyticsListener { }`，无括号），
  回调名是 `onVideoDecoderInitialized`；`VideoSize` 的包名是 `com.google.android.exoplayer2.video.VideoSize`；
  `onDroppedVideoFrames` 第三个参数是 Long。
- **别包装 `SSLSocket`！** 包装后若不转发 `connect()`，被包的 delegate 根本没连上，
  握手会以 `Broken pipe` 失败（4.3 真机实测）。
  正确做法：**只包装 `SSLSocketFactory`**，在 `createSocket()` 里对**真 socket**
  设 `enabledProtocols` 后**原样返回**。
- ✅ 修好后 `HttpsURLConnection` + 这个 factory 在 4.3 上**完全可用**（真机 HTTP 200）。
- ⚠️ 但 `HttpsURLConnection` 遇 401 且服务端不给 `WWW-Authenticate` 头时，
  会在 `getResponseCode()` 抛 `IOException: No authentication challenges found`，
  响应体读不到。Emby 正好不发这个头 → **必须手工兜住**，
  否则「密码错」会被误判成「网络失败」。
- ⚠️ **ExoPlayer 2.19.1 的 `DefaultHttpDataSource` 不接受外部传入的 socket factory**
  → 播放 HTTPS 源仍会失败，需要自定义 `DataSource`
  （ExoPlayer 2.19.1 **不带 OkHttp**，依赖树里没有）。
- ✅ **播放取流会 302 到 `v10.example.com` / `v11.example.com`**（纯文件后端，API 路径一律 403）。
  **后端有多个分流节点，域名会变** → 客户端必须动态跟随 `Location`，绝不能硬编码。
  ExoPlayer 不跟重定向 → `DataSource` 必须自己读 `Location`、重建连接、重发请求。
  证书是同一张 `CN=example.com`，内置锚点照样能验；4.3 协商出 CBC 套件（GCM 不支持，会自动回落）。
- ✅ **网络实播已验证通过**（2026-11-03 19:33）：自写 DataSource 取流 →
  ExoPlayer 2.19.1 → `OMX.MS.AVC.Decoder`（MStar 硬解）→ 1080p 上屏，**零丢帧**。
  详见 `docs/tv-device-report.md` 5.7 节。
- ⚠️⚠️ **SNI 缺失 —— 换了 CDN 源站就只剩「接口能通、取流全挂」**（2026-11-09 实测）：
  - `SSLSocketFactory.createSocket()` 建出来的 socket **ClientHello 里没有 SNI**。
    普通后端不 care，但**挂在 Cloudflare 前面**的源站**没有 SNI 就直接拒握手**。
  - **症状极有迷惑性**：`HttpsURLConnection`（业务接口那条路）**自带 SNI**
    （libcore 内部调 `OpenSSLSocketImpl.setHostname()`）→ **接口全部正常**，
    只有自写 DataSource 取流挂；错误是
    `SSL handshake aborted` + `SSL3_GET_RECORD:wrong version number`，
    看起来像「证书不对 / 服务器只支持 TLS 1.3 / 代理拦截」，其实只是**少了个 SNI**。
  - **修法**：`TlsStack.applyServerName(socket, host)` ——
    - API 24+：`SSLParameters.setServerNames(SNIHostName)`（公开 API）；
    - **API 18**：`javax.net.ssl.SNIHostName` 这个类**根本不存在**，
      但 `org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl` 上有个
      **隐藏方法 `setHostname(String)`** —— 4.3 上设 SNI 的唯一入口，只能反射调。
    - ⚠️ 写反射时**不能把 `SNIHostName` / `SSLParameter` 写进类型声明** ——
      那两个类 API 24 才有，4.3 上类都加载不到，字节码校验阶段就炸；
      全程用 `Any` + `Class.forName`。
  - **注意**：`createSocket(host, port)` 在 4.3 上**也带不了 SNI**（实测同样被拒），
    只能靠上面的 `setHostname`。
  - 验证方式：在 4.3 上跑一段小程序，对比「带 / 不带 SNI」两种握手，
    带 SNI 成功、不带被拒 —— 一眼就能定位。
- ⚠️ **手写 HTTP 的四个坑（全部真机踩过，`cmd=httpprobe` 可复现）**：
  - **绝不能用 `java.net.URL(URL, String)` 解析重定向**：Android 4.3 的 libcore 会把
    base 的 query 重复拼到绝对 URL 后面（`.../stream?a=b?a=b`）→ 404。
    同样别指望 `android.net.Uri.resolve()`——**它压根没有这个方法**。
    → 播放层已改为**手工切分 URL**（`UrlKit.parse` / `UrlKit.resolve`，有单元测试兜底）。
  - **路径必须带前导斜杠**。`GET Videos/666/stream` → openresty 回 **400 Bad Request**。
  - **逐字节读完 `\r\n\r\n` 后不要 unread**。socket 流的位置天然就在 body 开头；
    若把头结束符当 body 前 4 字节推回去，ExoPlayer 收到的第一个字节是 `0D 0A` 而不是
    `ftyp` → 十五个 extractor 全部拒绝 → `PARSING_CONTAINER_UNSUPPORTED`。
  - **`DataSource.Factory` 必须每次返回新实例**。ExoPlayer 会并发用多个 DataSource
    （嗅探 + 正式读取），有状态实例共用一个 socket 会让字节流错乱。
  - `Host` 头带不带默认端口（`:443`）**都可以**拿到 206 —— 之前以为带端口会 404，是误判。
- ⚠️ **206 的 `Content-Length` 只是本次 range 的长度**，总长在 `Content-Range` 里。
  不回填给 ExoPlayer 的话，媒体总长 / 时长 / 进度条全错。
- ⚠️ **Emby 的 item id 是 6 位数**（如 `765503`），取流要带
  `?static=true&mediaSourceId={MediaSources[].Id}`；`X-Emby-Token` 跨 302 依然有效。
- ⚠️ ExoPlayer 2.19.1 的 `DataSource` 接口签名：
  `open(dataSpec): Long`（返回**总长度**，不是 void）；
  `getResponseHeaders()` 返回**非空** platform type → Kotlin override 要写
  `Map<String, List<String>>`（**不能带 `?`**，否则编译报 "not a subtype"）。
- ⚠️ `DataSource` 要**保留服务器返回的原始头名大小写** —— ExoPlayer 按
  `"Content-Length"` 精确匹配，转小写就找不到。
- ⚠️⚠️ **模拟器（`LeTV` AVD / `sdk_x86` / API 18）不是「能不能播」的判据 —— 别拿它当代码 bug**（2026-11-16 实测）：
  - `/system/etc/media_codecs.xml` 里视频解码器**只有 `OMX.google.h264.decoder`**
    （外加 `mpeg4` / `h263` / `vpx`），**没有 HEVC、没有 AV1、没有任何硬解**。
  - 这个 4.x 时代的 AOSP 软解器**只吃 Baseline profile**：库里 1080p High（`avc1.640029`）直接
    `D/MediaCodecInfo: NoSupport [codec.profileLevel, avc1.640029, video/avc]` →
    `E/ACodec: [OMX.google.h264.decoder] ERROR(0x80001001)` →
    `ExoPlaybackException: MediaCodecVideoRenderer error, format_supported=NO_EXCEEDS_CAPABILITIES`。
    → **跟转码、码率、DataSource、解码参数全都无关，是镜像本身解不了。**
  - 对照实测：ffmpeg 生成的 **1280×720 Baseline H.264** 能完整播到 `00:20 / 00:20`、零报错
    （截图全黑是这台老模拟器**不抓 SurfaceView 层**，不代表没画面）。
  - → **「转码 / PlaybackInfo / TranscodingUrl」这条链路在模拟器上也无法验证**：
    上游没有可用的 HEVC 解码器，服务端转了也看不出对错。要验播放只能上真机
    （MStar 硬解 `OMX.MS.AVC.Decoder` / `OMX.MS.HEVC.Decoder`，1080p 零丢帧已验）。
  - → 只在模拟器上验 UI / 焦点 / 取数时，用本地 Baseline 片跑通播放器外壳即可：
    ```
    python tools/serve-range.py 8765 dist        # ⚠️ 必须用这个，见下
    adb shell am start -n work.luxiaoxiao.myembyhub/.ui.PlayerActivity \
      --es url 'http://10.0.2.2:8765/t.mp4' --es title LocalTest \
      --es item_id local --es media_source_id local --el start_ms 0
    ```
  - ⚠️ **`python -m http.server` 不支持 Range**：ExoPlayer 发 `Range: bytes=N-`，它老实地回 200 + 整个文件，
    客户端发现对不上就报 `ExoPlaybackException: Source error` —— 看起来像解码失败，其实是服务端不会分片。
    `tools/serve-range.py` 是带 Range（206 + `Content-Range`）的替代品。
- ⚠️ **`MediaCodecList` 的静态方法和实例方法不是一回事**（设备反馈踩的）：
  `MediaCodecList(ALL_CODECS).codecCount` / `.getCodecInfoAt(i)` 是 **API 21** 才加的**实例**方法，
  4.3 上根本没有 → 清单永远是 0 条，但 `createDecoderByType` 探测却跑得通，
  症状是「解码器列表空着、探测结果有」，很容易误判成数据没存上。
  正解：反射**静态**方法 `MediaCodecList.getCodecCount()` / `MediaCodecList.getCodecInfoAt(i)`
  （这两个在现代 SDK 里被标 @hide，编译期引用不到）。
  另：`isHardwareAccelerated` / `isSoftwareOnly` 都是 API 29+，4.3 上反射必失败 →
  用名字前缀判（`OMX.google.*` = 软解，`OMX.<厂商>.*` = 硬解）。
- ⚠️ **`TvDialog.message/confirm` 的参数顺序不能随手排**：Kotlin 尾随 lambda 绑的是**最后一个**参数。
  全工程有 3 处 `confirm(...) { 退出登录 / 下载更新 }` 的写法，
  把 `onDismiss` 放到 `onConfirm` 后面会让那些确认逻辑变成「关闭回调」——
  点「退出」毫无反应、点「下载」什么都不发生，而编译期一点错都不报。
  → `onConfirm` 必须留在参数表最后；要传 `onDismiss` 一律写命名参数。
- ⚠️ **AGP 8.0 起 `BuildConfig` 不再自动生成** → 必须写
  `buildFeatures { buildConfig = true }`。
  忘了的话运行时 `Class.forName("…BuildConfig")` 抛 `ClassNotFoundException`，
  兜底返回 0 → 在线更新会**永远提示「有新版」**（明明已经是最新）。
  `app` 模块一度漏了这行 → 同一个坑的两面。
- ⚠️ **改包名时，`proguard-rules.pro` 的 keep 规则必须同步改**（2026-11-09 实测）：
  `-keep class <旧包名>.BuildConfig { *; }` 写死了包名，
  改成新包名后这条 keep 就不再匹配 → 新的 `BuildConfig` **根本没被 keep**
  → R8 优化掉 `VERSION_NAME` / `VERSION_CODE` 字段
  → 运行时 `NoSuchFieldException` → 「服务器信息」里客户端版本显示为空、
  在线更新又**永远提示「有新版」**。
  → 改包名要同步改的**四处**：
  ① `app/build.gradle.kts` 的 `namespace` + `applicationId`；
  ② 源码目录 + 所有 `package` 声明 / `import`；
  ③ **`app/proguard-rules.pro` 的 BuildConfig keep 规则**（最容易漏，
  脚本按扩展名批量替换时 `.pro` 常常被漏掉）；
  ④ `tools/*.ps1` 里的 `am start -n <包名>/.ui.HomeActivity`。
  → 症状：改完包名，「服务器信息」的「客户端版本」是空的。
  → 验证：`adb logcat | Select-String BuildConfig` 看有没有 `NoSuchFieldException`。
- ⚠️ **zxing 必须用 3.2.1**：3.3.0+ 的 `qrcode.encoder.Encoder` 静态初始化引用
  `java.nio.charset.StandardCharsets`（**API 19 才有**）→ Android 4.3 上
  `NoClassDefFoundError`，二维码画不出来。3.2.1 全库 0 处引用，API 18 安全。
  判断方法：解包 jar 扫常量池有没有 `java/nio/charset/StandardCharsets`。
- ⚠️ **写外置存储必须声明 `WRITE_EXTERNAL_STORAGE`**（API 18 起强制，
  `targetSdk` 再低也一样）→ 否则 `FileOutputStream` 抛
  `FileNotFoundException: /sdcard/xxx (Permission denied)`。
  在线更新下载 APK 到 `/sdcard/Download/` 就踩过这个坑，表现为「下载失败」，
  但**报错文案完全看不出是权限问题**（只有一句 "Permission denied"）。
  → 电视是 4.3，没有运行时权限机制，`uses-permission` 写上就装机即授；
  → 排查手法：`aapt2 dump permissions <apk>` 直接看打包结果里有没有这条。
  → **现状（2026-11-20 起）**：在线更新不再写 `/sdcard/Download/`，改下到
    应用外部私有目录（`getExternalFilesDir(DIRECTORY_DOWNLOADS)`），那条路径不需要任何存储权限；
    两个权限仍保留但加了 `android:maxSdkVersion="32"`（老版本回退用）。
- ⚠️ **`RecycledViewPool` 是按 `viewType` 分桶的 —— 两列布局不同却共用一个池会「串台」。**
  （历史坑，出在已删除的 `LibraryActivity` 上，但结论对任何「两列共用回收池」的界面都成立）
  当时左右两列**布局不一样**（左栏 `item_rail` 纯文字、右栏 `item_media` 带海报），
  而 RecyclerView 默认所有 item 的 `viewType` 都是 0；
  再加上 `rvRight.setRecycledViewPool(rvLeft.recycledViewPool)`，
  右栏的 `item_media` holder 就会被左栏回收走 →
  **左栏（类别 tab）渲染出带封面的卡片，还白白发一堆图片请求**（4.3 上纯浪费带宽和内存）。
  症状很像「数据源多了封面」，其实是回收池串台；
  → 解法：`getItemViewType(position) = side`（左 0 / 右 1），两个桶天然分开，池还能继续共享；
  → 排查手法：`uiautomator dump` 看 `rv_left` 的子节点里有没有 `img_poster`
  （纯文字行高 56dp / 84px，带封面的行 132dp / 198px，行高对不上就是串台了）。
- **焦点 ≠ 选中**：左栏「焦点框」只表示遥控器走到哪儿，「选中态」才表示右边内容来自哪一项，
  两者要能同时出现。所以左栏选中项 = 左侧暖色竖条（`rail_indicator`）+ 标题变暖色
  + `state_selected` 的浅底色（`bg_rail_item.xml`）。
  状态由 `SimpleAdapter.setSelected(id)` 维护；`rvLeft.itemAnimator = null` —— 默认动画会让切换时闪一下。
- ⚠️ **改选中态千万别用 `notifyItemChanged`**：那会让左栏重排，
  而 `LinearLayoutManager` 每轮 layout 都会拿「当前有焦点的那个 child」当锚点
  （源码：`LinearLayoutManager.updateAnchorFromChildren` → `getFocusedChild()` →
  `assignFromViewAndKeepVisibleRect`），
  焦点一旦停在已被用户滚走的那个 tab 上，下一轮 layout 就把左栏**拽回**那个 tab
  —— 用户观感就是「点完 tab，加载期间滚动 tab 列会被拉回之前点的 tab」。
  → 正确写法：改选中态只动**可见行**的样式（`rvLeft.findViewHolderForAdapterPosition(pos)`
  → 改 `isSelected` / 竖条 / 标题色），**不通知 adapter**，一个 layout 都不触发；
  → 再加一道保险：左栏滚动监听里，焦点那一项滚出可视区时把焦点交给当前最上面那一项
  （只在 `SCROLL_STATE_DRAGGING` 做，遥控器方向键滚动本来就会带着焦点走，抢焦点会跟框架打架）。
  → 排查手法：`uiautomator dump` 比对 `rv_left` 子节点首项的文本在「加载前 / 滚动后 / 加载完成后」
    三次是否一致。
- ⚠️ **选中态写在 holder 上会「分身」——RecyclerView 的视图缓存复用不重新绑定**：
  `setItemViewCacheSize(12)` 让滚出屏幕的 holder 进缓存（不销毁），再滚回来时
  **只要 stable id 相同就直接复用，连 `onBindViewHolder` 都不调用**
  （源码：`RecyclerView.tryGetViewHolderForPositionByDeadline` → 缓存命中且 id 相同直接 attach）。
  holder 上残留的 `isSelected` + 暖色标题就被原样带回来 → **左栏同时好几个 tab 高亮**。
  → 解法：① `onViewAttachedToWindow` 里按当前状态重刷左栏视觉（attach 一定被调用，
  新建 / 池里捞的 / 缓存复用的都算）；② `onViewRecycled` 里清掉选中样式，别污染池子；
  → 症状：用户图里 3 个 tab 同时高亮；排查：`uiautomator dump` 数 `rv_left` 里 `selected=true` 的节点，
  正常应该**恒为 1**。
- ⚠️ **`OnKeyListener` 里 `return true` 前必须确认焦点真的被抢走了**（2026-11-18 真机踩）：
  用户报「首页遥控器只能上右下，左键没反应」——两个独立原因叠在一起：
  ① `onKeyDown` 里 `KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> true` 把左键也吃掉了；
  ② `onLeftFromFirstColumn` 写成
  `{ val chip = homeMoreChips.getOrNull(rowIndex); chip?.requestFocus(); true }` ——
   **该区块没有「更多」按钮时 `chip` 是 null**，`requestFocus()` 空转，但照样 `return true`
   → 按键被吞、焦点不动，观感就是「这个键坏了」。
  → 正确写法（`HomeActivity.kt` 现在这样）：
  ```kotlin
  // 行首按 ←：先找「更多」按钮，够不着就回顶栏；两条都失败才放给框架
  if (chip != null && chip.isShown && chip.requestFocus()) true
  else navItems.getOrNull(tabIndex)?.requestFocus() != null
  // 顶栏上按 ←：直接去顶栏第一个 tab（否则顶栏也是「死」的）
  KeyEvent.KEYCODE_DPAD_LEFT -> { navItems.getOrNull(tabIndex)?.requestFocus(); true }
  ```
  → 通用原则：**方向键的 fallback 一定要有去处**（回顶栏 / 回行首），
    不要为了「防越界」把按键吃掉 —— 吃掉比乱跳更像 bug。
  → 验证手法（模拟器逐次按键 + dump，一次只按一下）：
  ```
  adb shell input keyevent 21   # ←
  adb shell uiautomator dump /data/local/tmp/t.xml
  adb shell cat /data/local/tmp/t.xml   # 看 focused="true" 那个节点的 bounds/text
  ```
  实测轨迹：第二排卡片 → `更多 ›`（`[1777,577][1878,628]`）→ 顶栏「首页」tab，
  每一步都有落点，不再卡死。
- ⚠️ **遥控器菜单别用系统 `AlertDialog` + `ListView`**：系统对话框在 4.3 上是 Holo 蓝框 +
  青色标题分隔线，跟 App 的暖色语言完全两套，像「塞进来的系统弹窗」。
  → 现在 `TvMenu` 是 **透明窗口 + 自绘面板**：
  `Theme.MyEmbyHub.Dialog`（`windowBackground=透明` + `windowNoTitle`）
  + `tv_menu_dialog.xml`（深色圆角面板 + 暖色竖条标题）
  + `tv_menu_item.xml`（52dp 行，标题左、副标题右，焦点行圆角浅底 + 暖色竖条）。
  → 菜单行用 RecyclerView（和主界面同一套焦点行为，4.3 上验证过）；
  行根布局要写 `android:focusableInTouchMode="true"` ——
  否则在触摸模式（模拟器上用鼠标点开菜单）下 `requestFocus()` 会失败，
  菜单弹出后焦点停在 RecyclerView 容器上，**一个高亮行都没有**，用户不知道往哪儿按。
  → 自绘面板后，窗口宽度要 `show()` 之后再 `window.setLayout(560dp, WRAP_CONTENT)`，
  顺序反了宽度不生效。
- ✅ **所有弹窗都走 `TvDialog`**（消息 / 确认 / 自定义内容三种形态）：
  面板 `tv_dialog.xml`（暖色竖条标题 + 内容区 + 按钮行），
  按钮 `tv_dialog_button.xml`（`TextView` + `bg_dialog_btn` / `bg_dialog_btn_secondary`，
  文字颜色用 `res/color/dialog_btn_text*.xml` 的 selector 跟着焦点切）。
  已替换：服务器信息 / 退出登录 / 检查更新成功·失败 / 在线更新确认 /
  下载进度 / 搜索输入 / 手机扫码二维码 / 剧集详情页。
  → 详情页原来自带一套背景 + 标题（`dialog_detail.xml`），
  放进面板后会出现「双层壳」→ 背景去掉、标题交给面板、按钮换成对话框按钮样式。
  → 输入框的系统下划线是青蓝色（Material 走 colorPrimary），
  用 `bg_dialog_input.xml` 换成暖色细线。
- ✅ **长内容对话框要 `scrollable = true`**（2026-11-19 落地：「检查更新」的更新说明
  每版都往里堆，内容一多就把面板顶出屏幕、按钮直接看不见）：
  `TvDialog.show/message/confirm` 都有 `scrollable: Boolean = false` 与
  `maxContentHeightDp: Int = 300`；`UpdateUi` 两处已开。
  → ⚠️ `ScrollView` 没有 maxHeight，只能**先 `content.measure(...)` 再按 `min(量出来的, 上限)` 定高**；
    量宽必须是 `EXACTLY`（面板 560dp − 左右各 24dp = 512dp），用 `UNSPECIFIED` 会让 TextView 只量一行高。
  → 焦点按用户要的「默认在按钮上」：焦点在内容区且 `!sv.canScrollVertically(1)` 时按 ↓ 交给
    第一个按钮；在按钮行按 ↑ 回内容区。`ScrollView` 必须 `isFocusable = true`，
    否则焦点直接跳过内容区（`isVerticalScrollBarEnabled` 只在内容超高时才给，不然一条无效滚动条很显眼）。
  → 实测（30 行长 notes）：初始焦点在「下载」→ ↑ 进内容区 → 连按 20 次 ↓ 滚到底自动落到「稍后」，
    按钮没被顶出屏幕。
- ⚠️ **`Thread.sleep()` 一定要接住 `InterruptedException`**：
  `PhoneControlServer` 的定时线程在 `sleep(1000)`，
  而 `stop()` 里的 `worker.shutdownNow()` 会打断它 ——
  `InterruptedException` 从 `Runnable` 逃出去会被 `ThreadPoolExecutor`
  包成 `java.lang.Error` 抛到线程外 → **整个进程崩掉**
  （真机表现：扫码弹窗一按「关闭」App 就闪退，logcat 里是
  `FATAL EXCEPTION: pool-3-thread-2 / java.lang.Error: java.lang.InterruptedException`）。
  → 解法：sleep 外面 `catch (e: InterruptedException)`，安静退出线程。
- ⚠️ **给手机页面回 JSON 时不能手拼字符串**（2026-11-17 实测）：
  `PhoneControlServer` 原来是 `"{\"ok\":false,\"message\":\"$msg\"}"` 这么拼的，
  只把 `"` 换成 `'`，**没管换行**。而报错文本里常常带换行 ——
  网关层的 502/504 回的是多行 HTML 错误页，`EmbyApi.readError()` 会把响应体
  前 200 字符原样带出来 → 拼出来的 JSON 里有**裸 `\n`**
  → 手机浏览器 `r.json()` 抛
  `SyntaxError: Bad control character in string literal in JSON at position 146`，
  用户看到的是一句 JS 报错，而不是「服务器暂时不可用」。
  → 解法：一律走 `org.json.JSONObject`（`jsonMessage()` / `jsonState()` 两个 helper），
  顺手把连续空白压成一个空格。
- ⚠️ **登录页的错误文案不能复用 `friendlyError()`**：
  它把任何带 `401` 的消息都翻成「登录已过期，请重新登录」——
  那是**已登录用户** token 失效时说的话。可密码打错时服务端回的也是 401
  （`登录失败 HTTP 401 无效用户名或密码。请重试。`），
  于是用户在登录页上看到「登录已过期」，会以为是自己网络坏了，反复重试。
  → 解法：登录路径用 `friendlyLoginError(e)`（401 → 「账号或密码不对」，其余转交 `friendlyError`）。
- ✅ **TLS 实现可选 Conscrypt（2026-11-18 落地，解决「只开 GCM 的服务器连不上」）**：
  `TlsStack` 现在是**双实现**：Conscrypt（首选，自带 BoringSSL + GCM/ChaCha20/TLS1.3）
  和系统 JSSE（兜底，只有 CBC 套件）。
  - 依赖：`org.conscrypt:conscrypt-android:2.5.2`（**必须 2.5.x**：更高版本要 API 21+；
    2.5.2 的 manifest 是 `minSdkVersion=9`，`org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter`
    专门给 4.3 用，它继承 `org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl`）。
  - `app/build.gradle.kts` 里 `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86") }`：
    Conscrypt AAR 自带四个 ABI（共 ~8MB）。armeabi-v7a 给电视、x86 给模拟器、
    **arm64-v8a 给纯 64 位新设备**（2026-11-20 加：缺它新机型直接
    `INSTALL_FAILED_NO_MATCHING_ABIS`）；仍砍掉 x86_64，靠
    `packaging { jniLibs { excludes += "lib/x86_64/**" } }` 省 2.5MB。
    APK 从 2.27MB → 4.11MB（加 Conscrypt），再加 arm64、砍 x86_64 后现为 **3.9MB**。
  - **不要 `Security.insertProviderAt()` 装全局 Provider** —— 那会替换整个进程的 JSSE。
    `TlsStack.conscrypt()` 是 `SSLContext.getInstance("TLS", Conscrypt.newProvider())`，
    作用域只在本 App 自己 new 的 context 上。
  - 全程**反射**调 `org.conscrypt.Conscrypt`（`Class.forName` + `getMethod`）：
    万一某个 ABI 上 `.so` 缺失，`UnsatisfiedLinkError` 能被 catch 住自动退回系统栈，
    而不是在类校验阶段炸掉整个 `TlsStack`。
  - SNI 要分支：Conscrypt 建的 socket 走 `Conscrypt.setHostname()`（它同时负责 SNI 和
    **证书主机名校验**，漏了会验证失败）；老系统才走反射 `OpenSSLSocketImpl.setHostname()`。
    `TlsStack.applyServerName()` 的第 ⓪ 步就是干这个。
  - 用户可在**设置 → TLS 实现**里选「自动 / Conscrypt / 系统 JSSE」（存 `SharedPreferences`
    的 `myembyhub_tls`/`impl`，改完清 `TlsStack.cache`）。真出问题能当场锁回系统栈。
  - 判定当前走的哪条路：`adb logcat -s TlsStack`，会出现
    `TLS 使用 Conscrypt（GCM/ChaCha20 可用）` 或 `Conscrypt 不可用，退回系统 JSSE（只有 CBC 套件）`。
  - 实测（API 18 模拟器）：一个只开 `ECDHE-ECDSA-AES128-GCM-SHA256` 的站，
    系统 JSSE 下报「安全连接失败」，Conscrypt 下直接拿到 Emby 的 401 应答
    （说明握手 + 证书链全过）；同时另一台 CBC/GCM 都开的站两边都能连。
  - `proguard-rules.pro` 要 `-keep class org.conscrypt.** { *; }` +
    `-keep interface org.conscrypt.** { *; }` + `-dontwarn org.conscrypt.**`
    （AAR 自带 proguard.txt 只有前一条，不 keep 会在运行时炸）。

- ✅ **TLS 栈按域名记忆 + 失败自动换栈（2026-11-19 落地）**：
  4.3 上有两条栈，**哪条能跟某台服务器谈成事先不知道**：系统 JSSE 只有 CBC，
  Conscrypt 有 GCM/ChaCha20/TLS1.3 但要加载 8MB 原生库。现代服务器大多只开 GCM，
  所以系统栈经常谈不拢 —— 但也不是全部（有的服务器两条都能连，
  只开 GCM 的那台则只有 Conscrypt 能连）。
  - 目标：**每个域名最多只付一次失败握手**。第一次谈不拢就换另一条重试，
    结论写进 `SharedPreferences`（`myembyhub_tls_hosts` / 键 `map`，
    形如 `srv-a.example.com=system;srv-b.example.com:1234=conscrypt`，最多 24 条），
    以后直接照记录走。**不放内存表**：这台电视进程被杀是常态。
  - 三个文件：`net/TlsHostMemory.kt`（存取 + 编解码 + 归一化）、
    `net/TlsConn.kt`（`withRetry(ctx, trust, host) { factory -> ... }`）、
    `TlsStack.chooseFor / escalate / noteSuccess / isHandshakeFailure`。
  - ⚠️ **只在握手失败时换栈**：证书校验失败、DNS 错、超时不换 —— 换也救不回来，
    还会把真因埋掉、往记录里写错结论。
  - ⚠️ **「握手过了但接口报错」（401、JSON 解析失败）要记录**：那说明这条栈
    能把 TLS 谈成，只是业务层出问题。只有 DNS/连不上/超时这三种不记（压根没走到 TLS）。
  - ⚠️⚠️ **`TlsConn.withRetry` 包住的 block 必须把握手异常往外抛**：
    第一版 `DeviceReport.upload` / `PlayStats.post` 在 `catch` 里直接
    `Result.Fail(...)`，异常被吃在里层 → 换栈分支永远走不到，等于没实现。
    规矩：业务失败照旧返回值，但要先 `if (TlsStack.isHandshakeFailure(e)) throw e`。
    验证：`probe_system_first=true` + 清空记录 + 连只开 GCM 的服务器，
    日志应出现 `用 SYSTEM 握手失败，换栈重试` → `换 CONSCRYPT 重试`，
    且 `myembyhub_tls_hosts.xml` 立刻多出 `该域名=conscrypt`；
    再冷启动一次应**直接** `TLS 使用 Conscrypt`，不再有失败握手。
  - 设置页「TLS 实现」里多了「新域名先试系统库」（**默认开**）和「已记录的域名」。
    默认开的理由：系统栈能用就该用它 —— Conscrypt 要映射 8MB 原生库 + 首次加载 JIT，
    在 1.5GB / 96MB 堆的电视上是实打实的开销；而且它是「平台自己在走的那条路」。
    代价只是每个新域名第一次可能多付一次握手，记录之后就没了。
    ⚠️ 别因为「Conscrypt 更现代」就默认它：现代 ≠ 这台机器上更省。
    切换它或手动锁定实现时都会**清空记录**（旧记录是按旧策略试出来的）。
  - 反馈报告的 `tls.hostMemory` 是这张表的快照 —— 回答「用户那台机器上
    这个服务器到底被哪条栈连上的」，设置页写的「自动」只说明策略、不说明结果。

- ⚠️ **第一次 TLS 握手要单独放宽超时**（2026-11-19 实测，反馈上报的握手探测）：
  第一次握手用 8 秒**超时失败**，紧接着同参数的第 2、3 次各 1 秒就成功 ——
  差的正是「Conscrypt 原生库（8MB `.so`）首次加载 + JIT 预热」这一笔。
  → 探针里第一次给 20 秒（`DeviceReport.FIRST_PROBE_TIMEOUT`），后续用 8 秒。
  → 危害不只是「慢」：把冷启动那一笔报成 `SocketTimeoutException`，
    整份报告会得出**反向结论**（「服务器连不上」），比没数据更糟。
  → 反过来也成立：看到「第 1 次失败、第 2/3 次成功」这种组合，
    先怀疑超时太紧，别急着下「服务器有问题」的结论。

- ⚠️ **`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 的一大来源：服务器把 `.iso` 光盘镜像挂进了媒体库**
  （2026-11-18 实测，朋友的服 `emby.example.com`《玉簟秋》）：
  该剧 40 集里有 **10 集**（第 9/10/11/12/14/17/18/19/20/21）媒体源文件是
  `The.Lament.of.Autumn.2026.S01Exx.iso`，chaichai 与本项目报的错**一字不差**。
  - Emby 扫库时会**读穿镜像**，所以 `PlaybackInfo` 里 `Container=mkv`、
    视频流规格（1920×1080 / h264 High / 9.8Mbps）全都正常 —— 详情页看起来毫无异常。
  - 但 `static=true` 直放吐的是**整张 ISO 的原始字节**：
    前 32KB 全 `00`，`0x8000` 才是 UDF 描述符 `BEA01`，`0x8800` 是 `NSR03`；
    服务端 Content-Type 也是 `application/octet-stream`。
    十六个 extractor 挨个 sniff 全是零 → 谁也认不出来。
  - 判定一行就够：`GET /Videos/{id}/stream?static=true&mediaSourceId=…` 跟着 302 看
    `Location` 里 `response-content-disposition` 的文件名，后缀是 `.iso` 就是它。
  - **服务端转码救不了**：这类站通常 `SupportsTranscoding=false`，
    即便强行要 `/main.m3u8` 拿到了播放列表，拉切片会回
    `400 User does not have video remuxing permission.`（权限卡死，不是 ffmpeg 问题）。
  - **客户端现在能直接放**（见下面那条「✅ ISO / BDMV 直放」）。
    解析失败时才退回文案提示：`PlayerActivity.onPlayerError` 里按 cause 链
    匹配 `None of the available extractors` 加一句「服务器上这一集是光盘镜像」的提示。
    ⚠️ 别再拿 `error.message` 去匹配 —— `PlaybackException.message` 只有外层那句
    `"Source error"` / `"Renderer error."`，真正能诊断的文本在 `cause` 链里，
    要用 `generateSequence(error as Throwable?) { it.cause }` 拼起来再判断。
  - 4K 那几集（1/2/3/4/5/7/8/13/16/23/33）在模拟器上会走另一条错
    （`NO_EXCEEDS_CAPABILITIES` + `OMX.google.h264.decoder`），那是**模拟器软解只吃 Baseline**，
    与本条无关，别混为一谈（见第 6 节模拟器小节）。

- ✅ **ISO / BDMV 直放（2026-11-18 落地，纯 Kotlin，无 native 依赖）**：
  客户端现在能自己拆 UDF 光盘镜像、找到正片、剥壳交给 `TsExtractor` 播。
  两个新文件：`app/src/main/java/work/luxiaoxiao/myembyhub/net/Udf.kt`（UDF 2.50 解析）
  和 `app/src/main/java/work/luxiaoxiao/myembyhub/net/IsoPlayback.kt`（DataSource 适配）。
  接入点只有一个：`PlayerActivity` 里把
  `DataSource.Factory { EmbyHttpDataSource(this, token) }` 换成
  `IsoDataSourceFactory(this, token, Uri.parse(url))`。
  - **判定不写死后缀**，就发一次 8 字节 Range 读 sector 16，看 `BEA01`：
    `.mkv` / `.mp4` 那次探测失败 → 原样透传 `EmbyHttpDataSource`，零回归；
    连 ISO 的都是几十次小块 Range，比 sniff 整个文件便宜得多。
  - **解析链**（`Udf.parse`）：anchor(256) → VDS → PD(`partitionStart` @188) → LVD →
    在物理分区扫 `fileType=250` 找元数据文件得 `metaStart` → FSD（LVD+248 的 long_ad）→
    扫 FSD 里 `len==2048` 且目标块 `fileType==4` 得根目录 → `BDMV/STREAM/` 挑最大的 m2ts → EFE 取 extent 表。
  - ⚠️ **VDS 必须按 2048 扇区步进扫描**，不能用「offset 10 的 CRC 长度」推算：
    这张盘 PVD 该字段读到 5780（512 字节的 PVD 不该这么长），照它算会跳过后面的 PD/LVD
    → 报「找不到 PD/LVD」。
  - ⚠️ **FID 里的文件名是 d-string**（首字节是压缩标识：`0x08` 后接 8 位字符、`0x10` 后接 UCS-2）。
    直接当 UTF-8 解会得到 `"\u0008BDMV"`，和 `"BDMV"` 永不相等 → 报「不是蓝光结构」。
  - ⚠️ **long_ad 的 partRef 是权威值，0 也算数**。正片的 ICB 在元数据分区（partRef=1）、
    extent 在物理分区（partRef=0），早期写成「非 0 才采用」→ 整条映射偏了 65536 个扇区，
    读出来的「头」是 `B8 68 88 27`，认不出 TS sync。现在无条件取
    `u16(blk, adStart + k + 8)`。
  - ⚠️ **extent 长度是 2048（扇区）的整数倍，不是 192 的整数倍**
    （实测 `1071644672 = 523264 × 2048`）。2048 不整除 192 →
    **一个 192 字节包可能横跨两个 extent**。早期按「每 extent 有几个整包」算
    → `ISO 窗口只读到 598/600 个包` + `0/2 个包` 反复，播放卡死。
    → 现在按**字节**映射：`readDiskRange(logicalStart, length)` 把逻辑字节偏移
      换算到 extent 内位置、跨段时拆成几次 Range 拼进 `rawBuf`，再统一剥壳。
  - **剥壳**：蓝光 m2ts 每 192 字节 = 4 字节 ATS 头 + 188 字节标准 TS。
    `TsExtractor` 的 `TS_PACKET_SIZE = 188`、sync 固定在 0/188/376…
    → 必须 `arraycopy(rawBuf, src+4, win, dst, 188)`。
    包长靠探测：`head[4]==0x47 && head[4+192]==0x47 && head[4+384]==0x47` 是 192+4；
    `head[0]==0x47...` 是 188+0（有些人把 m2ts 存成标准 TS）。
  - **纯 Kotlin 无 native**：没引 libbluray / libmpv，4.3 能跑，APK 不涨
    （包体只多十几 KB 字节码，现在整包 3.9MB）。
    → 这也是和「用 mpv 外挂播放」的分界线。
  - 不做蓝光导航菜单（`index.bdmv` / `PLAYLIST` / `CLIPINF` 一律不读），只放
    `STREAM/` 里最大的那个 m2ts；AACS 加密盘（有 `AACS/` 目录）不在范围。
  - 实测（emulator-5554 / API 18）：解析出
    `Video(name=00000.m2ts, extents=4, first=(start=917504, len=1071644672),
    packetSize=192, prefixLen=4, logicalLength=3483865600, totalBytes=3557990400)`，
    进度条能走到 `01:55 / 47:42` 并持续前进 → 时间轴、缓冲、seek 都正常。
    模拟器只有 `OMX.google.h264.decoder` 且只吃 Baseline，1080p High 只出声音不出画，
    **出画要在电视上验**。
  - 判定走的哪条路：`adb logcat -s IsoPlay:*`，会看到
    `ISO 解析结果：Video(...)`（成功）或 `UDF 解析失败：<原因>`（失败退回原路径）。
  - ⚠️ **Emby 元数据里的编码不可信，以镜像里实际字节为准**（2026-11-18 真机实测）：
    某剧第 9 集的 Emby `PlaybackInfo` 报 `h264 High 1080p`，但把镜像里
    `BDMV/STREAM/00000.m2ts` 剥壳存成 `.ts` 后 `ffprobe` 读出来是
    **`hevc Main 3840x2160 level=153`（4K HEVC）**，`codec_tag=HDMV`。
    电视上表现就是「**有声音、没画面**」——MStar 的 `OMX.MS.HEVC.Decoder` 上限是 1080p，
    logcat 依次是 `NoSupport [codec.profileLevel, hvc1.1.6.L153.90, video/hevc]` →
    `Format exceeds selected codec's capabilities` → `MS_OMX_HEVCDEC Error Code: 1000003`。
    **ISO 解析本身没问题**（音轨出、进度走、seek 正常），纯粹是硬解吃不下。
    → 所以「ISO 能不能播」要分两件事看：镜像**拆得开**（客户端已解决）和
      里面正片**这台机器解码得了**（4K 就认命）。
    判定手法：`adb shell am start ...` 播一下，再 `adb logcat | Select-String 'hevc|exceeds'`。
  - ⚠️ **ExoPlayer 2.19.1 遇到「解码器建起来但每帧都被硬解拒绝」不会抛错**：
    视频轨被静默跳过、音频照播，`onPlayerError` 一声不响 ——
    用户看到的就是「有声音没画面、界面上什么提示都没有」。
    → `PlayerActivity.checkVideoStalled()` 因此改成两轮：
      第 1 轮（起播 15 秒）判「解码器根本没建起来」；第 2 轮（再过 20 秒）判
      「解码器建起来了、就是一帧不出」，提示「这台电视的硬解吃不下这个规格（多见于 4K HEVC）」。
      早期写的是 `if (decoderInitialized) return` —— 正好把这种最常见的情况放过。
  - ✅ **硬解预检 + 白屏兜底（2026-11-18）**：`PlayerActivity.checkHardwareCapability(format)`
    在 `onVideoInputFormatChanged` 里用 `MediaCodecUtil.getDecoderInfos(mime,false,false)` +
    `MediaCodecInfo.isFormatSupported(format)` 提前判 —— 就是打出 `NoSupport [codec.profileLevel…]`
    的同一个函数。**全都不支持才判死**，查询抛异常 / 一条解码器都查不到一律放行（宁可试，别误伤）。
  - ⚠️ **「白屏」的成因是没摘视频层**：`showCodecLimit()` 里必须
    `player.videoComponent.clearVideoSurface()`。不摘的话那块白/灰一直盖着文案
    （硬件 video plane 优先于 View 合成，既不受 Surface 黑底控制、也不跟窗口背景走）。
  - ⚠️ 光在 `showSpeed()` 里加守卫不够：`speedTick` 那个 0.5 秒的 Runnable 会自己把
    「正在缓冲… / x.x MB/s」刷回去盖在错误文案上 —— **它也必须 `if (capabilityBlocked) return`**。
  - ⚠️ **RecyclerView 不会替你滚长排**（2026-11-18 实测）：focus search 只在**已布局**的孩子里
    找邻居。一排 20 条、屏幕只够 8 张时，走到第 8 张按 → 就彻底不动了（1.2 秒按一次也一样，
    不是时序问题），后面十几张永远够不着。
    → `PosterAdapter` 自己接管 ←/→：`moveTo()` 先试 `requestFocus()` 已布局的目标，
      没布局出来就 `rv.scrollBy(step, 0)` + `postOnAnimation` 重试；`step` 从两个兄弟实测
      （`b.left - a.left`），取不到才退回卡宽。
    → ⚠️ 两个反例：`post {}` 的 runnable 常排在那次遍历**之前**跑，目标还没 attach，白跑；
      `scrollToPosition()` 会按「让目标贴边」重排整排，和焦点自己触发的
      `requestChildRectangleOnScreen` 打架，实测焦点来回抖。用 `scrollBy` + `postOnAnimation`。
  - 首页方向键语义（用户 2026-11-18 定的，别再改回去）：
    行首 ← **什么都不做**（早期两版都被否：`chip?.requestFocus(); true` 空转还吞键；
    「找不到 chip 就回顶栏」太跳）；「更多 ›」← 回本排末卡；行末 → 跳「更多 ›」。
    「更多 ›」在**区块标题那一行**的右端（header 里），是 RecyclerView 的**兄弟视图**，
    框架的 focus search 永远找不到它 —— 要靠 `onRightFromLastCard` 手工连。
    ⚠️ 行首判定别写 `position % firstColumnCount == 0`：首页每排传 `firstColumnCount = 1`，
    取模 1 恒为 0 → **每张卡都被当成行首**，按 ← 就乱跳（这就是「非最左边也跳去更多键」的真凶）。
- ⚠️⚠️ **切页时焦点被甩回顶栏，会让「焦点即切换」把你切回去**（2026-11-19 踩）：
  「更多 ›」从跳 `LibraryActivity` 改成 `switchTab(1)` 后**点了没反应** —— dump 里顶栏
  `selected` 仍是「首页」。原因：`showPage(1)` 把 `pageHome` 设成 `GONE`，而此刻焦点正停在
  刚按过的那颗「更多 ›」上 → 视图一消失，框架把焦点甩给顶栏最近的「首页」tab →
  它的 `setOnFocusChangeListener` 排一个 260ms 的 `scheduleTabSwitch(0)` → **刚切过去又被切回来**。
  → 修法：切之前 `suppressTabSwitch = true`，等目标页真正拿到焦点后再放开；放开前若焦点还挂在
  `navItems` / `btnSettings` 上就 `focusContentOf(tab)` 拽回内容区。
  → ⚠️ 不能一直关着：闸门永久关闭 = 「焦点即切换」彻底失效（那是另一个修过的 bug）。
- ⚠️⚠️ **同一个列表拉两遍，会把已经拿到焦点的那张卡 detach 掉**（同一次踩）：
  顶栏对了但焦点落在「排序 · 开播时间 ↓」这颗 chip 上，不进海报墙。轨迹是
  「卡拿到焦点 → `null` → `lib_sort_row`」。
  原因是 `switchTab(1)`（内部 `loadLibraryPageIfNeeded()` → `selectLibrary(libIndex)`）
  紧接着又 `main.post { selectLibrary(index) }` —— 两次请求拉同一个库，后回来的那次
  `notifyDataSetChanged()` 把已聚焦的卡 detach 掉，框架只好把焦点甩给最近的 widget。
  → 修法：`switchTab(index: Int, libraryTarget: Int = -1)`，`libraryTarget >= 0` 时
  **一次做完「切页 + 选中指定库」**，别再补第二次 `selectLibrary`。
  → 通用教训：**任何「切页 + 定位」都不能拆成两次异步加载**，第二次刷新会把焦点顶掉。
- ⚠️ **`scrollToPositionWithOffset()` 之后不能用 `post {}` 重试焦点**：它只是**请求**一次布局，
  `post {}` 的 runnable 常常排在那次遍历之前跑，目标卡还没 attach，`findViewByPosition(0)`
  拿到 `null` 就白跑。用 `rv.postOnAnimation { ... }`（见 `HomeActivity.focusGridRetry`）。
  另外 `focusGrid` 前要判 `rv.adapter?.itemCount != 0`，否则 adapter 还空着时整段空转。

- ✅ **播放页按键（2026-11-19 落地，细节见 [docs/player.md](docs/player.md)）**：
  ←/→ 跳 10 秒（长按 5 秒，并浮出 `▶ 08:42 / 45:10`）、↑/↓ 上一集/下一集、
  **菜单键按一下就出播放页菜单**（选集 / 字幕 / 诊断信息，诊断面板挪进菜单里了）、
  确定/媒体键播放暂停（自己接管）、返回退出。
- ⚠️ **国产电视遥控器的「菜单」键不一定是 `KEYCODE_MENU`**（2026-11-19 真机报「按菜单没反应」）：
  乐视 S50 Air 这类遥控器的键码不统一，有的发 `SETTINGS`(176)、有的 `INFO`(165) /
  `BOOKMARK`(174)，还有把「菜单」做成四个彩色键的。→ 叫出**播放页菜单**的是一整组
  `DIAG_KEYS`（82 / 176 / 165 / 174 / 183-186 / 170 / 172），不是单个常量。
  → 排查靠两条自建通道（电视上没有 uiautomator、`dumpsys` 没权限、用户看不到 logcat）：
  ① `dispatchKeyEvent` 对每个 `ACTION_DOWN` 打 `key NNN(NAME) repeat=0`；
  ② 按到的键不在 `KNOWN_KEYS` 里时**屏幕正中直接弹「未识别按键 NNN」**，
  用户报回数字就能加。屏幕上什么都不弹 = 键被系统截走了，客户端改不了。
  → ⚠️ `keyName()` 是手写映射表，**不能用 `KeyEvent.keyCodeToString()`**（API 19 才有，
  4.3 上 `NoSuchMethodError`）。
- ⚠️⚠️ **「当前是第几集」必须单独定位，别拿 `episodeIndex` 硬算**（2026-11-19 真机报「按上直接回第一集」）：
  两个坑叠在一起 ——
  ① `episodeIndex` 是 `-1` 时 `-1 + delta` 算出越界值，提示却是「已经是第一集了」，
  用户自然理解成「跳回第一集」，实际是「不知道现在第几集」；
  ② 按季号/集号兜底时 `MediaItem.indexNumber` / `parentIndexNumber` 默认值都是 **0**，
  「继续观看」自己构造的 `MediaItem` 和没给集号的列表项全是 0 →
  `indexOfFirst { 0 == 0 }` **必然命中第一集**。
  → 修法 `resolveEpisodeIndex()`：按 id 对 → 季号/集号兜底要带 `want.indexNumber > 0`
  且**候选必须唯一**（多季剧每季都从第 1 集编号，`parentIndexNumber` 缺失时会有多条候选，
  宁可说「找不到」也不能瞎挑）；都对不上就老实提示「定位不到当前是第几集」。
  → **教训：默认值也是值。** `Int` 字段用 `0` 表示「不知道」时，任何「相等就匹配」的
  兜底逻辑都会退化成「匹配第一条」。
- ⚠️ **ExoPlayer 自带控制栏已整个关掉**（`app:use_controller="false"`）：
  上面 ⏮ ⏪ 播放 ⏩ ⏭ 五个按钮在这台电视上**永远按不到** —— 方向键被
  `dispatchKeyEvent` 吃掉、又没有触摸屏。留着只会让人以为能按。
  → 关掉之后必须自己补三件事：确定键 → `togglePlayPause()`；
    进度 → `tv_seek` 浮一行；标题 → `showInfoBriefly()` 计时 3.5 秒。
  → ⚠️ **暂停时一定要显示「已暂停」**（`tv_pause`）：画面本来就是静止的，
    没有这个提示用户分不清「暂停了」和「卡住了」。
- ⚠️ **字幕每行后面垫一条不透明黑底**：真凶是**系统无障碍字幕设置**，不是字幕文件。
  `PlayerView` 构造里调 `SubtitleView.setUserDefaultStyle()` → 读
  `CaptioningManager.getUserStyle()`；系统字幕一启用且底色是黑的（电视/盒子常见默认），
  所有走 `SubtitleView` 的字幕都带黑底。客户端不显式 `setStyle` 就跟它走。
  → 修法：`subtitleView.setStyle(CaptionStyleCompat(WHITE, TRANSPARENT, TRANSPARENT,
    EDGE_TYPE_OUTLINE, BLACK, null))`，**`setStyle` 这一句才是修黑底的**；
    必须放在 `playerView.player = p` 之后（构造里已先打入系统样式）。
  → 描边不能省：透明底之后白字压在白墙/雪景上会糊成一片。
  → ⚠️ `setApplyEmbeddedStyles` / `setApplyEmbeddedFontSizes` 是**只写属性**，
    Kotlin 不能写 `sv.applyEmbeddedStyles = false`，编译期 `Unresolved reference`，只能调方法名。
    它俩管的是「字幕文件改字号/斜体」，**与黑底无关**，别混。
  → ⚠️ **别去查 ASS 的 `BackColour`，它压根不参与渲染**：解包
    `exoplayer-extractor-2.19.1` 的 `classes.jar` 扫字符串，`BackColour` / `PrimaryColour` /
    `Colour` 全部未命中，只命中 `BorderStyle` / `Alignment` —— `SsaDecoder` / `SsaStyle`
    只读位置信息，颜色一律不读。实测把 `BackColour` 写成不透明红，画面上没有任何红条。
  → 复现手法（模拟器默认字幕是关的，要主动打开黑底）：
    `settings put secure accessibility_captioning_enabled 1` +
    `accessibility_captioning_background_color -16777216`（0xFF000000），
    验完 `... enabled 0` 复原。正向对照：把 `setStyle` 底色临时改 `Color.RED`，
    应出现**红条**；不出现说明这个 `SubtitleView` 实例没被用上。
- ⚠️ **内嵌字幕默认压根不会被选中**：`DefaultTrackSelector` 默认不选「既没语言标记、
  也没 default/forced 标志」的字幕轨，而绝大多数 MKV 内嵌 ASS/SRT 两样都没有
  → 片子里明明有字幕却一个字不显示，看着像「不支持字幕」。
  → 必须 `setParameters(buildUponParameters().setSelectUndeterminedTextLanguage(true))`。
  → 别用 `setPreferredTextLanguage("zh")` 凑：字幕没写 language 时那条规则同样匹配不上。
- ✅ **字幕默认关，开关在播放页菜单里**（2026-11-19 用户要求，`Session.subtitleEnabled`
  默认 `false`）：理由不是省性能，是**会重叠** —— 很多资源自带压进去的硬字幕，
  再把内嵌轨渲染一遍就是上下两行字、内容还不一样；而硬字幕只能靠「关掉内嵌轨」避开，
  播放器没法自动判断。
  → 菜单那项会写出**这部片子有几条内嵌字幕轨**（数 `player.currentTracks` 里
    `TRACK_TYPE_TEXT` 的组长度），看到「开（2 条内嵌字幕轨）」就知道值得开。
  → 切换要 `trackSelector.setParameters(trackSelector.buildUponParameters()
    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on))` —— ⚠️ **必须 `buildUponParameters()`**，
    整个重建会把 `setSelectUndeterminedTextLanguage(true)` 一起丢掉，开了也是空。
  → ⚠️ `trackSelector` 必须存成 Activity 字段，`onCreate` 的局部变量在菜单回调里拿不到。
  → 「默认关」和 `setSelectUndeterminedTextLanguage` 不冲突：后者决定「开了选哪条」，
    前者决定「开不开」。
- ⚠️⚠️ **方向键必须写在 `dispatchKeyEvent` 里，写 `onKeyDown` 是死代码**：
  全屏播放时焦点在 `PlayerView` 上，它的控制器先消费掉上下左右 —— `onKeyDown` 只是兜底，
  根本轮不到。真机症状是「按 ↓ 一点反应都没有、logcat 一行都不打」，而
  `KEYCODE_MENU` 恰好没被控制器吃 → 「菜单键出面板」正常工作，**把 bug 完美掩盖**。
  → 要用的键全在 `dispatchKeyEvent` 里截，`ACTION_DOWN` 时处理，**`ACTION_UP` 也要吞**
  （`consumedKey` 记住 keyCode）—— 只吞按下会让系统报
  `InputEventConsistencyVerifier: ACTION_UP but key was not down`，抬起漏给视图树还可能触发多余点击。
  → 排查手法：`adb shell input keyevent 20` + `adb logcat -s PlayAct:*`，
  有「起播：第 N 集」就是收到了，什么都没有就是没收到。
- ⚠️ **切集必须清「只判一次」的守卫**（`firstFrameRendered` / `decoderInitialized` /
  `capabilityBlocked` / `capabilityChecked` / `videoSupported` / `videoTrackCount` /
  `stallRound` / `diagShown` / `endedNaturally` / `stopped`），并且 `removeCallbacks(stallCheck)`。
  不清的具体后果：从 4K HEVC 切到 1080p，`capabilityBlocked` 还是 true、视频层已
  `clearVideoSurface()` → 用户看到「有声音没画面」还挂着「硬解吃不下」的提示。
  为此把起播 15 秒/20 秒两轮判定的匿名 `postDelayed` lambda 改成了具名 `stallCheck`（否则摘不掉）。
- ⚠️ **切集顺序不能动**：`reportStop()` 要**先**跑（它读当前 `reportItem` / `playSessionId`），
  再换 `reportItem`、再发新 `playSessionId`（沿用旧的会让服务端把两集算成一次播放）。
- 剧集列表三个入口：详情页/媒体库**本来就带全集列表**（`EpisodeRef.encode` 编成 JSON 塞 Intent
  extra，300 集约 40KB，远低于 Binder 200KB）；首页「继续观看」只有一个 itemId → 后台两步补拉
  （`itemDetail(itemId).seriesId` → `episodes(seriesId)`），1~2 秒藏在起播缓冲里，失败静默。
- 「播完自动下一集」默认开（`Session.autoNextEpisode`，key `auto_next_episode`），
  设置菜单可关。`STATE_ENDED` → `reportStop()` → `main.post { autoAdvance() }`：
  放 post 里是因为在回调栈里直接 `setMediaSource` 会让 ExoPlayer 自己重入。
  三个提前退出分支各打一句日志（开关关 / 没列表 / 最后一集），方便用户报「没自动下一集」时定位。
- ⚠️ 验自动续播**别用「seek 到距结尾 30 秒」**：远端对象存储会回
  `HTTP 416 Requested Range Not Satisfiable` → `Source error`，播放器进错误态而不是
  `STATE_ENDED`，看着像「自动下一集没做」。用本地 8 秒 Baseline 片最稳（见 docs/player.md 第 8 节）。

## 7. 工程构建坑位（AGP 9.5 / Gradle 9.6）

- **AGP 9 内置 Kotlin 支持**：不要再声明 `org.jetbrains.kotlin.android` 插件——
  声明会报 "plugin is already on the classpath" 或 "Cannot add extension with name 'kotlin'"。
  直接写 `.kt` 源码即可；Kotlin stdlib 会被自动注入（纯 Java 模块也会，按需 exclude）。
- `kotlin { compilerOptions { jvmTarget } }` 扩展由 AGP 注册。
- Kotlin DSL 里 `java.xxx` 会被 Gradle 的 java 扩展抢占，JDK 类要用文件头 `import`。
- 版本号：`versionCode` 用秒级时间戳（永远递增，可覆盖安装），`versionName` 形如
  `1.0.20261117.3`（末位 = 当天第几次打包，由根目录 `.version-seq` 记着，已 gitignore；
  只有跑 `assemble*` 时才自增，跑单测不吃号）。发版 tag 就是 `v` + `versionName`。

### 7.1 签名与发版（2026-11-19 落地）

**签名不需要申请。** Android 是自签名体系（self-signed），不经过任何机构审核 ——
只要一把自己保管、永不更换的 keystore。

| 项 | 值 |
|---|---|
| keystore | `../MyEmbyHub-keys/myembyhub.jks`（**仓库外**，不进 git） |
| 口令 | 同目录 `STORE-PASSWORD.txt`（store 口令 = key 口令） |
| 别名 | `myembyhub` |
| 算法 / 有效期 | RSA 2048 / 10950 天（≈30 年） |
| SHA-256 | `94:AE:F9:A9:55:0D:42:81:65:7E:8D:CF:0A:4E:00:53:41:B9:62:46:40:80:E9:D7:E8:14:6A:09:5F:99:AE:54` |
| SHA-1 | `0C:6F:08:44:8D:EA:2A:FD:CE:5B:F3:D5:AE:7E:4B:40:DA:D6:8D:06` |

`local.properties`（已 gitignore）里四项：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。

> ⚠️ **换密钥 = 所有已装设备都装不上新包**（Android 按签名认应用身份），
> 只能让用户卸载重装。所以这把钥匙要备份，别重建。

- ⚠️ **Gradle Kotlin DSL 里 `signingConfigs` 会自赋值**：
  `create("release") { storePassword = storePassword }` 右侧解析成接收者自己的同名属性
  （初始 null）→ 四个字段全留空 → 构建报 `Keystore file not set for signing config release`。
  → 解法：外部变量必须换名字（本项目用 `relStoreFile` / `relStorePassword` / `relKeyAlias` /
  `relKeyPassword` 四个 `rel` 前缀的局部变量）。
- ⚠️ **`enableV1Signing = true` 必须显式开**：Android 4.3 只认 JAR 签名（v1），
  只开 v2 会「解析包时出现问题」。当前配置 v1 + v2 都开，v3/v4 不开（老系统不认）。
- `release` buildType 必须 `isDebuggable = false` —— R8 才真正生效：
  release 包 **3.9MB**（三个 ABI 全带），同名 debug 包要大 1MB 左右，差的那截就是没被优化掉的代码。
- 新增 `lint { abortOnError = true; checkReleaseBuilds = true; disable += "ExpiredTargetSdkVersion" }`：
  本项目永远不上 Play，从不追 Google 的 targetSdk 年限要求，那条「targetSdk 过期」检查不适用；其余 lint 错误一律拦构建。
  （真拦下过一个真问题：`DetailActivity.kt` 里 `epRow.setHasFixedSize(true)` 用在
  `height=wrap_content`、高度随集数变的列表上。）
- `tools/publish-release.ps1` 现在会校验三件事，任一不过直接 throw：
  ① `apksigner verify --print-certs` 的 SHA-256 必须等于上表（防止漏配 `storeFile` 退回 debug 签名）；
  ② `aapt dump badging` 输出里不能有 `application-debuggable`；
  ③ 清单 versionCode 与 APK 实测一致。
  另外默认还会把同一个包另存一份 `myembyhub-debug.apk` 一起上传（`-NoLegacyAlias` 可关），
  让烤了旧资产名的老客户端至少还能下载到东西。
- **签名换了之后本地装包要先 `adb uninstall`**，直接 `adb install -r` 会报签名不一致。

---

## 8. 还没验证的（下一步该测的）

### 8.1 已全部验证（2026-11-03，见 `docs/tv-device-report.md`）

1. **硬解能力** —— ✅ H.264=`OMX.MS.AVC.Decoder`、HEVC=`OMX.MS.HEVC.Decoder`（都是 MStar 硬解）。
2. **公网 Emby 的 TLS 协商** —— ✅ 服务器最低 TLS 1.1；4.3 用 TLSv1.2 + 内置锚点握手成功。
3. **CA 库判定** —— ✅ 4.3 的 CA 库没有 LE 2024 新链，靠 `BundledCa` 内置锚点解决。
4. **账号密码登录** —— ✅ HTTP 200，AccessToken 32 位。
5. **网络实播与丢帧** —— ✅ 1080p 零丢帧（mkv+hevc / mp4+h264 / mkv+h264）；
   4K HEVC 解码器能初始化但起播慢 → 产品上默认锁 1080p。
6. **HLS 播放** —— ⚠️ 未单独验证（媒体库主体是 mkv/mp4 直放，暂不需要）。
7. **电视休眠时 adbd 是否断开** —— ✅ 深度待机时 5555 会关，需用户按遥控器唤醒（按「信号源」最有效）。

### 8.2 已在模拟器上验证（2026-11-19）

- 播放页交互：**菜单键按一下出播放页菜单**（选集 / 字幕 / 诊断信息）/ 首页「继续观看」
  进播放器后台补拉剧集列表 / 上下键切集 / 播完自动下一集（开关两态都验过）/
  字幕默认关（截图对照：关时一个字没有，开后白字黑描边）。
  见 [docs/player.md](docs/player.md)。
- 长内容对话框可滚动：30 行长 notes 的检查更新框，初始焦点在「下载」、↑ 进内容区、
  连按 ↓ 滚到底落到「稍后」，按钮没被顶出屏幕。
- 首页「更多 ›」跳媒体库并选中对应库、焦点落在海报墙第 0 张（三层坑见第 6 节）。
- `LibraryActivity` 已删除（新版首页 + 媒体库页覆盖了它的全部用途）。
- 设置菜单四项：体验优化计划（双向读写 `play_stats_enabled`）/ TLS 实现三个子项 /
  新域名先试系统库 / 已记录的域名（清空）。
- 反馈上报脱敏 + `tls.implActual` + 内置对照（编号 #10 / #12）。
- 正式 release 包冷启动无 `FATAL` / `NoSuchField` / `ClassNotFound`（R8 后反射链完好）。
- 「检查更新」在最新版上回「没有新版本」。

### 8.3 还欠的

1. **真机（乐视电视）上的一切** —— 模拟器验不了：
   - 4K HEVC 的「有声音没画面」提示是否按预期出现；
   - ISO / BDMV 直放出画（模拟器只有 Baseline 软解）；
   - 1080p 硬解播放流畅度。
2. **电视上首次装 release 签名包必须 `adb uninstall` 再装**（签名跟旧 debug 包不一致），
   而系统安装器那一步**只能人按遥控器**（`input` 从 shell 注入到别的 uid 窗口会被静默丢弃）。
3. 电视休眠时 adbd 断开，要做真机验证得先按遥控器唤醒。
4. **左栏「加载中被拉回」的修复待真机确认**（2026-11-09）：
   模拟器上复现不出真机那个「滚着滚着被拽回之前点的 tab」的现象
   （新旧写法在模拟器上表现一致），修复按框架源码的机制做的
   （见第 6 节「改选中态千万别用 `notifyItemChanged`」）。
