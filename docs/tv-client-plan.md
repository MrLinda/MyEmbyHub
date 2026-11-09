# 正式客户端技术方案（乐视 S50 Air / Android 4.3）

> 依据：2026-11-03 真机实测（`docs/tv-device-report.md` 第 5 节）。
> 本文是 `app` 模块的落地依据，写代码前先核对。

## 1. 已定型的选型

| 层 | 方案 | 状态 |
|---|---|---|
| TLS | 系统 JSSE + 内置 LE 锚点 + 抬 `enabledProtocols` 到 1.2 | ✅ 真机验证通过 |
| 证书信任 | 内置 `isrg_root_x1/yr.pem` + `letsencrypt_yr2.pem`，手写逐级验签 | ✅ 真机验证通过 |
| HTTP（业务接口） | `HttpURLConnection` + 自定义 `SSLSocketFactory` | ✅ 真机 HTTP 200 |
| 播放（媒体流） | ExoPlayer 2.19.1 + **自定义 `DataSource`** | ✅ 真机实播通过（1080p / 硬解 / 零丢帧） |
| UI | XML/Views + 自己实现焦点导航 | ⏳ 待做 |
| 压缩/加密 | 不需要（Emby 侧配置） | — |

**不需要** Conscrypt，**不需要** Bouncy Castle，**不需要** OkHttp，**不需要** Compose/androidx.tv。

## 2. TLS 层：唯一正确的写法

```kotlin
fun forceTls12(factory: SSLSocketFactory): SSLSocketFactory =
    object : SSLSocketFactory() {
        // 其它重载省略
        override fun createSocket(): Socket = raise(factory.createSocket() as SSLSocket)
        // …每个 createSocket 重载都要 raise()

        private fun raise(s: SSLSocket): SSLSocket {
            s.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
            return s   // ← 关键：返回真 socket，不包装
        }
    }
```

### 三条铁律（都是真机踩出来的）

1. **绝不包装 `SSLSocket`。**
   包装后 `connect()` 不会转发给 delegate → delegate 根本没连上 → `Broken pipe`。
   只包 factory，对真 socket 设协议后**原样返回**。
2. **协议要抬到 `TLSv1.2`（含 1.1、1.0 回落）。**
   4.3 默认只发 TLS1.0，服务器最低要 1.1 → 不抬必然 `protocol version` alert。
3. **信任必须走内置锚点。**
   4.3 的 CA 库是 2013 年的，不认 Let's Encrypt 2024 新链。

### 401 的坑

`HttpURLConnection` 遇 401 且响应无 `WWW-Authenticate` 头时，
在 `getResponseCode()` 就抛 `IOException: No authentication challenges found`，响应体读不到。
Emby 正好不发这个头 → **必须手工兜住**，把异常还原成 401，
否则「密码错」会被误报成「网络失败」。

## 3. 播放层：必须自定义 DataSource

ExoPlayer 2.19.1 的 `DefaultHttpDataSource` 走 `HttpURLConnection`，
**不接受外部传入 `SSLSocketFactory`** → HTTPS 源会失败。
且 2.19.1 **不带 OkHttp**（依赖树里没有 okhttp/okio）。

### 3.1 要实现什么

`DataSource` 接口（`com.google.android.exoplayer2.upstream.DataSource`）：

```kotlin
interface DataSource {
    fun open(DataSpec dataSpec): Long          // 返回总长度（不知道就 -1）
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun getUri(): Uri?
    fun close()
}
```

HLS/DASH 场景还要 `TransferListener` 回调 `onTransferStart/onBytesTransferred/onTransferEnd`。

### 3.2 两种实现路线

| 路线 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 手写 SSLSocket DataSource** | 自己 `SSLSocket` 连服务器 → 手写 HTTP 响应头解析 → 读 body | 零依赖、体积小、完全可控 | 要自己处理 chunked/redirect/range |
| **B. 引入 OkHttp 3.12.x** | 用 OkHttp 的 `OkHttpDataSource` | 成熟、省事、自动处理重定向/断点 | 需确认 minSdk 能否覆盖 API 18；+体积 |

