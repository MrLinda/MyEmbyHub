# 目标电视实测底细 —— 乐视 S50 Air

> 数据采集时间：2026-11-02（晚）
> 采集方式：`tvprobe`（电视上运行的系统信息探针）+ `adb shell` + `getprop`
> 用途：这是 MyEmbyHub 全部技术选型的依据，改客户端前请先核对本文档。
>
> ℹ️ 采集时用到的 `tvprobe` / `tvcheck` 两个探针模块**已在验证通过后从仓库删除**，
> 本文保留它们的原始记录以说明数据来源；相关实现已经搬进 `app`。

## 1. 身份

| 项 | 值 |
|---|---|
| Android 版本 | 4.3 |
| API Level | 18 |
| DISPLAY | `full_mstampoli-userdebug 4.3 V2202RCN02C080080B07241S 1.1.18 test-keys` |
| BUILD_ID | V2202RCN02C080080B07241S |
| INCREMENTAL | 1.1.18 |
| TYPE / TAGS | userdebug / test-keys |
| FINGERPRINT | `MStar/full_mstampoli/mstampoli:4.3/V2202RCN02C080080B07241S/1.1.18:userdebug/test-keys` |
| 系统时间戳 | 1563936749000 |
| 时区 / 语言 | Asia/Shanghai / zh_CN |

## 2. 硬件

| 项 | 值 |
|---|---|
| MANUFACTURER | MStar Semiconductor, Inc. |
| BRAND | MStar |
| MODEL | Letv S50 Air |
| PRODUCT | full_mstampoli |
| DEVICE | mstampoli |
| HARDWARE | napoli |
| BOARD | BD_MST181B_D01A |
| CPU ABI | armeabi-v7a / armeabi |
| 屏幕 | 1920×1080 @ 60.0Hz |
| density | 1.5 (240 dpi) → 1280×720 dp |
| 物理内存 | 1512 MB（/proc/meminfo MemTotal 1548648 kB） |
| 应用堆上限 | 约 96 MB |
| 内部/外部存储可用 | 各 3.3 GB |
| 网络 | WiFi，SSID「xx-home」，本机同网段 192.168.1.0/24 |

## 3. 软件环境（决定架构的部分）

| 探测项 | 结果 | 结论 |
|---|---|---|
| Google Play 服务 `com.google.android.gms` | ❌ 无 | 不能用任何 GMS 库 |
| Google Play 商店 `com.android.vending` | ❌ 无 | 应用只能 sideload |
| WebView（三种包名全测） | ❌ 无 | **不能网页套壳**，必须原生 UI |
| 启动器 | ✅ 有 | 可从桌面进应用 |
| 已安装应用数 | 84 | — |

## 4. 调试通道

| 通道 | 状态 | 备注 |
|---|---|---|
| adb over TCP | ✅ `192.168.1.100:5555` | **一直开着**；别用高并发+短超时扫网段（会挤爆 adbd backlog 导致误判） |
| adb root | ❌ 被乐视禁用 | `adb root` → "unable to connect for root: closed"；无 su |
| adb install / pm install | ❌ 无权限 | shell uid=2000 无 `INSTALL_PACKAGES` → SecurityException |
| 可用装包法 | push + 系统安装器 | `adb push x.apk /sdcard/Download/` → `am start --user 0 -a android.intent.action.VIEW -d file:///... -t application/vnd.android.package-archive`，**需人在电视上按「安装」** |
| adb shell am start 启动 App | ⚠️ 需 `--user 0` | 否则报 `startActivity asks to run as user -2` |
| dumpsys activity / window | ❌ 需 DUMP 权限 | 改用 `pm path` / `ls -l /data/app/...` / `ps` / `logcat` |
| screencap | ❌ 输出 0 字节 | 该 ROM 截图不可用 |
| /sdcard 读写 | ✅ | shell 可 push/pull |
| 网络出站 | ✅ | 电视可上网 |

## 5. 协议 / TLS 能力

### 5.1 本机

- Android 4.3 **默认只启用 TLS 1.0**；1.1/1.2 需显式开启（`Tls12SocketFactory` 已实现）。
- 系统 CA 库为 2013 年版本，**不认现代根证书**（Let's Encrypt 等）。
  → 正式客户端需要自带 CA（`app` 已内置 LE 锚点）或引入 Conscrypt。

