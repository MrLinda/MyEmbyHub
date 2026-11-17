# 设备反馈：采集什么、为什么、怎么部署

电视上没有 logcat 可看（`screencap` 输出 0 字节、shell 没有 DUMP 权限），
用户说「播不出来 / 黑屏 / 连不上」时，光靠文字描述基本无从下手。
这个功能就是为了把现场带回来 —— 点一下「上报」，把设备信息、TLS 握手实测结果、
解码器清单、最近一次播放的编码参数和最近几条错误，一起 POST 到自建服务器。

服务端**不在这个仓库里**：它在平级目录 `MyEmbyHub-feedback`，是给作者自己部署的
（一台甲骨文 ARM 机器 + 1Panel + docker），不随公开仓库发布。
公开构建出来的 APK 里 `FEEDBACK_URL` 是空串，界面上会提示「这个版本没有配置反馈服务器」。

---

## 1. 界面上有什么

设置菜单 → **反馈**：

```
┌─ 反馈 ─────────────────────────────────┐
│ 会把设备信息一起带上：系统版本、屏幕、内存、 │
│ 网络、TLS 握手实测结果、解码器支持情况、最近 │
│ 一次播放的编码参数、最近几条错误。          │
│                                          │
│ 不含账号密码和片库内容。                   │
│ ┌──────────────────────────────────────┐ │
│ │ 哪一步不对？播的哪部片？屏幕上显示了什么？│ │
│ └──────────────────────────────────────┘ │
│                      取消   [ 上报 ]      │
└──────────────────────────────────────────┘
```

点「上报」→ 不可取消的转圈框（**采集要 2~6 秒**，见下面「为什么慢」）→
成功显示「已收到，编号 #N」，失败把原因原样贴出来。

「会采集什么」必须写在界面上，不是合规表演：里面会有局域网 IP、Wi-Fi 名称、
设备指纹，用户得知道这些会离开电视。

---

## 2. 采集什么，为什么是这些

| 段 | 内容 | 为什么需要 |
|---|---|---|
| `client` | 包名、versionName/versionCode、安装时间、开机时长 | 版本号是**秒级时间戳**，差一分钟就是不同包 —— 没有它就分不清「你装的是哪个」 |
| `device` | 厂商/型号/device/product/hardware/board、Android 版本、SDK、ABI、fingerprint、build tags、**堆上限**、是否 root、locale、时区 | 判断「是不是那台乐视」「是不是 userdebug」 |
| `screen` | 分辨率、dpi、dp 尺寸、**刷新率** | 布局问题基本都能从这行看出来 |
| `memory` | MemTotal / MemAvailable（退化读 MemFree）、lowMemory、堆上限 | S50 Air 只有 1512MB 物理 / 96MB 堆，OOM 类问题必须先看这个 |
| `network` | 连接类型、Wi-Fi SSID、RSSI、本机 IP、DNS | 「连不上」到底是没网、DNS 错、还是 TLS 挂 |
| `tls` | 默认协议栈、支持的套件数、**三条真实握手记录**、内置锚点清单 | 见下 |
| `codec` | 视频/音频解码器全表 + **17 个 mime 的真实探测** | 见下 |
| `player` | 最近一次播放：容器/视频编码/音频编码/宽高/码率/解码器名/是否出首帧/错误码/错误原文 | 「黑屏但进度条在走」和「起播就崩」是完全两回事 |
| `errors` | 最近 20 条错误（含未捕获崩溃） | 内存环形缓冲，进程内一直留着 |
| `note` | 用户自己写的那句话 | — |

### 2.1 TLS 为什么要握手三次

「接口能通、取流全挂」在这台机器上有两种成因，只看一次握手分不出来：

```
sni=True  trust=ALL      → 协议/套件能不能协商（排除证书因素）
sni=False trust=ALL      → 服务端是不是「没 SNI 就拒」（挂 CDN 的源站会）
sni=True  trust=BUNDLED  → 2013 年那套 CA 库能不能验过服务端证书
```