> 路线 B 的 minSdk 需实测：OkHttp 3.12.x 官方支持到 API 21/19 一档，
> API 18 能否用要验证（`okhttp3` 的 `minSdkVersion`）。
> 若不行，退回路线 A。

### 3.3 路线 A 的最小实现要点

- HTTP 请求头要带 Emby 的认证：
  - `X-Emby-Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="..."`
  - 拿到 token 后追加 `X-Emby-Token: <token>`
- 要支持 `Range`（视频拖动必需）：响应 206 + `Content-Range`。
- 要处理 `Content-Length` / chunked；不知道总长返回 `-1`。
- 要处理 3xx 重定向（Emby 反代常见）。
- 连接超时 10s / 读超时 20s 起步，直播流另算。

### 3.4 ⚠️ 2026-11-03 真机实测补充的两个硬事实

实测目标：`https://tv.example.com`（Emby 4.8.11.0，openresty 反代）。

**① 取流接口会 302 到另一个域名，必须自己跟**

```
GET /Videos/765503/stream?static=true&mediaSourceId=26abe6d7e2a5b26ffda7acd1de1efabc
→ HTTP/1.1 302 Moved Temporarily
  Location: https://v10.example.com/Videos/765503/stream?static=true&mediaSourceId=…
```

- `v10.example.com` 是**纯文件分发后端**：它对 `/System/Info/Public`、`/Items` 一律回
  `403 Forbidden`（openresty），只放行取流路径。
- `v10` 的证书是同一张 `CN=example.com`（SAN 含 `v10.example.com`），同一套 Let's Encrypt YR2 链
  → **内置锚点照样能验签通过**，不需要额外证书。
- 4.3 的 JSSE 在 `v10` 上同样能握手成功，协商 `TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`。
  （电视自带 curl 用 OpenSSL 1.0.1e 会选 `ECDHE-RSA-AES128-GCM-SHA256`，
  但 **4.3 不支持 GCM**，JSSE 自动回落到 CBC —— 实测通过，不是问题。）
- `X-Emby-Token` 头**跨重定向依然有效**，`v10` 认同一个 token。
- ExoPlayer 不会替你跟 302 → `DataSource.open()` 里要读 `Location` 头、
  重建连接、重发请求（最多 5 跳）。

**② 206 的 `Content-Length` 不是总长，要从 `Content-Range` 取**

```
HTTP/1.1 206 Partial Content
Content-Type: video/x-matroska
Content-Length: 2048              ← 只是本次 range 的长度！
Content-Range: bytes 0-2047/294813105   ← 这里才是总长
Accept-Ranges: bytes
```

若把 `Content-Length` 原样交给 ExoPlayer，媒体总长会算成 2048 → 时长、进度条全错。
`DataSource` 要**解析 `Content-Range` 的总长并回填给 ExoPlayer**。

### 3.5 已实现的原型（现已搬进正式客户端）

| 文件 | 作用 |
|---|---|
| `app/src/main/java/.../net/EmbyHttpDataSource.kt` | **完整的 ExoPlayer `DataSource` 实现**：TLS 握手、手写 HTTP、跟随 302、Range、`Content-Range` 总长回填 |
| `app/src/main/java/.../net/UrlKit.kt` | 手工 URL 解析 + 重定向解析（避开 4.3 上 `java.net.URL` / `android.net.Uri` 的坑），有单元测试 |
| `app/src/main/java/.../net/TlsStack.kt` | TLS factory（系统 JSSE）+ TLS1.2 协议抬高 |
| `app/src/main/java/.../net/BundledCa.kt` | 内置 LE 锚点 + 手写链校验 |
| `app/src/test/.../net/UrlKitTest.kt` | URL 解析回归测试（7 个用例） |
| ~~`NetPlayProbe.kt` / `HttpProbe.kt`~~ | 端到端实播探针 / HTTP 变体矩阵探针 —— **随 `tvcheck` 探针模块一起删除** |