### 5.2 目标服务器 tv.example.com（公网）

> ⚠️ **重要更正（2026-11-03）**：本章原先的结论是「服务器只协商出 TLS 1.2、TLS 1.0/1.1 不可用」，
> 那是**在电脑上测试时被本机 Clash Verge 的 fake-IP 污染**得出的错误结论（详见 5.3）。
> 用真实 IP 直连重测后，结论已修正。

| 项 | 实测值（真实 IP 直连，openssl 3.2） |
|---|---|
| 真实 IP | `203.0.113.10`、`203.0.113.11`（`de-ak-slb.example.com`） |
| 支持的协议 | **TLS 1.1 ✓ / TLS 1.2 ✓**；**TLS 1.0 ✗**（回 `tlsv1 alert protocol version`） |
| 首选套件 | `ECDHE-RSA-CHACHA20-POLY1305` ← ChaCha20 需 Android 4.4+ |
| 也能接受的套件 | `ECDHE-RSA-AES128-SHA`、`AES128-SHA`（纯 RSA 密钥交换）、`AES128-GCM-SHA256` 等 |
| SNI | **可选**：带不带 SNI 都能握手成功（无 SNI 也返回 `CN=example.com`） |
| 证书签发 | Let's Encrypt（CN=YR2），服务器握手签名用 **RSA-PSS** |
| 证书链 | `CN=example.com` → `Let's Encrypt YR2` → `ISRG Root YR` → `ISRG Root X1` |
| 链上根的年份 | ISRG Root YR = **2024 年**，ISRG Root X1 = 2015 年，4.3 都没有 |

**结论（已修正）**：服务器本身很宽容——不挑 SNI、不挑 cipher、不要求 RSA-PSS。
**真正的门槛只有两条**：① 最低 TLS 1.1（4.3 默认只发 TLS 1.0，必须显式抬高）；
② 必须信任 LE 新根（4.3 的 CA 库是 2013 年的）。

### 5.3 踩过的坑：Clash fake-IP 污染了所有本机测试

本机跑着 **Clash Verge（fake-IP 模式）**，DNS 被劫持到 `198.18.0.2`，
`tv.example.com` 解析成 **`198.18.0.206`**（fake-IP 段，不是真实地址）。
所有 `openssl s_client tv.example.com:443` 走的都是 Clash 的代理出口，
**测的根本不是电视会连到的那台服务器**。

| 做法 | 结果 |
|---|---|
| `openssl s_client -connect tv.example.com:443` | 走 fake-IP → 结论不可信 |
| `openssl s_client -connect 203.0.113.10:443 -servername tv.example.com` | ✅ 直连真实服务器，结论可信 |
| `Resolve-DnsName tv.example.com -Server 223.5.5.5` | 仍被 TUN 模式劫持，返回 fake-IP |
| 用 DoH（`dns.alidns.com/resolve`）查真实 IP | ✅ 能拿到真实 IP |

> **教训**：以后凡是「电视端要连的公网服务」，本机对照实验必须先确认 DNS 是否被劫持，
> 优先用 DoH 拿真实 IP + `-servername` 指定 SNI 直连。

### 5.4 电视端实测（2026-11-03，tvcheck 自动诊断矩阵）

用 `tvcheck` 的自动诊断（`adb shell am start --es cmd diag`，结果写 `/sdcard/tvcheck-tls.txt`）在真机上跑出来：

| 组合 | 结果 |
|---|---|
| 系统 JSSE + TLSv1.0 | ✗ `tlsv1 alert protocol version`（服务器最低要 1.1） |
| 系统 JSSE + **TLSv1.1** | ✅ 成功，`TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA` |
| 系统 JSSE + **TLSv1.2** | ✅ 成功，`TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA` |
| 同上 + 内置 LE 锚点（BUNDLED） | ✅ **成功**，说明自带锚点这条路可行 |
| 单独 `TLS_ECDHE_RSA_WITH_AES_128/256_CBC_SHA` | ✅ 成功 |
| 单独 `TLS_RSA_WITH_AES_128/256_CBC_SHA` | ✅ 成功 |
| 单独 `TLS_DHE_RSA_WITH_AES_128_CBC_SHA` | ✗ `sslv3 alert handshake failure`（服务器不认 DHE） |
| 系统自带的 curl（OpenSSL 1.0.1e）直连 | ✅ 成功，拿到 `{"ServerName":"Emby Server","Version":"4.8.11.0"}` |

