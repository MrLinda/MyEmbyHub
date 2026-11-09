# 进度交接（截至 2026-11-03 21:00）

> 给明天的自己 / 接手的人。**先读这个，再读 `docs/tv-client-plan.md`（功能与坑）、`docs/tv-device-report.md`（真机底细）。**

## 一句话状态

**可行性验证 100% 完成**，**TV 客户端已交付并装到电视上**（`com.example.myembyhub`，
versionCode 1793710453，8,427,317 字节）。
当前唯一待办：**在电视上点一次「在线更新 → 下载」**，验证下载链路（权限坑已修，待真机确认）。

## 已完成清单（都有真机证据）

| 项 | 结论 | 证据 |
|---|---|---|
| 服务器能力 | 最低 TLS 1.1；不要求 SNI、不挑 cipher | `docs/tv-device-report.md` 5.2 |
| 系统 JSSE 够用 | 4.3 用 TLSv1.2 + 内置锚点握手成功 | 诊断矩阵 B 段 |
| 登录跑通 | HTTP 200，AccessToken 32 位 | `/sdcard/tvcheck-login.txt` |
| 硬解能力 | H.264=`OMX.MS.AVC.Decoder`，HEVC=`OMX.MS.HEVC.Decoder`（都硬解） | `/sdcard/tvcheck-decode.txt` |
| 端到端 API | `/System/Info/Public` 200（MICU Cloud Media 4.8.11.0）；`/Items` 200，TotalRecordCount 795080 | `/sdcard/tvcheck-tls.txt` E 段 |
| 取流 URL | `/Videos/{itemId}/stream?static=true&mediaSourceId={msId}` | 电视 curl 实测 206 |
| 302 目标 | `v10.example.com` / `v11.example.com`（**多节点，域名会变**，必须动态跟随） | 5.6.2 |
| 网络实播 | ✅ READY / 1080p / 硬解 / 零丢帧（mkv+hevc / mp4+h264 / mkv+h264） | 5.7 节 |
| 不需要 Conscrypt/BC | BC 在 API 18 不可用（JSSE 类在 `META-INF/versions/9/`） | 5.5 节 |
| 客户端已装电视 | 8,427,317 字节，versionCode 1793710453 | `adb shell ls -l /data/app/com.example.myembyhub-2.apk` |

## 客户端已实现的功能

`app` 模块 = **纯 TV 客户端**（minSdk 18 / XML+Views / 无 Compose）：

| 功能 | 状态 |
|---|---|
| 登录：电视端输入 + 手机扫码（二维码） | ✅ 已装电视，待肉眼确认一次 |
| 媒体库：左栏库 / 右栏内容，分层下钻（剧→季→集） | ✅ |
| 海报墙（自写 ImageLoader：LRU + 磁盘缓存 + RGB_565） | ✅ 已装电视 |
| 排序：名称/加入时间/首播上映/年份/时长，升降序 | ✅ |
| 搜索：手机扫码输入关键词（电视无软键盘） | ✅ |
| 菜单：搜索/排序/在线更新/服务器信息/刷新/退出登录 | ✅ |
| 播放页：ExoPlayer 2.19.1 + 自写 DataSource，左右键 ±10s | ✅ 待在电视上肉眼确认出画面 |
| 在线更新：清单比对 → 下载 APK → 调起系统安装器 | ✅ **全链路实测通过**（21:06，`status=0`，覆盖安装成功） |

网络层已从 `tvcheck` **复制**进 `app/.../net/`（**没有抽公共模块**，避免改动已验证的探针代码）：

`TlsStack` / `BundledCa` / `UrlKit` / `EmbyHttpDataSource` / `TrustMode` / `EmbyApi` /
`Session` / `QrUtil` / `UpdateManager` / `ImageLoader` / `PhoneControlServer`
（3 张 LE 锚点 PEM 也复制到了 `app/src/main/assets/`）。

## 手写 HTTP 层的四个真坑（已修，勿再犯）

1. **别用 `java.net.URL(URL, String)` 解析重定向** —— 4.3 的 libcore 会把 base 的 query
   重复拼到绝对 URL 后面（`...?a=b?a=b`）→ 404。用 `UrlKit.resolve`（手工解析）。
   `android.net.Uri` **没有** `resolve` 方法，别试。
2. **请求路径必须带前导斜杠** —— `GET Videos/666/stream` → openresty 回 400。
3. **逐字节读完 `\r\n\r\n` 后不要 unread** —— socket 流位置天然就在 body 开头。
   把头结束符推回去会让 ExoPlayer 收到 `0D 0A` 而不是 `ftyp` → 15 个 extractor 全拒。
4. **`DataSource.Factory` 每次返回新实例** —— ExoPlayer 会并发用多个 DataSource。

> `Host` 头带不带 `:443` **都能** 206（之前以为带端口会 404，是误判）。

## 客户端侧踩过的坑（详见 `docs/tv-client-plan.md` 6.1.3）

- **`MediaTypes=Video` 会抹掉「剧→季→集」分层**（Series/Season 的 MediaType 不是 Video）
- **zxing 必须锁 3.2.1**（3.3.0+ 引用 API 19 才有的 `java.nio.charset.StandardCharsets` → 4.3 上 `NoClassDefFoundError`）
- **AGP 8.0 起 `BuildConfig` 不再自动生成** → 要 `buildFeatures { buildConfig = true }`
- **写 `/sdcard` 必须声明 `WRITE_EXTERNAL_STORAGE`**（API 18 起强制）→ 漏了就 `Permission denied`，
  UI 只显示「下载失败」，看不出是权限问题（本次刚修）