**真机实播结果**（2026-11-03 19:33）：`READY` / 1920×1080 / `OMX.MS.AVC.Decoder`（MStar 硬解）/
时长 1441131 ms / **丢帧 0** / 错误 无。

```kotlin
val dsFactory = DataSource.Factory { EmbyHttpDataSource(ctx, token) }  // ⚠️ 每次 new
val mediaSource = ProgressiveMediaSource.Factory(dsFactory)
    .createMediaSource(MediaItem.Builder().setUri(url).setMimeType("video/mp4").build())
```

实测要点（已写进实现里）：
- **只包 factory，不包 socket**（见第 2 节铁律）。
- **逐字节读完 `\r\n\r\n` 后不要 unread**——socket 流位置天然就在 body 开头。
- `open()` 返回值即媒体总长，`getResponseHeaders()` 要保留服务器原始大小写
  （ExoPlayer 按 `"Content-Length"` 精确匹配）。
- `DataSource` 在 2.19.1 的签名是 `open(dataSpec): Long`（不是 void），
  且 `getResponseHeaders()` 返回**非空** platform type，Kotlin override 要写
  `Map<String, List<String>>`（不能带 `?`，否则编译不过）。
- **`DataSource.Factory` 必须每次返回新实例**（ExoPlayer 会并发使用多个 DataSource）。

### 3.6 ExoPlayer 侧怎么接

```kotlin
val dataSourceFactory = DataSource.Factory { dataSpec ->
    EmbySslDataSource(this).apply { /* 带 token 与 client info */ }
}
val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
    .setMimeType(MimeTypes.VIDEO_MP4)
    .createMediaSource(MediaItem.fromUri(url))
```

对 HLS 则用 `HlsMediaSource.Factory`，把 `DefaultHttpDataSource.Factory`
换成自定义 factory —— **注意 HLS 的分片请求也是走 DataSource**，所以同一个工厂即可复用。

## 4. 遥控器焦点导航（自实现）

`androidx.tv` 要 API 21 → 不可用。方案：

- 用 `View.setOnKeyListener` 或 `dispatchKeyEvent` 捕获
  `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT/CENTER/BACK/MEDIA_PLAY_PAUSE`。
- 焦点状态自己管（一个 `currentFocusIndex` + 焦点高亮），或用 `View.requestFocus()`
  + `android:focusable="true"` 的标准焦点链（4.3 的 focus 机制其实可用，
  只是没有 TV 风格的焦点样式和 `Leanback` 的辅助类）。
- 列表用 `RecyclerView`（androidx.recyclerview，minSdk 14），
  `setHasFixedSize(true)` 控制内存。
- 建议：**先做「焦点高亮 + 上下左右移动」的手感**，比全键盘导航更重要。

## 5. 内存红线（96MB 堆）

- 图片：Bitmap 用 `RGB_565` + `inSampleSize` 缩放；列表项图片必须复用/缓存有上限。
- 缩略图：Emby 的 `/Items/{id}/Images/Primary?maxHeight=…` 自己带尺寸参数，
  **别下原图再缩**。
- ExoPlayer：限制缓冲（`LoadControl`），1080p 时留意解码器缓冲。
- 避免大 JSON：`/Items` 查询用 `Fields=` 限制返回字段。

## 6. 实施顺序建议

1. **登录 + 库列表**（走 `HttpURLConnection` + factory）→ 验证 API 链路。
2. **自定义 DataSource + 播放**（路线 A）→ 验证媒体链路。
3. **遥控器焦点导航 + 首页/详情页**。
4. 硬解能力实测（H264/HEVC 样片，看 `OMX.MSTAR.*` 还是 `OMX.google.*`）。