**关键结论**：**Android 4.3 的系统 JSSE 本身完全够用**，
只需①显式把 `enabledProtocols` 抬到 `TLSv1.2`（或 1.1）②用自带锚点信任 LE 链。
**不需要 Conscrypt，也不需要 Bouncy Castle。**

#### 5.4.1 App 侧踩的两个真 bug（对照实验定位）

同样的裸握手在真机上成功，但 `HttpsURLConnection` 走自定义 `SSLSocketFactory` 时失败。
`tvcheck` 的自动诊断矩阵（`C2`/`C3`/`D` 三节）把原因钉死了：

| 调用方式 | 修复前 | 修复后 |
|---|---|---|
| 裸握手：`createSocket()` + `connect()` + `enabledProtocols=[TLSv1.2]` | ✅ | ✅ |
| 经 `SSLSocket` 包装类包装后再握手 | ✗ `Broken pipe` | ✅ **成功** |
| `HttpsURLConnection` + 默认 factory | ✗ `protocol version` alert | ✗（预期，4.3 只发 TLS1.0） |
| `HttpsURLConnection` + 自定义 factory（不包装） | ✗ `protocol version` alert | ✗（预期，协议没抬） |
| **`HttpsURLConnection` + 只包 factory（真 socket 抬协议）** | ✗ | ✅ **HTTP 200** |

**Bug ①：包装 `SSLSocket` 时没有转发 `connect()`。**
包装类的 `connect()` 落在 `java.net.Socket` 自己的实现上，
被包装的 delegate socket **根本没连上** → 握手时 `Broken pipe`。
→ **正确做法：只包装 `SSLSocketFactory`，不包装 `SSLSocket`**；
在 factory 的每个 `createSocket()` 里对**真 socket** 设 `enabledProtocols` 后**原样返回**。

**Bug ②（已确认不是 bug）**：`HttpsURLConnection` 在服务端回 401 且不带
`WWW-Authenticate` 头时，会在 `getResponseCode()` 阶段抛
`IOException: No authentication challenges found`，
响应体根本读不到。Emby 不发这个头 →
**必须手工兜住**，否则「凭据不对」会被误报成「网络/TLS 失败」。

> ✅ **最终结论：`HttpsURLConnection` + 正确实现的 `SSLSocketFactory` 在 4.3 上完全可用**
> （真机实测 HTTP 200，套件 `TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`）。
> 正式客户端可以继续用 `HttpURLConnection`，只需挂上这个 factory。
>
> ⚠️ 但 **ExoPlayer 2.19.1 的 `DefaultHttpDataSource` 走的是 `HttpURLConnection`，
> 而它不接受外部传入的 `SSLSocketFactory`** → 播放 HTTPS 源仍会失败。
> 播放链路需要自定义 `DataSource`（ExoPlayer 2.19.1 **不带 OkHttp**，依赖树里没有）。

### 5.5 关于 Bouncy Castle（备选，暂不需要）

- Maven Central 上**没有** `bcprov-android` / `bctls-android`，只有 jdk15on 坐标。
- `bctls-jdk15on` 的 JSSE provider 类都放在 **`META-INF/versions/9/`**（Java 9+ 多版本 jar），
  D8 在 API 18 上取不到 → 运行时 `ClassNotFoundException`。
- 若真要上 BC，得手动把 jar 里的 `META-INF/versions/9/**` 提到根目录，或改用 `-android` 变体。
- **结论：暂不引入**，先修好系统 JSSE 路径。

### 5.6 媒体取流链路（2026-11-03 实测，含 v10.example.com）

Emby 服务器 `tv.example.com` 前面挂 openresty 反代，**取流会 302 到另一个域名**。

#### 5.6.1 取流 URL 的正确形式

