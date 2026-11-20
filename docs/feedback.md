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
| `tls` | TLS **偏好**（用户在设置页选的策略）、**本域名实际用**哪条栈、能力清单来自哪个 `SSLSocket` 类、启用协议、**启用套件清单**（含 GCM / ChaCha20 判定位）、**四条真实握手记录**（每条带用的哪条栈）、内置锚点清单、**按域名记下的 TLS 栈结论** | 见下 |
| `codec` | 视频/音频解码器全表 + **17 个 mime 的真实探测** | 见下 |
| `player` | 最近一次播放：容器/视频编码/音频编码/宽高/码率/解码器名/是否出首帧/错误码/错误原文 | 「黑屏但进度条在走」和「起播就崩」是完全两回事 |
| `errors` | 最近 20 条错误（含未捕获崩溃） | 内存环形缓冲，进程内一直留着 |
| `note` | 用户自己写的那句话 | — |

### 2.1 TLS 为什么要握手四次

「接口能通、取流全挂」在这台机器上有两种成因，只看一次握手分不出来：

```
sni=True  trust=ALL      → 协议/套件能不能协商（排除证书因素）
sni=False trust=ALL      → 服务端是不是「没 SNI 就拒」（挂 CDN 的源站会）
sni=True  trust=BUNDLED  → 2013 年那套 CA 库能不能验过服务端证书
sni=True  trust=ALL      → 内置对照：对反馈服务器再握一次
```

历史上最难查的那个坑（`AGENTS.md` 的「SNI 缺失」条目）症状极具迷惑性：
`HttpsURLConnection` 自带 SNI 所以接口全好，只有自写 DataSource 取流挂，
报错是 `SSL3_GET_RECORD:wrong version number` —— 看起来像证书不对或只支持 TLS 1.3。
有了这三行记录，一眼就能定位。

#### 内置对照：把「连不上」拆成互斥的两种

用户问过「能不能换个稳定的站来测 TLS」。不用换站 —— **反馈服务器自己就是那个站**：
它在线、Let's Encrypt 证书、TLS 1.2、CBC/GCM 都开，还不用多维护一个域名。

所以第四次握手打的是 `Session.feedbackEndpoint` 的主机（用户填的优先，没填走
`BuildConfig.FEEDBACK_URL`）。它回答的问题和前三次不同：

| 对照结果 | Emby 那三次 | 结论 |
|---|---|---|
| 失败 | 失败 | 这台电视连**任何** HTTPS 都费劲 → 客户端/网络侧（典型：Conscrypt 没加载起来，系统栈又没 GCM） |
| 成功 | 失败 | 问题在**那台服务器**的配置（只开 GCM / 证书链 / 端口） |
| 成功 | 成功 | TLS 这一层没问题，往播放链路查 |

跳过对照的三种情况会写进 `tls.note`：服务器未知、反馈地址不是 https、
反馈服务器与 Emby 同一主机（对照无意义）。

#### 为什么要记「启用套件清单」而不只是套件数

「套件数 = 54」这种数字什么都说明不了。真正要回答的是
**这台机器的 TLS 栈里到底有没有 GCM**：

- 有 GCM，服务器只开 GCM 却连不上 → 服务器配置问题
- 没有 GCM，服务器只开 GCM → 客户端问题（4.3 的系统 JSSE 只有 CBC）

所以报告里带 `cipherSuites` 全清单（截前 80 条）+ `hasGcm` / `hasChaCha20` 两个判定位，
外加 `socketClass`（上面这份清单来自哪个 `SSLSocket` 类 ——
`org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter`
就是 Conscrypt 走通的铁证）。

⚠️ **「偏好」和「实际」是两个字段，别混**：