## 6.1 客户端已实现的功能（2026-11-03）

`app` 模块已改造为**纯 TV 客户端**（minSdk 18 / XML+Views / 无 Compose）：

| 功能 | 实现 |
|---|---|
| 登录 | 电视端输入 + **手机扫码登录**（二维码） |
| 媒体库 | 左栏库 / 右栏内容，**分层下钻**（剧 → 季 → 集），返回回上层 |
| 搜索 | **手机扫码搜索**（关键词在手机上敲） |
| 排序 | 名称 / 加入时间 / 首播上映时间 / 年份 / 时长，可切升降序 |
| 菜单 | 标题栏按钮 + 遥控器菜单键：搜索 / 排序 / 在线更新 / 服务器信息 / 刷新 / 退出登录 |
| 在线更新 | 自动比对 `version.json`，下载 APK 并调起系统安装器 |

### 6.1.1 扫码输入（这台电视没有软键盘）

`PhoneControlServer`（端口 5103）在电视上起一个小 HTTP 服务，
二维码里是 `http://<电视IP>:5103/?mode=login|search`，
手机扫码打开网页 → 在手机上输入 → 提交给电视。

- 同一个服务两种模式，靠 URL 的 `mode` 参数区分
- 登录成功或 10 分钟超时自动关闭；只提供登录/搜索两个动作
- 电视 IP 优先读 WiFi DHCP（`WifiManager.getDhcpInfo()`），拿不到再枚举网卡
- 二维码用 **zxing core 3.2.1**（纯 Java 库，API 18 可用 —— 但**必须锁 3.2.1**，见 6.1.3）

### 6.1.2 在线更新

- `versionCode` = **秒级时间戳** → 永远递增，覆盖安装不会被系统拒
- Gradle 在 `assembleDebug` 后自动生成 `dist/version.json`：

```json
{ "versionCode": 1793710453, "versionName": "1.0.20261103-205413",
  "url": "http://192.168.1.50:8765/myembyhub-debug.apk", "size": 8427317, "notes": "…" }
```

- 电脑端 `python -m http.server 8765`（在 `dist/`）把清单和 APK 发出去
  → **2026-11-03 已从电视实测：电视能访问该地址** ✓
- 换网络时用 `-PupdateHost=host:port` 重新打包即可
- ⚠️ 下载完**仍需人按一次「安装」**（系统级限制，任何第三方 App 都没有 `INSTALL_PACKAGES`）

### 6.1.3 踩过的坑（都写进代码注释了）

- **`MediaTypes=Video` 会抹掉「剧 → 季 → 集」分层**：
  Series / Season 的 MediaType 不是 Video，加了这个过滤 → 剧集库第一层直接空。
  动画电影库因为直接是 Movie 所以看着正常，把问题掩盖了。
- **`Recursive=false`** 做分层下钻；递归会把三层糊在一起。
- **`LibraryActivity` 不要设成非 exported**，否则 adb 无法自动启动它做验证
  （shell uid=2000 会 SecurityException）。
- **zxing 必须锁 3.2.1**：3.3.0+ 的 `qrcode.encoder.Encoder` 静态初始化引用
  `java.nio.charset.StandardCharsets` —— 那是 **API 19** 才有的类，
  Android 4.3 上直接 `NoClassDefFoundError`，二维码画不出来。
  （判断方法：解包 jar 扫常量池有没有 `java/nio/charset/StandardCharsets`。）
- **AGP 8.0 起 `BuildConfig` 不再自动生成**：忘了
  `buildFeatures { buildConfig = true }`，反射读 `VERSION_CODE` 会抛
  `ClassNotFoundException`，兜底返回 0 → **在线更新永远提示「有新版」**。
  现在 `check()` 改成：读不到自己的版本号就**报错误**而不是误报有更新。