```bash
# ❌ 错的：item id 是 6 位数（765503 这种），不是 3 位
GET /Videos/765503/stream?static=true&mediaSourceId=26abe6d7e2a5b26ffda7acd1de1efabc
```

- `itemId` 来自 `/Items?IncludeMedia=Video` 返回的 `Items[].Id`；
- `mediaSourceId` 来自 `Items[].MediaSources[].Id`（UUID）。
- ⚠️ **`mediaSourceId` 是必需的，不是「带上更稳」**（2026-11-05 更正，原记录有误）：
  对照实测（item `892548`，同一台服务器、同一个 token）：

  | 请求 | 结果 |
  |---|---|
  | `GET https://tv.example.com/Videos/892548/stream?static=true` | 302 → `v10.example.com` → **`404 Not Found`** |
  | `GET https://tv.example.com/Videos/892548/stream?static=true&mediaSourceId=1439141b…` | 302 → `v10.example.com` → **`200`，35,913,000 字节** |

  `tv.example.com` 这层对缺参数照样 302，**是 v10 纯文件后端在 404** —— 所以客户端
  拿不到 `mediaSourceId` 时表现为「播放失败：HTTP 404」，很容易被误判成网络/服务端故障。
  客户端已在 `LibraryActivity.play()` 里兜底：为空时先用 `/Users/{uid}/Items/{id}?Fields=…MediaSources`
  补取一次。

#### 5.6.2 302 目标：`v10.example.com`（纯文件后端）

```
HTTP/1.1 302 Moved Temporarily
Server: openresty
Location: https://v10.example.com/Videos/765503/stream?static=true&mediaSourceId=…
```

`v10.example.com` 的行为：

| 请求 | 结果 |
|---|---|
| `/Videos/{id}/stream?...` | ✅ `206 Partial Content` |
| `/System/Info/Public` | ❌ `403 Forbidden`（openresty） |
| `/Items?Limit=1` | ❌ `403 Forbidden` |

→ **`v10` 只放行取流，API 一律 403**。业务接口必须打 `tv.example.com`，
只有媒体流会落到 `v10`。

#### 5.6.3 `v10` 的 TLS 与证书（4.3 真机验证）

用 tvcheck 的 TLS 矩阵跑 `cmd=diag --es host v10.example.com`：

```
[JSSE] TLSv1.2 / trust=ALL     → ✓ 成功  套件=TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
[JSSE] TLSv1.2 / trust=BUNDLED → ✓ 成功  套件=TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
```

- 证书：`subject: CN=example.com`，`SAN: v10.example.com matched`，`issuer: Let's Encrypt YR2`
  → **和 `tv.example.com` 是同一张证书、同一套链**，内置锚点直接复用，不用加证书。
- ⚠️ 电视自带 curl（OpenSSL 1.0.1e）会选 `ECDHE-RSA-AES128-GCM-SHA256`，
  **但 4.3 的 JSSE 不支持 GCM**，它自动回落到 CBC 并握手成功 → GCM 不是问题。

#### 5.6.4 206 的 `Content-Length` 陷阱

```
HTTP/1.1 206 Partial Content
Content-Type: video/x-matroska
Content-Length: 2048                      ← 只是本次 range 的长度
Content-Range: bytes 0-2047/294813105     ← 这才是总长
Accept-Ranges: bytes
X-Slice-Range: bytes=0-33554431
```

自定义 `DataSource` 必须**从 `Content-Range` 解析总长**并回填给 ExoPlayer，
否则媒体总长被当成 2048 → 时长/进度条全错。

#### 5.6.5 鉴权

`X-Emby-Token` 头**跨 302 重定向依然有效**（`v10` 认同同一个 token），
所以重定向后重发请求时继续带上原头即可，不用换 token。

### 5.7 ✅ 网络实播验证（2026-11-03 19:33，最终成功）

自写 `EmbyHttpDataSource` + ExoPlayer 2.19.1 实播公网视频的最终结果：

```
DataSource open(): HTTP 206, 总长=483453132,
  Content-Range=bytes 0-483453131/483453132,
  解码=TLSv1.2/TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
播放器状态: READY
播放位置: 15554 ms   已缓冲到: 66239 ms
媒体总时长: 1441131 ms
分辨率: 1920x1080
解码器: OMX.MS.AVC.Decoder
丢帧: 0
错误: 无
```