历史上最难查的那个坑（`AGENTS.md` 的「SNI 缺失」条目）症状极具迷惑性：
`HttpsURLConnection` 自带 SNI 所以接口全好，只有自写 DataSource 取流挂，
报错是 `SSL3_GET_RECORD:wrong version number` —— 看起来像证书不对或只支持 TLS 1.3。
有了这三行记录，一眼就能定位。

### 2.2 解码器为什么要真去建

清单和「能不能用」不是一回事。4.3 上 `MediaCodecInfo.isFormatSupported` 和
`VideoCapabilities`（问 profile/level）**都是 API 21 才有的**，问不到。
所以对 17 个 mime 逐个 `MediaCodec.createDecoderByType()`：建得起来才算有。

实测数据（模拟器 API 18 x86）：

```
OK  video/avc            -> OMX.google.h264.decoder
OK  video/x-vnd.on2.vp8  -> OMX.google.vpx.decoder
OK  video/mp4v-es        -> OMX.google.mpeg4.decoder
OK  video/3gpp           -> OMX.google.h263.decoder
--  video/hevc           : Failed to allocate component instance
--  video/av01           : Failed to allocate component instance
```

这直接解释了「模拟器播不了 4K HEVC」——不是客户端 bug，是压根没这个解码器。

⚠️ **不 configure**：真去 configure 一个 4K HEVC 会在 1GB 内存的机器上直接 OOM，
而反馈功能自己不能是崩溃来源。

### 2.3 为什么慢（2~6 秒）

因为它是**真的做事**：三次 TLS 握手（每次要建 socket、协商、取证书链）、
17 次解码器创建、读 `/proc/meminfo`、遍历网络接口。不是读几个系统字段交差。
所以必须在后台线程跑，而且必须给不可取消的进度框 —— 否则用户以为卡死了。

---

## 3. 客户端实现

| 文件 | 作用 |
|---|---|
| `net/DeviceReport.kt` | 采集 + 上报。数据结构字段名 = 服务端 `report.go` 的 json tag，改一处必须改两处 |
| `net/PlaybackSnapshot.kt` | 最近一次播放的内存快照（`object`，进程内一直留着） |
| 同文件里的 `ErrorLog` | 最近 20 条错误的环形缓冲 + 全局未捕获异常钩子 |
| `ui/FeedbackUi.kt` | 三步对话框：填备注 → 采集上传 → 结果 |
| `res/values/strings_feedback.xml` | 全部文案 |
| `HomeActivity.showFeedback()` | 菜单入口。**打开前关掉「焦点即切换」闸门**，由 `FeedbackUi` 在所有出口回调 `restoreFocusAfterDialog()` |

### 3.1 地址从哪来

```kotlin
// Session.kt
var feedbackUrl: String          // 用户在 prefs 里填的
val feedbackEndpoint: String     // feedbackUrl.ifEmpty { BuildConfig.FEEDBACK_URL }
```

`BuildConfig.FEEDBACK_URL` 由打包时决定（`app/build.gradle.kts`）：

```properties
# local.properties（已 gitignore）
feedbackUrl=https://你的域名
```

或命令行：`gradlew :app:assembleDebug '-PfeedbackUrl=https://你的域名'`。
**都没有就是空串** → 公开构建的 APK 点反馈会提示「没有配置反馈服务器」。

### 3.2 上报为什么放宽证书校验

`TrustMode.ALL`。上报服务器是用户自建的（甲骨文 / 群晖 / 什么都可能），
证书从 Let's Encrypt 到自签都有可能，内置锚点覆盖不全。
这条链路传的是「用户主动要交给自己的设备信息」，放宽不会泄露给别人；
真正该防的是「报错发不出去、反馈功能等于摆设」。

注意 `upload()` 里按协议分流（`conn is HttpsURLConnection` 才设 socket factory），
本地联调 `http://10.0.2.2:端口` 也得能跑。

---

## 4. 服务端（不在本仓库）