| 字段 | 回答什么 |
|---|---|
| `tls.impl` | 用户在设置页选了什么策略（自动 / 锁 Conscrypt / 锁系统栈） |
| `tls.implActual` | **这个域名实际用了哪条栈**（`自动` 模式下不同域名可能各走一条） |
| `tls.socketClass` | 上面那份能力清单来自哪条栈 |
| `tls.handshakes[].impl` | 那一次握手用的是哪条栈（换过栈的话记的是**换之后**那条） |

`impl` 只说明策略、不说明结果。实测就撞上过这种组合：
`impl=Conscrypt（自动）` 但 `implActual=系统 JSSE（按域名记录）` ——
因为 `eb.atrct.cn` 两条栈都谈得拢，按「新域名先试系统库」的记录走了系统栈。
只看 `impl` 会以为跑的是 Conscrypt。

⚠️ **探针也走真实策略**（`TlsStack.chooseFor` + 失败换栈重试），不是固定用偏好那条栈。
早期版本探针固定用偏好栈，于是出现过「探针报 Conscrypt、真实请求走系统栈」的自相矛盾 ——
一台用得好好的电视会被报成「握手失败」。

⚠️ **第一次握手要给更长的超时**（2026-11-19 实测）：第一次 8 秒整超时失败，
紧接着同参数的第 2、3 次各 1 秒成功 —— 差的是 Conscrypt 原生库加载 + JIT 的钱。
报成「失败」会让整份报告得出反向结论，所以第一次给 20 秒（`FIRST_PROBE_TIMEOUT`）。

### 2.2 按域名记住「哪条 TLS 栈谈得拢」

这台机器上有两条 TLS 栈，**哪条能跟某个服务器谈成，事先不知道**：

- 系统 JSSE（2013 年的 Harmony/OpenSSL）：只有 CBC，没有 GCM / ChaCha20 / TLS 1.3；
- Conscrypt（BoringSSL）：GCM / ChaCha20 / TLS 1.3 全有，但要加载 8MB 原生库。

现代服务器大多只开 GCM，所以系统栈经常谈不拢 —— 但也不是全部
（实测 `eb.atrct.cn` 两条栈都能连，`cf.wedg.top:1234` 只有 Conscrypt 行）。
于是「先试哪条」本身就是个赌，赌输的代价是一次 1~3 秒的失败握手。

客户端把成本压到**每个域名只有一次**：

```
第一次连 A → 按策略试一条 → 谈不拢 → 换另一条重试 → 成功 → 记下结论
以后连 A   → 直接照记录走，不再付失败握手
```

记录落在 `SharedPreferences`（`myembyhub_tls_hosts`，键 `map`，形如
`eb.atrct.cn=conscrypt;cf.wedg.top=conscrypt`），最多 24 条、按时间淘汰最老的。
**不放内存表**：这台电视的进程被 `ActivityManager-Killer` 杀掉是常态，只放内存等于没记。

规则细节：

| 情况 | 处理 | 为什么 |
|---|---|---|
| 握手谈不拢（`SSLHandshakeException` / `handshake_failure` / `wrong version number`） | 换栈重试一次，成功则记录 | 这才是换栈能救的那一类 |
| 证书校验失败 | 不换栈，不记录 | 换栈也救不回来，还会把真因埋掉 |
| DNS 错 / 连不上 / 超时 | 不换栈，不记录 | 压根没走到 TLS，跟哪条栈无关 |
| 握手过了但接口报错（401、JSON 解析失败…） | **记录** | 说明这条栈能把 TLS 谈成，只是业务层出问题 |

反馈报告里的 `tls.hostMemory` 就是这张表的快照（`域名=实现` 的字符串数组）。
它回答的问题很具体：**「用户那台机器上，这个服务器到底是被哪条栈连上的」** ——
设置页写的「自动」只说明策略，不说明结果。

设置页可调：**设置 → TLS 实现** 里多了「新域名先试系统库」（**默认开**）和
「已记录的域名」（显示条数，点一下清空）。