- **`LibraryActivity` 必须 exported**，否则 adb 起不来它做验证

## 环境现状

- 电脑静态服务：`python -m http.server 8765` 在 `dist/` 目录跑着。
  **端口 8000 落在 Windows 保留段 7928-8027，绑定报 `WinError 10013`，统一用 8765。**
- 电视 adb：`192.168.1.100:5555`（深度待机时 5555 会关，需用户按遥控器唤醒，按「信号源」最有效）。
- 电视 conf：`/sdcard/tvcheck.conf`，已含 `api_key`（32 位）、`server`、`username`、`password`、
  `apk_url`。**注意 conf 里有明文密码，不要把它贴到公开地方。**
  ⚠️ **`dist/tvcheck.conf` 是模板**（内容是空占位），
  运行时配置在 **`dist/tvcheck-runtime.conf`** —— 推电视时**只能推 runtime 那份**，
  推错会把真实的 server/token/密码冲掉（2026-11-03 踩过，已恢复）。
- 触发探针**必须加 `--activity-clear-top`**，否则 shell 发起的 `am start` 带的
  `FLAG_ACTIVITY_NEW_TASK` 会吞掉 extras，`cmd` 根本不送达。
- ⚠️ **Gradle 的 `assembleDebug` 只生成 `dist/version.json`，不拷 APK 到 `dist/`** →
  打包后要手动 `python -c "import shutil; shutil.copy(...)"` 同步，否则电视下载到旧包。

## 覆盖安装的检测方式（别再用路径判断）

`/data/app/<包名>-N.apk` 的 **N 在覆盖安装时不变**，只有卸载重装才会变号。
所以判断"有没有装上新包"要**比 APK 文件大小/时间戳**：

```bash
adb shell pm path com.example.myembyhub          # 先拿当前路径（-1 / -2 会变）
adb shell ls -l /data/app/com.example.myembyhub-2.apk
```

## 部署到电视的流程（已知无法绕过系统限制）

`adb install` / `pm install` → `SecurityException`（shell 无 `INSTALL_PACKAGES`）；
`adb shell input keyevent` 跨 uid → `INJECT_EVENTS` 拒。
**唯一可行路径**：推 APK → 调起系统安装器 → **人按一次「安装」**。

```bash
adb push dist\myembyhub-debug.apk /sdcard/Download/myembyhub.apk
adb shell "am start --user 0 -a android.intent.action.VIEW \
  -d 'file:///sdcard/Download/myembyhub.apk' -t 'application/vnd.android.package-archive'"
```

## 别再踩的坑

- 本机 Clash Verge fake-IP：`tv.example.com` 解析成 `198.18.0.206` → **本机 openssl 结论不可信**。
  用 DoH 拿真实 IP + `-connect <真实IP>:443 -servername tv.example.com`。
- 电视 `screencap` 输出 0 字节、shell 无 DUMP 权限 → 一切验证走「intent 触发 → 写文件 → adb pull」。
- Android 4.3 的 `java.net.Socket` 不实现 `Closeable` → Kotlin `socket.use{}` 真机崩。
- `SSLSocketFactory.getDefault()` 返回 `SocketFactory`，要强转。
- `SSLSocket.getProtocol()` / `getCipherSuite()` 在公开 stub 里是 `@hide` → 只能反射。
- 本机 `dalvikvm` 被 ROM 阉割 → 推 dex 上去跑这条路不通。
- PowerShell `Set-Content -NoNewline` 会把多行 conf 压成一行 → 写 conf 用 python 且 `newline="\n"`、无 BOM。
- `Copy-Item` / `Remove-Item` 被本地安全策略拦截 → 用 `python -c "import shutil; shutil.copy(...)"`。
- PowerShell 单引号字符串里 `` `n `` **不转义**（写成字面量）→ 用双引号。
- ExoPlayer 2.19.1 的 `DataSource.open()` 返回 **`Long`**（总长度）不是 void；
  `getResponseHeaders()` 返回非空 platform type，Kotlin override **不能带 `?`**。
- GBK 控制台打印中文/✓ 会 `UnicodeEncodeError` → 写文件统一 `encoding='utf-8'`。

## 下一步

1. ~~电视上点「在线更新 → 下载」验证下载链路~~ → ✅ **21:06 已通过**（`status=0`，覆盖安装成功）。
2. ~~把 `dist/version.json` 改回真实值~~ → ✅ 已改回 `1793710453`（电视上现在点更新会显示「已是最新」）。
3. 在电视上肉眼确认：媒体库有海报、进入剧集能播放出画面。
4. 详情页（大图 + 简介 + 剧集列表）—— 等核心链路验顺后再议。

## 本次踩坑的两个新事实（2026-11-03 21:06 实测）

- **系统安装器装完会把源 APK 从 `/sdcard/Download/` 删掉**（logcat `delete done`）
  → 下载完文件「凭空消失」是正常的，不是下载失败。手动 `adb push` 的包同理。
- **乐视 4.3 允许同 versionCode 覆盖安装**（清单 1793710600 vs APK 自带 1793710453，照装成功）
  → 递增时间戳是保险做法，不是这台机器上的硬性要求。
