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

> ⚠️ **测服务器时务必先确认本机 DNS 有没有被 Clash 劫持**：
> 本机跑着 Clash Verge（fake-IP 模式），`tv.example.com` 会解析成 `198.18.0.206`，
> 所有 `openssl s_client tv.example.com:443` 测的都是代理出口，不是电视要连的那台机器。
> 正确做法：用 DoH 查真实 IP，再 `s_client -connect <真实IP>:443 -servername tv.example.com`。
> 详见 `docs/tv-device-report.md` 第 5.3 节。
>
> 另：服务器**最低要 TLS 1.1**（TLS 1.0 会被回 `tlsv1 alert protocol version`），
> 且**不要求 SNI、不挑 cipher** —— 这两条放宽了设计约束。

自签名证书同理，客户端必须显式信任。

---

## 3. 模块结构

仓库只有一个模块：

| 模块 | 语言 | minSdk | 作用 |
|---|---|---|---|
| `app` | Kotlin + XML/Views | 18 | **正式 TV 客户端**（已改造并装到电视上）：登录/媒体库/播放/搜索/排序/在线更新 |

`app` 模块的 UI 是 XML/Views（**不是 Compose**，Compose 要 API 21），
网络层在 `app/src/main/java/work/luxiaoxiao/myembyhub/net/`：
`TlsStack` / `BundledCa` / `UrlKit` / `EmbyHttpDataSource` / `TrustMode` /
`EmbyApi` / `Session` / `QrUtil` / `UpdateManager` / `ImageLoader` / `PhoneControlServer`。

> 历史上还有两个探针模块：`tvcheck`（TLS 协商 / 播放解码 / 5102 远程配置）和
> `tvprobe`（设备自检，零依赖 Java）。真机验证全部通过后已删除，
> `tvcheck` 里唯一长期有价值的 `UrlKit` 回归测试已迁到 `app/src/test/`。

`dist/` 是交付产物目录（APK、在线更新清单 `version.json`）；`tools/` 是辅助脚本。

> ⚠️ `gradlew :app:assembleDebug` **只生成 `dist/version.json`，不会把 APK 拷进 `dist/`** ——
> 打包后要手动同步（`python -c "import shutil; shutil.copy(...)"`），
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
gradlew :app:assembleDebug                        # 正式 TV 客户端（会自动生成 dist/version.json）
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
adb shell am start -n work.luxiaoxiao.myembyhub/.ui.LibraryActivity

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
adb push dist/myembyhub-debug.apk /sdcard/Download/myembyhub.apk
# 2) 调起系统安装器（电视上需要人按一次「安装」）
adb shell am start --user 0 -a android.intent.action.VIEW \
  -d 'file:///sdcard/Download/myembyhub.apk' -t 'application/vnd.android.package-archive'
```

**装上带「在线更新」的版本之后就不用再手动推了**：电脑起 `python -m http.server 8765`，
电视上「在线更新」填 `http://192.168.1.50:8765/myembyhub-debug.apk` 即可。

---

## 6. 这台机器上暴露出来的平台坑（务必记住）

- **Android 4.3 的 `java.net.Socket` 没有实现 `java.io.Closeable`** → Kotlin 的 `socket.use {}`
  在真机上抛 `ClassCastException`，而桌面 JVM 上单元测试全绿。Socket 一律手写 try/finally。
- `DisplayMetrics.densityDpi`(API 17)、`Build.SERIAL`(19)、`Build.VERSION.SECURITY_PATCH`(23)、
  `ApplicationInfo.minSdkVersion` 在老系统上访问会抛 `NoSuchFieldError` → **一律走反射**。
- `SSLSocket.getProtocol()` / `getCipherSuite()` / `SSLSocketFactory.getSupportedProtocols()`
  在公开 SDK stub 里是 `@hide`，编译期引用不到 → **只能反射**。
- `screencap` 在这台 ROM 上输出 0 字节（截图不可用）。
- `dumpsys activity` / `dumpsys window` 需要 DUMP 权限，shell 没有 →
  判断状态用 `pm path`、`ls -l /data/app/...`、`ps`、`logcat`。
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
  ④ `tools/*.ps1` 里的 `am start -n <包名>/.ui.LibraryActivity`。
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
- ⚠️ **`RecycledViewPool` 是按 `viewType` 分桶的 —— 两列布局不同却共用一个池会「串台」。**
  `LibraryActivity` 里左右两列**布局不一样**（左栏 `item_rail` 纯文字、右栏 `item_media` 带海报），
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
- ⚠️ **`Thread.sleep()` 一定要接住 `InterruptedException`**：
  `PhoneControlServer` 的定时线程在 `sleep(1000)`，
  而 `stop()` 里的 `worker.shutdownNow()` 会打断它 ——
  `InterruptedException` 从 `Runnable` 逃出去会被 `ThreadPoolExecutor`
  包成 `java.lang.Error` 抛到线程外 → **整个进程崩掉**
  （真机表现：扫码弹窗一按「关闭」App 就闪退，logcat 里是
  `FATAL EXCEPTION: pool-3-thread-2 / java.lang.Error: java.lang.InterruptedException`）。
  → 解法：sleep 外面 `catch (e: InterruptedException)`，安静退出线程。

## 7. 工程构建坑位（AGP 9.5 / Gradle 9.6）

- **AGP 9 内置 Kotlin 支持**：不要再声明 `org.jetbrains.kotlin.android` 插件——
  声明会报 "plugin is already on the classpath" 或 "Cannot add extension with name 'kotlin'"。
  直接写 `.kt` 源码即可；Kotlin stdlib 会被自动注入（纯 Java 模块也会，按需 exclude）。
- `kotlin { compilerOptions { jvmTarget } }` 扩展由 AGP 注册。
- Kotlin DSL 里 `java.xxx` 会被 Gradle 的 java 扩展抢占，JDK 类要用文件头 `import`。
- 版本号：`versionCode` 用秒级时间戳（永远递增，可覆盖安装），`versionName` 形如
  `1.0.20261102-230013`。

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

### 8.2 现在还欠的（正式客户端）

1. 电视上点一次「在线更新 → 下载」—— 验证下载链路（权限坑已修，待真机确认）。
2. 在电视上肉眼确认：媒体库有海报、进入剧集能播放出画面。
3. 详情页（大图 + 简介 + 剧集列表）—— 等核心链路验顺后再议。
4. **左栏「加载中被拉回」的修复待真机确认**（2026-11-09）：
   模拟器上复现不出真机那个「滚着滚着被拽回之前点的 tab」的现象
   （新旧写法在模拟器上表现一致），修复按框架源码的机制做的
   （见第 6 节「改选中态千万别用 `notifyItemChanged`」）。
   → 真机验证要等电视唤醒（`192.168.1.100:5555` 深度待机时是关的）。