默认开是因为**系统栈能用就该用它**：Conscrypt 要映射 8MB 原生库、首次调用还要加载 + JIT，
在这台 1.5GB / 96MB 堆的电视上是实打实的开销，而且系统栈是「平台自己在走的那条路」，
出问题概率最低。代价只是每个没见过的域名第一次可能多付一次失败握手 ——
靠 `TlsHostMemory` 记住之后就不再付，实际域名就那几个（Emby 服务器 + 取流 / 海报主机）。

实测也支持这个默认值：`eb.atrct.cn` 两条栈都能连（系统栈走 TLSv1.2 + CBC），
这种情况把 Conscrypt 拉起来纯属白费。只有只开 GCM/ChaCha20 的服务器才需要它。

切换它或手动锁定实现时都会**清空已有记录** —— 旧记录是按旧策略试出来的，留着会误导。
已经手动存过 `false` 的安装保持 `false`（那是用户自己选的，不该被默认值覆盖）。

### 2.3 解码器为什么要真去建

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

### 2.4 为什么慢（2~6 秒）

因为它是**真的做事**：四次 TLS 握手（每次要建 socket、协商、取证书链）、
17 次解码器创建、读 `/proc/meminfo`、遍历网络接口。不是读几个系统字段交差。
所以必须在后台线程跑，而且要**给出真实进度** —— 光转个圈用户会以为卡住了。

进度按阶段推：读设备 4% → 网络 20% → 三次握手 22/32/42% → 内置对照 52%
→ 解码器逐个探测 62~86% → 整理播放记录 88% → 打包 92% → 上传 94% → 等服务器确认 97%。
`DeviceReport.collect/upload` 都接受一个 `onProgress: ((Int, String) -> Unit)?`，
百分比只**向前**走（同一阶段重复回调不会倒退），文案同步说「正在测试解码能力（7/17）」
这种具体进度。界面用 `ui/TvProgressBar.kt`（自绘确定进度条）+ 百分比文字，
不用系统 `ProgressBar`（老机器上 indeterminate 那个转圈动画本身就是卡顿来源之一）。

---

## 3. 客户端实现

| 文件 | 作用 |
|---|---|
| `net/DeviceReport.kt` | 采集 + 上报。数据结构字段名 = 服务端 `report.go` 的 json tag，改一处必须改两处 |
| `net/PlaybackSnapshot.kt` | 最近一次播放的内存快照（`object`，进程内一直留着） |
| 同文件里的 `ErrorLog` | 最近 20 条错误的环形缓冲 + 全局未捕获异常钩子 |
| `ui/FeedbackUi.kt` | 三步对话框：填备注 → 采集上传（带百分比进度条）→ 结果 |
| `ui/TvProgressBar.kt` | 自绘确定进度条（老机器上的系统 ProgressBar 转圈本身就卡） |
| `net/PlayStats.kt` | 播放打点：从 `PlayerActivity` 收事件，播放结束时异步 POST 一条 `/api/v1/play` |
| `net/TlsHostMemory.kt` | 按域名记住「哪条 TLS 栈谈得拢」，落 `SharedPreferences`（见 2.2） |
| `net/TlsConn.kt` | 带换栈重试的一次 HTTPS 请求。**所有走 TLS 的链路都该包这一层** |
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

或命令行：`gradlew :app:assembleRelease '-PfeedbackUrl=https://你的域名'`
（本地调试用 `assembleDebug` 也行，发版必须 `assembleRelease` —— 见 `AGENTS.md` 7.1）。
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

### 4.1 后台管理面板

入口 `https://<feedbackUrl>/admin`，**HTTP Basic 认证**（账号在服务端的 `.env`
里：`FEEDBACK_ADMIN_USER` / `FEEDBACK_ADMIN_PASS`）。页面全部服务端渲染，
不依赖前端框架 —— 老机器、手机、电视浏览器都能打开。