- **写 `/sdcard` 必须声明 `WRITE_EXTERNAL_STORAGE`**（API 18 起强制，`targetSdk` 再低也一样）。
  在线更新把 APK 下到 `/sdcard/Download/` 时踩过：manifest 漏了这条 →
  `FileOutputStream` 抛 `FileNotFoundException: … (Permission denied)`，
  UI 上只显示「下载失败」，**完全看不出是权限问题**。
  - 电视是 Android 4.3，**没有运行时权限机制** → `uses-permission` 写上就装机即授。
  - 排查手法：`aapt2 dump permissions <apk>` 直接看打包结果，别猜。
  - 同源修复：下载完成后 `File.setReadable(true, false)`，
    否则系统安装器（另一个 uid）读不到 APK。


### 6.1.4 在线更新排查手册

| 症状 | 真因 | 怎么确认 |
|---|---|---|
| 一直提示「有新版」，但明明是最新 | `BuildConfig` 没生成 → 反射读版本号失败兜底 0 | `aapt2 dump badging` 看 `versionCode`，logcat 搜 `读 BuildConfig.VERSION_CODE 失败` |
| 提示「检查更新失败」 | 电视访问不到电脑的 8765 端口 / 主机 IP 变了 | 电视上 `curl -s http://<电脑IP>:8765/version.json` |
| 提示「下载失败：… (Permission denied)」 | manifest 缺 `WRITE_EXTERNAL_STORAGE` | `aapt2 dump permissions <apk>` |
| 提示「下载失败 HTTP 404」 | `dist/` 里的 APK 没同步（Gradle 只写 `version.json`，**不负责拷 APK**） | 比对 `dist\myembyhub-debug.apk` 和 `app\build\outputs\apk\debug\app-debug.apk` 的大小 |
| 下载完了但装不上 | 系统安装器读不到 `/sdcard` 里的文件 | 下载后 `setReadable(true, false)` |
| 覆盖安装被拒 | versionCode 没变（不是时间戳） | `versionCode` 必须是秒级时间戳 |
| 下载完 `/sdcard` 里找不到文件 | **正常现象** —— 系统安装器装完会把源 APK 删掉 | logcat 搜 `delete done`；别误判成下载失败 |

**实测（2026-11-03 21:06）**：全链路通过 —— 下载 → 写盘 → 调起安装器 → 人按「安装」→
`PackageInstaller` 报 `status=0`（成功）→ 覆盖安装完成（APK 时间戳 20:56 → 21:06）。
附带发现：**乐视 4.3 允许同 versionCode 覆盖安装**（清单 versionCode 与 APK 自带的不一致也能装），
但仍建议保持秒级时间戳递增，以兼容更严格的 ROM。

**下载链路自检**（电视上已是最新版本、点更新只会显示「已是最新」时用）：
临时把 `dist/version.json` 的 `versionCode` 改大（例如 `1793710600`）、`url` 仍指向同一个 APK，
电视上点「在线更新 → 下载」就能真跑一遍下载→写盘→调起安装器；
自检完把 `version.json` 改回真实值。

> 2026-11-03 实测：电视 curl 下载同一个 8.4MB APK → HTTP 200 / 8427317 字节 / 0.85s，
> 说明服务端与网络没问题，失败纯粹是客户端权限缺失。



| 文件 | 说明 |
|---|---|
| `app/src/main/java/.../net/TlsStack.kt` | TLS factory（系统 JSSE）+ TLS1.2 协议抬高 |
| `app/src/main/java/.../net/BundledCa.kt` | 内置锚点 + 手写链校验 |
| `app/src/main/assets/*.pem` | LE 锚点证书 |
| `app/src/test/.../net/UrlKitTest.kt` | URL 解析回归测试（7 个用例） |
| `docs/tv-device-report.md` | 真机实测底细 |

> 原先的 `tvcheck/src/main/java/.../TlsMatrix.kt`（TLS 诊断矩阵）随探针模块一起删除，
> 它的实测结论已经写进 `docs/tv-device-report.md` 第 5 节。
