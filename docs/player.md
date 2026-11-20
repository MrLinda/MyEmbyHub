# 播放页（PlayerActivity）

全屏播放。ExoPlayer 2.19.1 + 自写 DataSource，遥控器是唯一的输入设备。

**控制栏是关掉的**（`app:use_controller="false"`）—— 原因见第 2 节末尾。

## 1. 按键表

| 键 | 行为 | 备注 |
|---|---|---|
| ← / → | 后退 / 前进 10 秒 | 长按连发改成 5 秒小步，免得按住不放一路退到底 |
| ↑ / ↓ | 上一集 / 下一集 | 到边界给 toast，不静默吞键 |
| 菜单键（按一下） | 打开播放页菜单：选集 / 字幕 / 诊断信息 | 2026-11-19 改的：以前是「连按三次出诊断面板」，但播放页真正需要随手够到的是**换集**和**开字幕**，诊断是调试用的 |
| 确定 / 播放暂停键 | 播放 ↔ 暂停 | 暂停时画面正中显示「已暂停」；播完按确定 = 从头重播 |
| 返回 | 退出播放页 | 先 `reportStop()` 把进度写回 Emby |
| 其它没认出的键 | 屏幕正中弹「未识别按键 NNN」 | 专为「遥控器上这个键到底发什么」准备的，见第 7 节 |

左右跳转时会浮出一行 `▶ 08:42 / 45:10`（`tv_seek`），1.2 秒后消失。
控制栏关了之后，这是唯一能看到「现在播到哪儿」的地方。

## 2. ⚠️ 方向键必须在 `dispatchKeyEvent` 里截，不能写在 `onKeyDown`

**这是 2026-11-19 踩过的坑。** 最初上下键切集写在 `onKeyDown` 里，
代码看着完全正确、编译也过，但真机上按 ↓ **一点反应都没有，logcat 一行都不打**。