服务端代码放在平级目录 `MyEmbyHub-feedback`，**不随公开仓库发布** ——
它是给作者自己部署的（一台甲骨文 ARM 机器 + 1Panel + docker），
里面带后台页面和部署脚本，没必要摊在公开仓库里。

客户端只需要知道这一层协议，改动时两边对齐：

| 路径 | 说明 |
|---|---|
| `GET /healthz` | 探活 |
| `GET /api/v1/config` | 返回 `maxNoteLen` / `noteHint` / `reportEnabled` |
| `POST /api/v1/report` | 收报告，`Content-Type: application/json`，支持 gzip，上限 2MB |

响应：成功 `{"ok":true,"id":<编号>}`（客户端把编号显示成「已收到，编号 #N」），
失败 `{"error":"<原因>"}`。

请求体就是 `net/DeviceReport.kt` 里 `Report` 序列化出来的 JSON ——
**字段名跟服务端一一对应，改一处必须改两处**。
服务端只校验类型、不校验枚举值：老设备什么奇怪的值都可能冒出来，
存下来比拦下来有用。

---

## 5. 验证过的（模拟器 AVD `LeTV` / API 18 / 1920×1080 / 240dpi）

一次真实上报（编号 #3）收到的内容：

```
device : unknown Android SDK built for x86  sdk=18  abi=x86
client : 1.0.20261117.5 (1794921643)
screen : 1920x1008 @240dpi 60Hz   (dp 1280x672)
memory : total=1515MB avail=1148MB heap=96MB
network: mobile/LTE  ip=10.0.2.15
tls    : TLSv1.2,TLSv1.1,TLSv1  54 套件  锚点 3 个
         sni=True  ok  TLSv1.2 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA 117ms
         sni=False ok  TLSv1.2 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA 123ms
         sni=True  ok  TLSv1.2 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA 125ms
codec  : video=4 audio=9 probes=17
player : 「这次开机还没播过东西」
```

界面：`dist/feedback-1.png`（填备注）、`dist/feedback-2.png`（成功回执）。

---

## 6. 踩过的坑

**① `MediaCodecList` 的静态方法和实例方法不是一回事**
第一版写 `MediaCodecList(ALL_CODECS).codecCount` —— 那是 **API 21** 的实例方法，
4.3 上没有，清单永远是 0 条（但探测却跑得通，所以症状是「解码器列表空着、
探测结果有」，很容易以为数据没存上）。
正解：反射 `MediaCodecList.getCodecCount()` / `getCodecInfoAt(i)`，**这两个是静态的**。

**② `isHardwareAccelerated` / `isSoftwareOnly` 都是 API 29+**
4.3 上反射必失败。名字前缀是这个年代唯一可靠的判据：
`OMX.google.*` = 软解，`OMX.<厂商>.*` = 硬解。

**③ `WifiInfo.dns1` 在新 SDK 里被藏了**
编译期引用不到 → 反射读。4.3 拿不到 `LinkProperties`（API 21 才有），
DHCP 里的 `dns1` 是唯一入口。

**④ `TvDialog.message/confirm` 的参数顺序不能随便排**
Kotlin 尾随 lambda 绑的是**最后一个**参数。全工程有 3 处
`confirm(...) { 退出登录 }` 的写法，把 `onDismiss` 放最后会让那些
「确认逻辑」变成「关闭回调」，点确认毫无反应。`onConfirm` 必须留在最后。

**⑤ `truncate()` 切中文要按 rune 退**
按「最后一字节是续字节」判法挡不住「切在多字节序列首字节」的情况
（测试抓到 `"封\xe9…"`）。正解：`for !utf8.ValidString(cut) { cut = cut[:len(cut)-1] }`。

---

## 7. 还没做的

- 报告里没有截图（电视 `screencap` 输出 0 字节，这条路在真机上不存在）。
- 没有自动上报：只在用户主动点时上传，崩溃不会自己发。
- 后台没有趋势图，只有按设备分组的计数。