读到的字节（前 24 字节）：

```
00 00 00 20 66 74 79 70 69 73 6F 6D 00 00 02 00 69 73 6F 6D 69 73 6F 32 61 76 63 31 6D 70 34 31
└────┬───┘ └──┬─┘ └──────┬──────┘ └────┬─────┘ └──────────┬───────────┘ └───────┬────────┘
 box size  'ftyp'   'isom'    brands            'isomiso2avc1mp41'
```

→ **完整链路验证通过**：TLS 握手 → 302 跟随 → Range 取流 → `Content-Range` 总长回填
→ 容器识别 → **MStar 硬解**（`OMX.MS.AVC.Decoder`）→ 1080p 上屏 → 零丢帧。

> 👤 **用户肉眼确认（2026-11-03 19:58）**：tvcheck 实播时**电视屏幕上确实有画面**。
> 这补上了整条链路里唯一无法自动验证的环节（`screencap` 在这台 ROM 上输出 0 字节）——
> 至此「能取到流」到「能上屏」全链路闭合。

#### 5.7.1 手写 HTTP 层的四个真坑（全部真机踩过）

| # | 坑 | 现象 | 正确做法 |
|---|---|---|---|
| 1 | `java.net.URL(URL, String)` 解析重定向 | 4.3 的 libcore 把 base 的 query 重复拼到绝对 URL 后（`...?a=b?a=b`）→ 404 | 手工切分 URL（`UrlKit.parse` / `UrlKit.resolve`） |
| 2 | 请求路径缺前导斜杠 | `GET Videos/666/stream` → openresty 回 **400 Bad Request** | `parseUrl` 里自己补回 `/` |
| 3 | 把头结束符当 body 前 4 字节 `unread` 回去 | ExoPlayer 收到 `\r\n\r\n` 而不是 `ftyp` → 15 个 extractor 全拒 → `PARSING_CONTAINER_UNSUPPORTED` | 逐字节读到 `\r\n\r\n` 后**什么都不要 unread**，socket 流位置天然就在 body 开头 |
| 4 | `DataSource.Factory` 返回同一个实例 | ExoPlayer 并发用多个 DataSource（嗅探+读取），有状态实例抢同一个 socket → 字节流错乱 | `DataSource.Factory { EmbyHttpDataSource(...) }`，每次 new |

> `Host` 头带不带默认端口（`:443`）**都可以**拿到 206 —— 之前以为带端口会 404，是误判。

#### 5.7.2 探测服务器的「变体矩阵」探针

`cmd=httpprobe` 用**裸 `SSLSocket`**（绕开 `EmbyHttpDataSource`）一次测 8 种写法，
把「服务器到底接受什么」一次扫清，避免每次改代码都要用户重装 APK 迭代：

```
[Host 无端口 + Range + 路径带斜杠] → 206   ← 正确写法
[Host 带端口 + Range + 路径带斜杠] → 206
[Host 无端口 + 无 Range + 路径带斜杠] → 200（全文，无 Content-Range → 拿不到总长）
[Host 无端口 + Range + 路径无斜杠] → 400 Bad Request
```

### 5.8 ✅ 多格式组合实播（2026-11-03 19:39）

探针改成一次连播多个样本（按「容器/编码」分组，每组挑一个 ≤1080p 的代表），
一次安装覆盖全部主要格式：

| 样本 | 格式 | 分辨率 | 结果 | 解码器 | 丢帧 |
|---|---|---|---|---|---|
| 1 | mkv/hevc | 1920×1080 | ✅ READY | `OMX.MS.HEVC.Decoder` | 0 |
| 2 | mp4/hevc | 3840×1608 | ⚠️ 12s 内仍 BUFFERING | `OMX.MS.HEVC.Decoder` | 0 |
| 3 | mp4/h264 | 1920×1080 | ✅ READY | `OMX.MS.AVC.Decoder` | 0 |
| 4 | mkv/h264 | 1920×1080 | ✅ READY | `OMX.MS.AVC.Decoder` | 0 |