| 页面 | 干什么 |
|---|---|
| `/admin`（看板） | 总数 / 今日 / 近 7 天 / 待处理 / 已解决 / 设备数 六张卡；14 天柱状图；筛选 + 列表 + 分页 |
| `/admin/reports` | 同上，纯列表（看板就是它的别名） |
| `/admin/report/{id}` | 单份详情：设备 / 屏幕与内存 / 网络与客户端 / 最近一次播放 四张卡，异常、解码器、解码探测、TLS 握手三张表，同型号历史，原始 JSON |
| `/admin/stats` | 30 天趋势 + 按型号 + 按客户端版本 |
| `/admin/perf` | **播放性能**：首帧均值/中位数/P90、出错率、掉帧每分钟、重缓冲；按机型 / 按版本 / 最近 50 次明细 |
| `/admin/export.csv` | 全量导出（带 UTF-8 BOM，Excel 直接开） |

- **筛选**：`?model=` `?version=` `?status=` `?q=`（模糊搜描述/备注/型号/版本/IP）
  `?days=`（最近 N 天）；分页 `?limit=&offset=`。
- **处理状态**：`new` 待看 / `seen` 已看 / `fixed` 已解决，可在详情页直接改，
  另有一栏「我的备注」只存在服务端，不上报、不回传客户端。
  首次离开 `new` 时自动写 `handled_at`，切回 `new` 会清空。
- 新增字段走的是**幂等迁移**：启动时 `pragma_table_info('reports')` 逐列检查，
  缺哪列补哪列（SQLite 没有 `ADD COLUMN IF NOT EXISTS`），老库直接升级，不丢数据。

### 4.2 播放打点（`/api/v1/play`）

用户点反馈是「一年点不了几次」，而「卡不卡」要看长期曲线，所以除了反馈之外
还有一条**静默上报**：客户端每次播放结束（退出 / 播完 / 出错）自动发一条，
不弹框、失败静默、只打 `Log.i`。

| 字段 | 含义 |
|---|---|
| `clientVersion` / `versionCode` / `model` / `release` / `sdkInt` / `abi` | 谁在播 |
| `itemTitle` / `itemId` / `container` | 播的什么 |
| `videoCodec` / `audioCodec` / `width` / `height` / `bitrateKbps` | 视频规格 |
| `decoder` | 实际建起来的解码器名（如 `OMX.MS.HEVC.Decoder`） |
| `firstFrameMs` | 首帧耗时；**-1 = 这次没出画** |
| `playedMs` / `wallMs` | 真正播放的时间 / 从点开到最后的总时长 |
| `droppedFrames` / `rebufferCount` / `rebufferMs` | 掉帧累计、重缓冲次数与总时长 |
| `errorCode` / `errorMessage` / `reason` | 出错码、原始报错、`exit`/`ended`/`error` |

- **只发数字和片名**，不发进度、不发账号、不发片库清单。
- 服务端 `play_events` 表**与 `reports` 分开**：一份报告近百 KB，一条打点几百字节，
  混在一起两件事都难做；打点只留 90 天（`PlayRetention`），报告留 180 天。
- 空心跳（连机型、片名、版本号都没有）直接回 400 丢掉。
- ⚠️ **JSON tag 必须逐字对齐**：客户端键是 `model` / `release` / `sdkInt`
  （沿用 `DeviceReport` 的风格），而 Go 字段叫 `DeviceModel` / `AndroidRel` ——
  `PlayEvent` 上不写 `json:"model"` 之类的 tag，`json.Unmarshal` 会**静默丢字段**，
  后台里显示成「（未知）」且没有任何报错。这类 bug 只能靠截图看出来。

---

## 5. 验证过的（模拟器 AVD `LeTV` / API 18 / 1920×1080 / 240dpi）

一次真实上报（编号 #10，2026-11-19）收到的内容：