原因：全屏播放时焦点在 `PlayerView` 上，它的控制器 `PlayerControlView`
先把上下左右消费掉了 —— `onKeyDown` 只是兜底，根本轮不到。
`KEYCODE_MENU` 恰好没被控制器吃，所以「按三次出诊断」看着是好的，
把这个 bug 完美掩盖了：一半功能正常，另一半静默失效。

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val mine = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_MENU
    if (!mine || player == null) return super.dispatchKeyEvent(event)
    when (event.action) {
        KeyEvent.ACTION_DOWN -> if (handlePlayerKey(event)) {
            consumedKey = event.keyCode      // 记住，抬起的也要吞
            return true
        }
        KeyEvent.ACTION_UP -> if (consumedKey == event.keyCode) {
            consumedKey = -1                 // 只吞按下会让系统报
            return true                      // InputEventConsistencyVerifier: ACTION_UP but key was not down
        }
    }
    return super.dispatchKeyEvent(event)
}
```

只吞 `ACTION_DOWN` 不吞 `ACTION_UP` 还有第二个后果：那个键的抬起会漏给视图树，
可能触发一次多余的点击。

排查手法（模拟器上完全不看界面也能确认键有没有被收到）：
```
adb shell input keyevent 20          # ↓
adb logcat -s PlayAct:*              # 有"起播：第 N 集"就是收到了，什么都没有就是没收到
```

## 2.1 为什么把 ExoPlayer 的控制栏整个关掉

自带控制栏上有 ⏮ ⏪ 播放 ⏩ ⏭ 五个按钮。在这台电视上：

- **方向键全被 `dispatchKeyEvent` 吃掉了**，焦点根本走不到按钮上；
- **没有触摸屏**，点也点不了。

→ 那五个按钮是**永远按不到的**。留着只会让人以为能按，按了没反应又以为是 bug。
`app:use_controller="false"` 关掉之后，原来它负责的三件事得自己接管：

| 原来控制栏干的 | 现在谁干 |
|---|---|
| 播放 / 暂停按钮 | 确定键 → `togglePlayPause()` |
| 进度条 + 时间 | 左右键 → `tv_seek` 浮一行 `▶ 08:42 / 45:10` |
| 显示时顺带露出标题 | `showInfoBriefly()` 自己计时 3.5 秒 |

⚠️ **暂停时必须显示「已暂停」**（`tv_pause`）。控制栏关掉之后，暂停在屏幕上
什么都不会变（画面本来就是静止的），用户没法区分「暂停了」和「卡住了」，
也判断不出按键到底有没有生效。这是关控制栏唯一真正需要补的东西。

## 2.2 ⚠️ 字幕每行后面垫着一条不透明黑底

**2026-11-19 用户报的**：「上次我在电视上试的时候发现字幕有黑色背景，不是透明的」。

**真凶是系统的无障碍字幕设置，不是字幕文件。** 这一条是靠 A/B 实验定下来的
（我第一版猜的是 ASS 的 `BackColour`，猜错了，下面记下为什么）：

| 组 | 设置 | 结果 |
|---|---|---|
| A | 不碰样式（原始行为） | 有黑条 |
| B | `setStyle` 底色给 `Color.RED` | **红条** —— 证明底色确实归 `CaptionStyleCompat` 管 |
| C | `setApplyEmbeddedStyles(false)` + 底色 `TRANSPARENT` | **无黑条**（白字黑边） |
| D | ASS 里写 `BackColour=&H000000FF`（不透明红） | **红条没出现** —— ASS 的 `BackColour` 压根不参与渲染 |

D 组是关键否证：`exoplayer-extractor-2.19.1` 的 `SsaDecoder` / `SsaStyle` 里
**没有 `BackColour` / `PrimaryColour` / `Colour` 这些字符串**（解包 `classes.jar` 扫过），
它只读 `BorderStyle` / `Alignment` 之类的位置信息，颜色一律不读。
所以「字幕文件带了黑底」这个说法是错的。

真因在 `SubtitleView.setUserDefaultStyle()`：`PlayerView` 的构造函数里会调它，
它读 `CaptioningManager.getUserStyle()` —— 也就是**系统设置里的字幕样式**。
系统字幕功能一旦启用、且底色是黑的（很多电视/盒子默认如此），
所有走 `SubtitleView` 的字幕都会带那条黑底。客户端不显式覆盖就跟它走。

修法（`PlayerActivity.onCreate`，必须在 `playerView.player = p` 之后调，
`PlayerView` 的构造里已经先把系统样式打进去了）：

```kotlin
playerView.subtitleView?.let { sv ->
    sv.setApplyEmbeddedStyles(false)      // 不让字幕文件决定字号/样式
    sv.setApplyEmbeddedFontSizes(false)
    sv.setStyle(CaptionStyleCompat(       // 显式覆盖掉系统字幕样式
        Color.WHITE,                      // 字色
        Color.TRANSPARENT,                // 底色 ← 要修的就是这个
        Color.TRANSPARENT,                // 窗口色（4.3 上不用）
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        Color.BLACK,                      // 描边色
        null
    ))
}
```

- **`setStyle` 这一句才是修黑底的那一句**，`setApplyEmbeddedStyles(false)` 修的是别的
  （避免字幕文件改字号/斜体）。两件事别混。
- **描边不能省**。透明底之后白字压在白墙/雪景上会糊成一片，
  黑描边是唯一能同时兼顾亮暗画面的办法（电视台字幕也是这么做的）。
- 代价：丢掉字幕里的斜体/彩色。电视剧字幕基本用不到，换来的是「任何画面都清楚」。
- ⚠️ `setApplyEmbeddedStyles` 是**只写属性**（只有 setter 没有 getter），
  Kotlin 里**不能**写成 `sv.applyEmbeddedStyles = false`，编译期直接
  `Unresolved reference`。只能老老实实调方法名。

### 验证手法（模拟器，不用等电视台）

模拟器的系统字幕默认关着，所以要**主动把黑底打开**来复现：

```bash
adb shell settings put secure accessibility_captioning_enabled 1
adb shell settings put secure accessibility_captioning_background_color -16777216  # 0xFF000000
adb shell settings put secure accessibility_captioning_foreground_color -1
adb shell settings put secure accessibility_captioning_edge_type 1
```

再播一个带字幕的片子截图：修好之后应当**只有白字黑边、没有黑条**。
验完记得关掉（`settings put secure accessibility_captioning_enabled 0`）。

正向对照（想确认「底色到底归谁管」时最有用）：把 `setStyle` 的底色临时改成
`Color.RED`，应当出现**红条**；不出现说明这个 `SubtitleView` 实例根本没被用上。

⚠️ **别用 ASS 的 `BackColour` 做这个实验** —— 它压根不参与渲染（见上表 D 组）。

## 2.3 ⚠️ 内嵌字幕默认压根不会被选中

同一个问题排查时发现的第二件事：`DefaultTrackSelector` **默认不选**「既没有
语言标记、也没有 default/forced 标志」的字幕轨。而绝大多数 MKV 内嵌字幕
（ASS/SRT）恰恰两样都没有 → **片子里明明有字幕，播放器一个字都不显示**，
看着像「不支持字幕」，其实只是没被选中。

```kotlin
val trackSelector = DefaultTrackSelector(this).apply {
    setParameters(buildUponParameters()
        .setSelectUndeterminedTextLanguage(true)
        // ⚠️ 2026-11-19 起还要叠一条：字幕整个默认关掉（理由见第 8 节）。
        //    这两条不冲突 —— 这条决定「开不开」，上面那条决定「开了选哪条」。
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Session.get(this@PlayerActivity).subtitleEnabled))
}
```

⚠️ 别用 `setPreferredTextLanguage("zh")` 去凑：字幕没写 language 时那条规则同样匹配不上。

⚠️ `trackSelector` 要存成 Activity 字段（菜单里切字幕要用），不能只是 `onCreate` 的局部变量。

## 3. 剧集列表从哪来

切集和自动续播都需要「同剧的所有集」。三个入口分两种供给方式：

| 入口 | 列表来源 |
|---|---|
| 详情页（`DetailActivity`） | 本来就有全集列表 → 通过 Intent 直接传 |
| 媒体库（`LibraryActivity`） | 当前层级整层是剧集时才传（混合层传了会让「下一集」跳到别的片子） |
| 首页「继续观看」/ 外部拉起 | 手里只有一个 itemId → 后台补拉 |

传列表用 `EpisodeRef.encode()` 编成一段 JSON 塞 Intent extra（`episodes`），
体积有上限：300 集约 40KB，远低于 Binder 的 200KB 预算（`EpisodeRefTest` 有断言兜着）。

后台补拉是**两步**（`loadEpisodeListAsync`）：

```kotlin
val sid = a.itemDetail(itemId).getOrNull()?.seriesId.orEmpty()
val list = if (sid.isEmpty()) emptyList() else a.episodes(sid).getOrNull().orEmpty()
```

因为播放器手里只有 itemId，`MediaItem.seriesId` 只有列表/详情接口带 `SeriesId` 字段时才有。
两步大约 1~2 秒，正好藏在起播缓冲里。
**失败一律静默**：拉不到就只是「没有切集功能」，正片照播。

列表就绪时 logcat 打 `剧集列表就绪：39 集，当前第 8 集`。

### 3.1 ⚠️ 「当前是第几集」必须单独定位，不能拿 `episodeIndex` 硬算

2026-11-19 真机报「按上直接回第一集了」。看着像切集逻辑跑反了，实际是**两件事叠在一起**：

**① `episodeIndex` 是 `-1` 时，`-1 + delta` 会算出离谱的值。**
`switchEpisode` 原来是 `val next = episodeIndex + delta`，索引没定位到时 `-1 + (-1) = -2`，
越界判定的提示是「已经是第一集了」—— 用户听到这句话，自然理解成「跳回第一集了」。
真实情况是**压根不知道现在播的是第几集**，这两件事必须在提示上分开。

**② 按「季号 + 集号」兜底会稳稳命中第一集。**
`MediaItem.indexNumber` / `parentIndexNumber` 的默认值都是 **0**；
`PlayerActivity` 从「继续观看」进来时自己构造的那个 `MediaItem`（只有 id / 名字 / mediaSourceId）
这两个字段也是 0；接口没给集号时列表里每一集的 `indexNumber` 同样是 0。
于是 `indexOfFirst { 0 == 0 }` **必然命中第 0 条**，也就是第一集。

修法是 `resolveEpisodeIndex()`，三重兜底 + 两道闸：

```kotlin
private fun resolveEpisodeIndex(): Int {
    if (episodeIndex in episodes.indices) return episodeIndex   // ① 入口直接传了列表
    val want = reportItem ?: return -1
    val byId = episodes.indexOfFirst { it.id == want.id }        // ② 按 id 对
    if (byId >= 0) { episodeIndex = byId; return byId }
    if (want.indexNumber > 0) {                                  // ③ 季号/集号兜底
        val candidates = episodes.withIndex().filter {
            it.value.indexNumber == want.indexNumber &&
                it.value.parentIndexNumber == want.parentIndexNumber
        }
        if (candidates.size == 1) { ... return candidates.first().index }
        // 多季剧里每季都从第 1 集编号，parentIndexNumber 缺失时会有多条候选 ——
        // 宁可说「找不到」，也不能瞎挑一条跳到别的季
    }
    return -1
}
```

两道闸：`want.indexNumber > 0`（挡掉「全 0 命中第一集」）、候选必须**唯一**（挡掉跨季乱跳）。

定位不到时给的是「定位不到当前是第几集，暂时不能切集」，
logcat 里还有一行 `切集失败：在 3 集里定位不到当前集（itemId=…）`。

**教训**：默认值也是值。`Int` 字段用 `0` 表示「不知道」时，
任何「相等就匹配」的兜底逻辑都会退化成「匹配第一条」。

## 4. 播完自动下一集

开关在 `Session.autoNextEpisode`（key `auto_next_episode`，**默认开**），
设置菜单里是「播完自动下一集」。

`Player.STATE_ENDED` → `reportStop()` → `main.post { autoAdvance() }`。
放 post 里是为了先让 ENDED 那一帧的状态回调跑完，再拆播放源 ——
在回调栈里直接 `setMediaSource` 会让 ExoPlayer 自己重入。

`autoAdvance()` 三个提前退出的分支**都留了日志**：

```
播完收尾：自动下一集是关的，停在这里
播完收尾：没有剧集列表（不是剧集，或还在拉），不续播
已经是最后一集，不自动续播
```

用户报「没自动下一集」时，一看 logcat 就能分清三种成因，否则只能猜。

## 5. 切集的收尾顺序（切错了会串账）

`playEpisodeAt()` 的顺序不能动：

1. **先 `reportStop()`** —— 它读的是当前的 `reportItem` / `playSessionId`，
   换早了就把旧集的进度记到新集头上，「继续观看」永远停在切走前 10 秒那个位置。
2. `ProgressTick` remove 掉。
3. `PlayStats.finish(..., reason)`，`reason` 是 `switch`（手动切）或 `ended`（播完接）。
4. 换 `episodeIndex` / `tvInfo`。
5. `startEpisodeStream()`：新 `reportItem` + **新 `playSessionId`**
   （沿用旧的会让服务端把两集算成一次播放）+ `PlaybackSnapshot.reset` + `PlayStats.begin`。

## 6. ⚠️ 切集必须清「只判一次」的守卫

`PlayerActivity` 里有一堆一次性状态：`firstFrameRendered`、`decoderInitialized`、
`capabilityBlocked`、`capabilityChecked`、`videoSupported`、`videoTrackCount`、
`videoCodecInfo`、`stallRound`、`diagShown`、`diagLines`、`endedNaturally`、`stopped`。

不清的后果非常具体：从一集 4K HEVC（硬解吃不下）切到 1080p，
`capabilityBlocked` 还是 true、视频层已经被 `clearVideoSurface()` 摘掉了 →
用户看到「有声音、没画面」，而界面还挂着「这台电视的硬解吃不下这个规格」。

所以 `resetPlaybackState()` 一次清干净，并且：
- `main.removeCallbacks(stallCheck)` —— 起播 15 秒 / 20 秒两轮硬解判定的
  Runnable 必须能摘掉，否则旧集的定时器会去判新集。原来那两个 `postDelayed`
  用的是匿名 lambda，**根本 remove 不掉**，为此改成了具名 `stallCheck`。
- 隐藏 `tvSeek` / `tvDiag` / `tvError`。

## 7. 播放页菜单（菜单键按一下）

菜单键按一下就出一个小面板（`TvMenu`），三项：

| 项 | 副标题 | 作用 |
|---|---|---|
| 选集 | 「第 3 集 / 共 24 集」，或「正在取剧集列表…」「这不是剧集」 | 列出全部剧集，当前那集禁用 |
| 字幕 | 「关」/「开（2 条内嵌字幕轨）」/「开（这部片子没有内嵌字幕轨）」 | 见第 9 节 |
| 诊断信息 | 「开：显示在屏幕底部」/「关：不显示」 | 调试用，`tv_diag` |

**为什么从「连按三次出诊断」改成「按一下出菜单」**：诊断是给开发者看原始事件流的，
用户一般用不到；而播放页真正需要随手够到的两件事是**换集**和**开字幕**。
把二者放进同一个菜单，按一下就到。

诊断面板本身没删，只是从「菜单键 ×3」变成「菜单 → 诊断信息」。打开后 `tv_diag`
在屏幕底部（bounds `[0,872][1920,1008]`），内容是原始事件流：
`state=…`、`tracks`、`videoIn`、`cap ok <解码器>`、`decoder=… init=…ms`、
`videoSize`、`FIRST FRAME rendered`、`loadError`。

> 历史：诊断面板最早是「按一下菜单就弹」，因为糊满半个屏幕、用户误触后以为播错了，
> 改成连按三次；现在整个挪进菜单里，连按计数的那套（`MENU_PRESSES_FOR_DIAG` /
> `MENU_SEQUENCE_GAP_MS` / 「诊断 1/3」toast）全部删掉了 —— 那三条 toast 是调试噪声，
> 用户不该看见。

### 7.1 ⚠️ 国产电视遥控器的「菜单」键不一定是 `KEYCODE_MENU`

2026-11-19 真机报「播放里按遥控器菜单没反应」，而模拟器上同一个键好好的。
乐视 S50 Air 这类国产电视的遥控器键码并不统一：有的发 `SETTINGS`(176)，
有的发 `INFO`(165) / `BOOKMARK`(174)，还有的把「菜单」做成四个彩色键。

所以叫出菜单的键是一整组 `DIAG_KEYS`（82 / 176 / 165 / 174 / 183-186 / 170 / 172），
不是单个 `KEYCODE_MENU`。这些键在播放页本来就没别的用途，全收进来没有副作用。

**怎么查真机上某个键发什么** —— 这是关键，因为电视上拿不到 UI 层级、
用户也看不到 logcat：

1. `dispatchKeyEvent` 对**每一个** `ACTION_DOWN` 都打一行 `key NNN(NAME) repeat=0`；
2. 按到的键不在 `KNOWN_KEYS` 里时，**屏幕正中直接弹「未识别按键 NNN」**；
3. 于是用户按一下遥控器上那个键，把 toast 上的数字报回来，照着加进 `DIAG_KEYS` 即可。

两种情况一眼分开：

| 现象 | 结论 |
|---|---|
| 屏幕上弹「未识别按键 NNN」 | 键到了 Activity，只是没认出来 → 加进 `DIAG_KEYS` |
| 屏幕上什么都不弹、菜单也没出 | 键被 Launcher / TV Service 截走了，客户端改不了 |

`keyName()` 手写映射表，**不能用 `KeyEvent.keyCodeToString()`** —— 那是 API 19 才有的，
这台电视 4.3（API 18）一调就是 `NoSuchMethodError`。

验证（模拟器）：
```
adb shell input keyevent 82   # 按一次，播放页菜单直接弹出
adb shell uiautomator dump /data/local/tmp/d.xml
adb shell cat /data/local/tmp/d.xml | Select-String '选集|字幕|诊断信息'
```
`176`（`SETTINGS`）同样能叫出菜单 —— 已验证。

## 8. 字幕：默认关，开关在播放页菜单里

`Session.subtitleEnabled`（`shared_prefs` key `subtitle_enabled`）**默认 false**。

**为什么默认关**：不是省性能，是**会重叠**。很多资源自带压进去的硬字幕，
客户端再把内嵌字幕轨渲染一遍，就是画面下方上下两行字、内容还不一样。
硬字幕只能靠「关掉内嵌轨」避开，播放器没法自动判断。

代价是「这片子明明有字幕却不显示」。所以：

- 菜单里那项会明确写出**这部片子有几条内嵌字幕轨**（`textTrackCount()` 数
  `player.currentTracks` 里 `TRACK_TYPE_TEXT` 的组长度），
  看到「开（2 条内嵌字幕轨）」就知道值得开；
- 开关立刻生效，不用重开播放页 —— `trackSelector.setParameters(
  buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on))`。
  ⚠️ 必须用 `buildUponParameters()` 继承其余参数，整个重建会把
  `setSelectUndeterminedTextLanguage(true)` 一起丢掉，开了字幕也是空。

⚠️ **`trackSelector` 必须是 Activity 字段**，不能是 `onCreate` 里的局部变量 ——
菜单回调里要用它。

⚠️ 「默认关」和 `setSelectUndeterminedTextLanguage(true)` 不冲突：
后者决定「开了之后选哪条」，前者决定「开不开」。两个都要。

实测（模拟器，`dist/sub60.mkv`，60 秒、1 条 ASS 内嵌字幕，`--el start_ms 500` 落在字幕窗口内）：
- `subtitle_enabled=false` → 截图里**一个字都没有**；
- `subtitle_enabled=true` → 画面下方出现字幕，白字黑描边、无黑条（黑底问题见 2.2）。

## 9. 验证用本地短片

模拟器只吃 Baseline profile，线上 1080p High 会 `NO_EXCEEDS_CAPABILITIES`，
所以验证播放器「外壳」逻辑（切集 / 自动续播 / 边界提示）用本地片子最省事：

```bash
ffmpeg -y -f lavfi -i "testsrc=size=640x360:rate=15:duration=8" \
       -f lavfi -i "sine=frequency=440:duration=8" \
       -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -shortest short.mp4
python tools/serve-range.py 8765 dist        # ⚠️ 必须带 Range，http.server 不行
```

```bash
adb shell "am start -n work.luxiaoxiao.myembyhub/.ui.PlayerActivity \
  --es url 'http://10.0.2.2:8765/short.mp4' --es title '第一集' \
  --es item_id a --es media_source_id local --el start_ms 0 \
  --es episodes '[{\"i\":\"a\",\"n\":\"第一集\",\"x\":1,\"p\":1,\"m\":\"local\",\"r\":0,\"t\":20000}]'"
```

⚠️ 别用「seek 到距结尾 30 秒」的方式验自动续播：远端对象存储会回
`HTTP 416 Requested Range Not Satisfiable` → `Source error`，
播放器进错误态而不是 `STATE_ENDED`，看着像「自动下一集没做」。

⚠️ 模拟器截屏 `screencap -p /sdcard/x.png` 会报 `Read-only file system`，
必须写 `/data/local/tmp/`。