**结论**：
- **mkv + HEVC 1080p 硬解直放成功**（媒体库主体格式没问题）。
- 4K HEVC：解码器**能正确初始化**、分辨率也能识别，只是 4K 码率下缓冲/起播太慢
  → 不是格式不支持，是**性能问题**。产品上应把默认上限锁在 1080p。

#### 5.8.1 ExoPlayer 会发起随机 Range 请求（DataSource 必须支持）

取流日志（样本 1，mkv/hevc）：

```
HTTP 206, Content-Range=bytes 0-294813104/294813105,        position=0
HTTP 206, Content-Range=bytes 294782187-294813104/294813105, position=294782187  ← 跳到接近文件尾
HTTP 206, Content-Range=bytes 8521-294813104/294813105,      position=8521       ← 又跳回来
```

ExoPlayer 在 seek（找 `moov` / 定位），**每次 Range 都用新连接重建**，
`Content-Range` 与总长每次都对 → 自写 DataSource 的随机访问能力验证通过。

> 附带说明：这也正是手写 DataSource 强于 `DefaultHttpDataSource` 的地方 ——
> 后者走 `HttpURLConnection`，无法注入带 TLS1.2 的 socket factory。

### 5.9 ✅ 正式客户端在真机上的实测（2026-11-03 20:50-21:00）

`app` 模块（TV 客户端）打包并装到电视上：

```
/data/app/work.luxiaoxiao.myembyhub-2.apk   8427317 字节   versionCode 1793710453
```

#### 5.9.1 写外置存储必须声明 `WRITE_EXTERNAL_STORAGE`（API 18 起强制）

「在线更新」报**下载失败**，真因是 **manifest 漏声明权限**，不是网络问题：

```bash
aapt2 dump permissions /data/app/work.luxiaoxiao.myembyhub-1.apk   # 装上去的旧包
# → 只有 INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK / ACCESS_WIFI_STATE
#   / CHANGE_WIFI_MULTICAST_STATE，**没有 WRITE_EXTERNAL_STORAGE**
```

Android 4.3 上写 `/sdcard/Download/` 必须有这条权限，否则：

```
FileNotFoundException: /sdcard/Download/myembyhub-update.apk (Permission denied)
```

UI 上只显示「下载失败」，**完全看不出是权限问题**（这是排查耗时最久的一点）。

补充事实：

- API 18 **没有运行时权限机制** → manifest 写上就装机即授，不用弹窗。
- `targetSdk` 再低也一样强制，API 18 是分水岭。
- 下载完成后还要 `File.setReadable(true, false)`，否则**系统安装器**（另一个 uid）
  读不到 `/sdcard` 里的 APK。
> **现状（2026-11-20 起）**：在线更新不再写 `/sdcard/Download/`，改下到应用外部私有目录
> （`getExternalFilesDir(DIRECTORY_DOWNLOADS)` = `Android/data/<pkg>/files/Download`）。
> 那条路径不需要任何存储权限，`WRITE_EXTERNAL_STORAGE` 现在带 `maxSdkVersion="32"` 留着回退。
> `setReadable(true, false)` 仍保留 —— 安装器还是另一个 uid。
> 但**安装器读得到私有外部目录、读不到应用内部目录**，后者只回一句 `Parse error`（已实测）。

#### 5.9.2 服务端和电视网络都没问题（同一次排查的排除项）

电视上直接 curl 同一个 APK：

```
http://192.168.1.50:8765/myembyhub-debug.apk
→ http=200  bytes=8427317  time=0.849s
```

→ 电视到电脑的 HTTP 大文件下载完全正常，失败纯属客户端权限缺失。

#### 5.9.3 覆盖安装后如何确认版本

`/data/app/<包名>-N.apk` 的 **N 在覆盖安装时不变**（卸载重装才变号），
判断有没有装上新包要比**文件大小/时间戳**：

```bash
adb shell pm path work.luxiaoxiao.myembyhub          # 先拿当前路径
adb shell ls -l /data/app/work.luxiaoxiao.myembyhub-2.apk
```

#### 5.9.4 ✅ 在线更新全链路实测通过（2026-11-03 21:06）

电视上点「在线更新 → 下载」后的系统日志（`PackageInstaller`）：