```
device : unknown Android SDK built for x86  sdk=18  abi=x86
client : 1.0.20261119.30（正式 release 包，固定密钥签名）
screen : 1920x1008 @240dpi 60Hz   (dp 1280x672)
memory : total=1515MB avail=1148MB heap=96MB
network: mobile/LTE  ip=10.0.x.x
tls    : impl=Conscrypt（自动）  actual=系统 JSSE（按域名记录）
         socket=org.conscrypt.PreKitKatPlatformOpenSSLSocketImplAdapter
         启用套件 18 条  GCM=有 ChaCha20=有
         eb.atrct.cn                sni=True  [系统 JSSE] ok TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
         eb.atrct.cn                sni=False [系统 JSSE] ok TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
         eb.atrct.cn                sni=True  [系统 JSSE] ok TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA（内置锚点验签）
         myembyhub.luxiaoxiao.work  sni=True  [系统 JSSE] ok TLSv1.2 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA（内置对照）
         hostMemory=eb.atrct.cn=system
codec  : video=4 audio=9 probes=17
player : 「这次开机还没播过东西」
```

⚠️ 这里 `ip=10.0.x.x` 就是脱敏后的样子 —— 真实值 `10.0.2.15` 不会离开设备。

⚠️ **协商结果会随 TLS 栈变**：同一个 `eb.atrct.cn`，系统 JSSE 下是
`TLSv1.2 / ECDHE_RSA_WITH_AES_128_CBC_SHA`，Conscrypt 下直接上 `TLSv1.3`
（`TLS_AES_256_GCM_SHA384`）。所以报告里「协议 / 套件」这两列必须连同
**每条握手自己的栈名**一起看，单看一列会得出错结论。

⚠️ 上面这份还演示了 `impl` 与 `implActual` 的差别：**偏好写的是 Conscrypt，
实际走的是系统 JSSE** —— `eb.atrct.cn` 两条栈都谈得拢，按「新域名先试系统库」
的记录用了系统栈。`socketClass` 仍是 Conscrypt 的类名，因为能力清单取的是
「自动模式下优先的那条栈」，它回答**具备**什么能力，不回答这次**用了**什么。

**换栈重试实测**（`server=https://cf.wedg.top:1234`，`probe_system_first=true`，记录已清）：

```
W/TlsConn: cf.wedg.top 用 SYSTEM 握手失败，换栈重试: SSLHandshakeException: ...
I/TlsStack: SYSTEM 握手失败，换 CONSCRYPT 重试
I/TlsHostMemory: 记住 cf.wedg.top → CONSCRYPT（共 1 条）
```

冷启动第二次直接 `TLS 使用 Conscrypt`，**零失败握手** —— 这就是 2.2 要的效果。

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

**⑥ `catch` 里把异常翻成返回值，会让「换栈重试」变成死代码**（2026-11-19 实测）
第一版 `DeviceReport.upload` / `PlayStats.post` 都是 `catch (e) { Result.Fail(...) }`，
而 `TlsConn.withRetry` 靠**捕获异常**才知道握手失败了 —— 异常在更里层就被吃掉，
换栈分支永远走不到。表现极具迷惑性：功能看着正常（成功时当然正常），
只有遇到「只开 GCM 的服务器」才暴露，而那时日志里什么线索都没有。

→ 规矩：**`TlsConn.withRetry` 包住的 `block` 必须把握手异常往外抛**。
业务失败照旧返回值，但要先过一道
`if (TlsStack.isHandshakeFailure(e)) throw e`。
验证手法：`probe_system_first=true` + 清空记录 + 连一台只开 GCM 的服务器，
日志里应出现 `用 SYSTEM 握手失败，换栈重试` → `SYSTEM 握手失败，换 CONSCRYPT 重试`，
且 `myembyhub_tls_hosts.xml` 立刻多出 `该域名=conscrypt`。

---

## 7. 还没做的

- 报告里没有截图（电视 `screencap` 输出 0 字节，这条路在真机上不存在）。
- 没有自动上报：只在用户主动点时上传，崩溃不会自己发。
- 后台没有趋势图，只有按设备分组的计数。
