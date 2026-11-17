# 详情页（DetailActivity）

从海报墙点一部片之后落地的那一页。以前是 `TvDialog` 弹窗，2026-11-17 改成整页。

## 1. 为什么从弹窗改成整页

弹窗面板固定 560dp 宽（`TvDialog` 的尺寸）。详情要同时塞下：

- 海报、标题、年份/时长/进度、类型/评分/分级
- 三五行的简介
- 「播放 / 继续播放第 N 集」按钮
- 一部剧可能 40 集的选集列表

560dp 宽里简介只能露三行、选集还得在弹窗内部再套一层横向滚动 ——
遥控器在「弹窗 → 简介 → 内层滚动 → 剧集」之间来回跳，用户会迷路。

整页之后横向空间 1280dp（1920px / 240dpi），一屏能把上面全部摊开。

## 2. 结构

```
FrameLayout  surface_base
├ ImageView  img_backdrop    整屏剧照，alpha .55（没有剧照时退回海报，alpha .28）
├ View       bg_detail_scrim 左边纯黑 → 右边透明的水平渐变
└ LinearLayout（垂直居中）
   ├ LinearLayout（横）
   │  ├ FrameLayout 240×360  img_poster + tv_poster_ph
   │  └ LinearLayout
   │     ├ tv_title     36sp bold，最多两行
   │     ├ tv_meta      「2026 · 电影 · 32:12 · 已看 12:34」
   │     ├ tv_genres    「动画 / 家庭 · 6.9 分 · G」（暖色，没有就 GONE）
   │     ├ tv_overview  正文，最多 6 行
   │     ├ tv_tech      「MKV · H264 · 1920×1080 · 2.1 GB」
   │     └ btn_play / btn_back
   └ page_episodes（剧集才有）
      ├ tv_episodes_title「选集」
      └ row_episodes  RecyclerView（横向），item_episode_chip
```

## 3. 数据分三次到，先能看再补全

| 顺序 | 内容 | 来源 |
|---|---|---|
| 1 | 标题 / 海报 / 首字占位 | 调用方传进来的 `MediaItem`（不用等网络） |
| 2 | 简介、类型、评分、分级、技术信息 | `api.itemDetail(id)`（`DETAIL_FIELDS` 比列表多了 `Overview,OfficialRating,CommunityRating,Genres`） |
| 3 | 选集 + 「该看的那一集」 | `api.episodes(id)` + `api.nextUp(id)`（失败退 `api.firstEpisode(id)`） |

第 2 步慢的时候用户已经能按「播放」了 —— 播放只需要 `mediaSourceId`，
缺了会在点下去的瞬间补一次 `itemDetail`。

## 4. 几个必须记住的坑

### 4.1 选集不能用 HorizontalScrollView 套 LinearLayout

一开始 `row_episodes` 写成了 `HorizontalScrollView > LinearLayout`，然后代码里
`findViewById<RecyclerView>(R.id.row_episodes)` 强转 —— 直接
`ClassCastException: LinearLayout cannot be cast to RecyclerView`，Activity 一进去就崩。

`HorizontalScrollView` 里放 40 个 `TextView` 也不划算：一次性把 40 个都 inflate，
96MB 堆的电视白吃内存。横向 `RecyclerView`（`LinearLayoutManager(HORIZONTAL)`）
只建可见的那几个。

### 4.2 很多条目没有剧照（Backdrop）

实测「史努比特辑：圣诞大作战」（id `416261`）只有 `Primary`、没有 `BackdropImageTags`，
背景图请求回来是空的 → 整页纯黑，看着像没加载完。

所以拿到空结果时**退回用海报铺满**，`alpha` 调到 0.28。同一张图，至少颜色和构图透得出来。

### 4.3 焦点从外面塞进 RecyclerView 不会自动滚

`focusEpisode(position)` 先 `findViewByPosition`，可见就 `requestFocus`，
不可见得先 `scrollToPositionWithOffset(position, 0)` 再 `post{}` 里 focus ——
跟首页卡片行是同一个坑（见 `docs/home-screen.md`）。

### 4.4 每集用 `IndexNumber`，不是 `Name`

Emby 的 `Name` 规则是「有分集标题就用标题」，直接显示会出现
「第 30 集」和「谢画楼跪求…」混排，看着像没编号。统一显示「第 N 集」。

### 4.5 简介的多行省略在老系统上不生效

`android:ellipsize="end"` + `android:maxLines="6"` 在 Android 4.3 上**不会真的加省略号**
（多行省略是后来才做全的，老系统只有单行可靠）。表现是简介被硬切在第 6 行半个字上，
看着像排版坏了。

所以代码里自己截断：超过 220 字就 `take(220) + "…"`（常量 `OVERVIEW_MAX`）。

## 5. 焦点路径

| 起点 | 按键 | 落点 |
|---|---|---|
| 进入页面 | — | `btn_play`（`post{}` 里 requestFocus） |
| `btn_play` / `btn_back` | ↓ | 选集第一集（没有选集就交给框架） |
| 选集任意一集 | ↑ | `btn_play` |
| 选集内 | ← / → | 逐集移动，RecyclerView 自己滚 |
| 任意 | BACK | 回上一页（`onActivityResult` 里刷新进度） |

### 返回之后焦点回到原来那张卡

`HomeActivity.openDetail()` 把 `item.id` 记进 `pendingFocusItemId`，从详情页回来后：

- 首页（`restoreHomeFocus()`）：在各行里按 id 找，找到就 `requestFocus`，
  行还没画出来就每 200ms 重试（最多 8 次），最后实在找不到退回第一张。
- 媒体库页（`restoreLibraryFocus()`）：同样按 id 在 `libAdapter` 里找位置，
  找不到退回网格第一张。

实测：媒体库点第四张卡 → 详情 → 返回，焦点仍在 `[1008,642][1233,975]`（原来那张）。

## 6. 还没做

1. 演职人员（Emby 的 `People` 字段没解析）。
2. 相似推荐 / 同系列。
3. 演播室、发行日期、字幕轨列表。

## 7. 相关文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/work/luxiaoxiao/myembyhub/ui/DetailActivity.kt` | 页面本体 |
| `app/src/main/res/layout/activity_detail.xml` | 整页布局 |
| `app/src/main/res/layout/item_episode_chip.xml` | 一集（TextView 直接做根） |
| `app/src/main/res/drawable/bg_detail_scrim.xml` | 左黑右透的渐变 |
| `app/src/main/res/drawable/bg_episode_chip.xml` | 剧集条目三态（焦点 / 该看的一集 / 默认） |
| `app/src/main/res/color/episode_chip_text.xml` | 剧集文字色 |
| `app/src/main/res/values/strings_detail.xml` | 文案 |

入口：`HomeActivity.onCardClick`（电影/剧集 → 详情页，单集/视频 → 直接播）、
`LibraryActivity.showDetail`（老的左栏界面点条目）。

## 8. 实拍

| 文件 | 内容 |
|---|---|
| [detail-series.png](../dist/detail-series.png) | 剧集详情：47 集选集行、「继续播放第 1 集」 |
| [detail-from-lib.png](../dist/detail-from-lib.png) | 电影详情（从媒体库网格点进来，有剧照背景） |
| [detail-error.png](../dist/detail-error.png) | 服务器挂了时的降级：标题 + 海报仍在，只提示「服务器暂时不可用」 |