```
I/PackageInstaller: [app][InstallAppProgress]:initCompleteView  sourceFile path : /sdcard/Download/myembyhub-update.apk
W/PackageParser: apk on sdcard  sourceFile dir: /sdcard/Download/myembyhub-update.apk
I/PackageInstaller: [app][InstallAppProgress]:Report str : msgtype=appinstall
                   &packageName=work.luxiaoxiao.myembyhub&versionCode=1793710453&status=0
I/PackageInstaller: [app][InstallAppProgress]:finish request is succeeded
I/PackageInstaller: [app][InstallAppProgress]:sourceFile path : ... exists : true
I/PackageInstaller: [app][InstallAppProgress]:==============delete done=============ourceFile.exists()==false
```

安装后 `/data/app/work.luxiaoxiao.myembyhub-2.apk` 时间戳从 20:56 变成 **21:06**、大小 8427317 → **确实重装成功**。

两个新事实：

1. **`status=0` = 安装成功**；且乐视 4.3 **允许同 versionCode 覆盖安装**
   （清单 versionCode 1793710600 vs APK 自带 1793710453，装的是 APK 自带的那个）。
2. **系统安装器装完会把源 APK 从 `/sdcard/Download/` 删掉**（最后那行 `delete done`）——
   所以「下载完文件不见了」是**正常现象**，不是下载失败。
   手动 `adb push` 的 `myembyhub.apk` 也是装完即删，别再误判成"推送失败"。

## 6. 性能特征

- 电视内存紧张：`ActivityManager-Killer` 在 logcat 中频繁触发，各进程 heap 仅 4~13MB。
- APK 体积要克制（当前 `tvcheck` 约 8.4MB，含 ExoPlayer + 两段内置样片 + Kotlin stdlib）。

## 7. 相关文件

> ⚠️ 采集本文数据时用过的两个探针模块（`tvprobe` / `tvcheck`）在验证全部通过后
> **已从仓库删除**，本文保留它们的原始记录以说明数据来源。相关实现已经搬进 `app`。

| 文件 | 说明 |
|---|---|
| `app/src/main/java/.../net/TlsStack.kt` | TLS 栈工厂（系统 JSSE）+ TLS1.2 协议抬高 |
| `app/src/main/java/.../net/BundledCa.kt` | 内置 LE 锚点 + 手写逐级验签 |
| `app/src/main/java/.../net/EmbyHttpDataSource.kt` | **自写的 ExoPlayer `DataSource`**：TLS + 手写 HTTP + 跟随 302 + Range |
| `app/src/main/java/.../net/UrlKit.kt` | 手工 URL / 重定向解析（有单元测试） |
| `app/src/main/assets/*.pem` | 内置 LE 锚点证书 |
| `app/src/test/.../net/UrlKitTest.kt` | URL 解析回归测试（7 个用例） |
| `dist/` | 交付产物：APK、在线更新清单 |
| `tools/tls-capture.py` | TCP 转发 + ClientHello 抓包（同时捕获服务端 alert） |
| `tools/tlsprobe/` | 纯 Java TLS 探针源码（本机 dalvikvm 加载被 ROM 阉割，未采用） |
| `tools/serve-dist.ps1` | 静态服务（PowerShell 版，需管理员；推荐改用 `python -m http.server`） |

### 7.1 诊断自动化（历史工作流，探针已删除）

这台电视的 `screencap` 输出 0 字节、shell 无 DUMP 权限，**没法自动取界面**，
所以当时「在电视上点按钮 + 拍照回传」不可扩展，验证全部改成文件驱动 —— 由 `tvcheck`
的 `cmd=diag` 入口跑 TLS 矩阵、结果写 `/sdcard/tvcheck-tls.txt` 再 `adb pull` 回来。

```bash
# 触发（探针 App 收到 intent extra cmd=diag 就自动跑矩阵）
adb shell am start --user 0 -n work.luxiaoxiao.tvcheck/.MainActivity \
    --es cmd diag --es host tv.example.com --ei port 443

# 取结果
adb shell cat /sdcard/tvcheck-tls.txt
```

> 这套探针已经随 `tvcheck` 模块删除。现在验证正式客户端靠
> `adb shell am start` + `adb logcat` + `uiautomator dump`（见 `AGENTS.md` 4.1）。
