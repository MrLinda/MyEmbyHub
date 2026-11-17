# 首页（HomeActivity）—— 结构、取数与焦点规则

> 这是电视客户端的**入口界面**，也是「媒体库 / 搜索」共用的界面壳：
> 登录后进这里，遥控器第一屏看到的就是它，**顶栏切哪个 tab，下面就是哪一栏的内容**。
>
> 设计依据：电视上「顶栏 + 纵向横滑行」这个结构是行业通用做法（各家 TV 客户端都这么排），
> 这里只借鉴了**结构**——配色、圆角、字号、留白、焦点态全部是本项目自己定的，
> 走 `res/values/colors.xml` 的两层令牌。

## 1. 它长什么样

```
┌──────────────────────────────────────────────────────────┐
│  首页   媒体库   搜索                         〔设置〕     │ ← 顶栏 76dp，选中项暖色药丸 + 3dp 下划线
├──────────────────────────────────────────────────────────┤ ┐
│  继续观看                                                 │ │
│  ┌────┐ ┌────┐                                           │ │ 标题行 56dp
│  │海报│ │海报│  …                                        │ │ 横滑行 234dp
│  └────┘ └────┘                                           │ │
│  电影                              〔更多 ›〕              │ │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ …                           │ │
│  └────┘ └────┘ └────┘ └────┘                              │ ┘
└──────────────────────────────────────────────────────────┘

媒体库页（跟首页刻意长得不一样：左边一列分类，右边整片海报墙）
┌──────────────────────────────────────────────────────────┐
│  首页   媒体库   搜索                         〔设置〕     │
├───────────────┬──────────────────────────────────────────┤
│ ▎电影         │ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐│
│  动漫电影     │ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘│
│  港澳台剧集   │ ┌────┐ ┌────┐ ┌────┐ …                   │
│  国产剧       │ └────┘ └────┘ └────┘                     │
│  …（14 个库） │                                          │
└───────────────┴──────────────────────────────────────────┘
```

- **顶栏 3 个 tab + 1 个按钮**：首页 / 媒体库 / 搜索，最右边是「设置」。
  设置没做成 tab —— 它弹的是菜单不是一栏内容，混进 tab 里焦点一路往右会「切到不存在的页面」。
- **焦点即切换**：遥控器停在哪个 tab 上，下面立刻换成那一栏。不用按确认键。
  中间加了 260ms 防抖，路过中间那个 tab 不会白加载一次。
- **首页纵向 = 一条条横滑行**：第一行固定「继续观看」，后面每个媒体库一行，最多 5 行。
  它是「一眼扫过去有什么」的界面。
- **媒体库页 = 左栏 228dp 竖排分类 + 右侧 6 列海报墙**：它是「在十几个分类里反复挑」的界面，
  竖列能用余光看到上下五个分类、找回刚才那个库是往上一两下的事。
  两页长得不一样是**故意的**：用户一眼就知道自己站在哪一页。
- **卡片** 150×222dp（`item_home_poster.xml`），行高 234dp，左右各留 18dp，
  最后一张故意被右边缘切掉半张 —— 提示「这一行还能往右按」。
- **列数按屏宽算**：首页横滑行不动列数；搜索页 `gridSpan` = 7 列（整屏宽）；
  媒体库页 `libSpan` = 6 列（扣掉左栏 228dp 之后的宽度）。同一份 `PosterAdapter`，换个 LayoutManager。
- 顶栏下面那层 76dp 的渐隐（`bg_top_fade.xml`）让内容「从顶栏底下滚过去」时是淡出，
  而不是被顶栏一刀切。

## 2. 文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/work/luxiaoxiao/myembyhub/ui/HomeActivity.kt` | 三页全部逻辑（约 1300 行） |
| `app/src/main/java/work/luxiaoxiao/myembyhub/ui/PosterAdapter.kt` | 卡片适配器，**横滑行和网格共用** |
| `app/src/main/res/layout/activity_home.xml` | 骨架：三块页面容器 → 渐隐 → 顶栏 → 加载条 → 居中转圈 → 提示语 |
| `app/src/main/java/work/luxiaoxiao/myembyhub/ui/TopInsetScrollView.kt` | 首页滚动容器：把顶栏高度算进「焦点滚动」的可视边界 |
| `app/src/main/java/work/luxiaoxiao/myembyhub/ui/LoadingOrbit.kt` | 自绘加载转圈（轨道 + 暖色弧），替代 4.3 的 Holo 青色 spinner |
| `app/src/main/res/values/dimens.xml` | 顶栏高度 / 药丸高度 / 设置按钮高度 / 转圈直径 |
| `app/src/main/res/layout/item_home_nav.xml` | 顶栏 tab：文字 + 下划线 |
| `app/src/main/res/layout/item_home_poster.xml` | 卡片：占位字 / 海报 / 标题条 / 进度条 / 焦点圈 |
| `app/src/main/res/layout/item_lib_tab.xml` | 媒体库左栏一行：暖色竖条 + 标题 |
| `app/src/main/res/layout/activity_home.xml` 里的 `box_lib_loading` | 媒体库切库时的局部转圈（只盖海报墙） |
| `app/src/main/res/layout/activity_home.xml` 里的 `lib_sort_row` | 媒体库左栏顶部的「排序 · xxx」（固定在滚动区之外） |
| `app/src/main/res/drawable/bg_home_nav.xml` | tab 焦点底（默认透明，焦点才浮出来） |
| `app/src/main/res/drawable/bg_home_nav.xml` | tab 焦点底（默认透明，焦点才浮出来）—— 顶栏「设置」用的是同一个 |
| `app/src/main/res/drawable/bg_lib_tab.xml` | 左栏一行的底：焦点=浮起+描边，选中=浅底，默认透明 |
| `app/src/main/res/drawable/bg_card_ring.xml` | 卡片焦点圈（画在海报**上层**，见第 5 节） |
| `app/src/main/res/drawable/bg_home_poster_title.xml` | 卡片标题条（下两角与卡片同圆） |
| `app/src/main/res/drawable/bg_top_fade.xml` | 顶栏下方的渐隐 |
| `app/src/main/res/color/home_nav_text.xml` / `lib_tab_text.xml` / `chip_text.xml` / `nav_action_text.xml` | 文字色 selector |
| `app/src/main/res/values/strings_home.xml` | 顶栏文案 / 「更多 ›」/ 空态 / 失败态 / 搜索引导 |

`LibraryActivity` 仍在（老的左右两栏列表视图，带排序和手机扫码搜索），从设置菜单进；
它支持三种深链：`EXTRA_PARENT_ID`（直接定位到某个库）、`EXTRA_PARENT_NAME`、`EXTRA_OPEN_SEARCH`。

## 3. 取数

首页：`Resume` → `libraries()` → 每个库「最新 20 条」。**单线程串行**，
不是并发 —— 电视堆只有 96MB，6 个请求同时解析 JSON 只会让 GC 抖起来。

**必须递归列 + 指定条目类型：**

```kotlin
api.items(
    parentId = lib.id,
    recursive = true,          // ← 这个库里每个影片外面还套一层同名文件夹
    limit = ROW_LIMIT,         // 20
    sortBy = SortOption.SORT_DATE_CREATED,
    descending = true,
    includeItemTypes = HOME_ROW_TYPES   // "Movie,Series,Video"
)
```

为什么三条都不能省（真机实测过的坑）：

- 这个 Emby 库（`ParentId=4` 那个「电影」）里，`Recursive=false` 只返回 **4 个文件夹**：
  `电影` / `1080p电影` / `1080p电影` / `1080p电影`。不递归的话，首页一整行三四张卡片全叫
  「1080p电影」，用户还得再点进去一层才看见片子。
- `Recursive=true` 会返回 **8725 条**，而且是**成对**的：文件夹 `恐怖X档案 (2022)`
  紧跟电影 `恐怖X档案`。不带 `IncludeItemTypes` 就是两套同名卡片（带年份的那个是文件夹）。
- 光靠 `limit` 挡不住，`IncludeItemTypes` 是唯一靠谱的过滤。

**媒体库页**：进来先 `api.libraries()` 拉一遍库列表做成 chip 行；选中哪个库就拿第一页
（`PAGE_LIMIT = 60`），滚到倒数第 `gridSpan` 个位置时按 `startIndex` 追加下一页，
返回条数不足 `PAGE_LIMIT` 就标记到底（`libAtEnd`）。

**媒体库排序**：两个字段 —— `libSort: SortOption.Opt`（字段）和 `libSortDesc: Boolean`（方向），
两项取数（第一页 / 翻页）都读它俩：`sortBy = libSort.key`、`descending = libSortDesc`。
两个都存盘（`myembyhub_ui` / `lib_sort_key` + `lib_sort_desc`）。

字段自带一个 `descending` 默认值，它只在**换字段那一刻**起作用：

| 选项 | key | 换到这个字段时的默认方向 |
|---|---|---|
| 开播时间（出厂默认，菜单第一项） | `PremiereDate` | 降序 |
| 最近添加 | `DateCreated` | 降序 |
| 片名 | `SortName` | 升序 |
| 年份 | `ProductionYear` | 降序 |
| 时长 | `Runtime` | 降序 |

默认选「开播时间」而不是「最近添加」：库里的「加入时间」取决于什么时候拷进去的，
一次补齐整季会让同一天的片子整批霸占前面；开播时间才是内容本身的先后。
（`SortOption.DEFAULT`，菜单里也把默认那项排在第一行，用户一眼看到的就是出厂设置。）

**方向是用户可以单独改的**（菜单下半部分「方向」分组：降序 / 升序），
所以「年份升序」看老片、`Z → A` 翻片名这些都成立。
换字段时方向重置成新字段的默认（不会出现「挑了片名却还在 Z→A」这种莫名其妙的状态），
同一字段内改过的方向保持不变。

菜单用 `TvMenu.showPersistent`（**点选不关闭**）：先挑字段、再翻方向、不满意再换一个字段，
是连着按的一串动作；每点一下就关掉、还得重新打开，电视上按起来烦。
菜单开着的时候网格在后面重排，关掉就是最终结果。

左栏那一行会带方向箭头：`排序 · 年份 ↑` / `排序 · 最近添加 ↓`。不带的话，
用户看菜单里「当前」才知道是升还是降，等于把状态藏起来了。

会**存盘**（`myembyhub_ui` / `lib_sort_key`）：用户挑过「按片名」，下次开 App 还得是按片名。
只放内存里的话杀进程重进就变回「最近添加」，等于白设。
`onCreate` 里 `loadSort()` 必须在 `buildLibraryPage()` **之前**调，否则第一屏先按默认排一遍再重排。

**搜索页**：`api.search(term, SEARCH_LIMIT = 60)`。手机输入走 `PhoneControlServer(MODE_SEARCH)`，
手机浏览器上打好字直接回传，电视端自动执行搜索。搜索**不做排序菜单** ——
结果按相关度排才有意义，让用户按片名重排等于把搜索变成「模糊的媒体库」。

**增量渲染（重要）**：一个库递归排序要 1.5 秒起（模拟器上更慢），
五六个库攒齐再画就是十几秒空白页。所以首页是**到一行画一行**
（`main.post { appendSection(sec) }`），「继续观看」一秒多就出来了。

刷新时不清空旧内容，等第一块新数据到了再整体替换（`pendingReset` 标志）——
立刻 `removeAllViews()` 会让「从播放器返回 / 手动刷新」时整屏空白好几秒，
电视上看着像崩了。

## 4. 焦点规则

遥控器在 TV 上就是全部输入。**凡是「几何上最近」不等于「用户想去的地方」的，都手工接管**：

| 动作 | 到哪里 | 为什么必须手写 |
|---|---|---|
| 顶栏 tab ←/→ | 相邻 tab；最右 → 「设置」 | 不接的话，媒体库数据加载完后按右键，框架判定「右下方的卡片更近」，直接跳进海报墙，**永远走不到搜索和设置** |
| 顶栏「设置」← | 最后一个 tab | 跟上面一条对称；不接的话会按几何位置去够「搜索」下方的卡片 |
| 顶栏 ↓ | 当前 tab 那一栏的第一个可聚焦物 | 顶栏是横容器，框架只会往最近的邻居找 |
| 首页**每一排**卡片 ↑ | 上一排同序号的卡；第一排才回顶栏 | 上面那一排可能已经滚出屏幕，框架的几何搜索找不到它就把焦点丢给顶栏 —— 实测第二排按 ↑ 直接跳到「媒体库」tab，第一排再也回不去（见 `focusRowAbove`） |
| 首页第一排任意卡拿到焦点 | 整页 `scrollTo(0, 0)` | 否则从下面 ↑ 回来时页面停在「卡片顶边贴着顶栏下沿」，区块标题被吃掉，整页不是刚进来那个姿势 |
| 行首卡片 ← | 该行的「更多 ›」 | 不吞掉的话框架会一路往左上，几下就把焦点拽回顶栏 |
| 「更多 ›」↓ | 回到本行第一张 | 让「进库」和「浏览」两条路都能来回 |
| 网格第一排 ↑ | 媒体库页=左栏当前选中那一行；搜索页=搜索框 | 从网格回到自己那一栏的「开关」 |
| 媒体库左栏 ← / → | ← 回不了右边；→ 进海报墙 | 左栏是竖列，右边才是内容，横向只有「进内容」一条路 |
| 媒体库「排序」行 ↑ | 顶栏当前选中项 | 它是左栏最上面那一行，往上就是顶栏 |
| 媒体库「排序」行 ↓ | 第一个库 | ↓ 就是「去挑分类」 |
| 媒体库左栏第一个库 ↑ | 顶栏上的「排序」行 | 排序行是它的上一行。**注意不是顶栏** —— 直接跳顶栏等于跳过排序，用户永远发现不了这个功能 |
| 媒体库左栏最后一项 ↓ | 海报墙 | 到底了就该进内容，不该原地不动 |
| 海报墙第一列 ← | 左栏当前选中那一行 | 不接管的话会一路往左上跑到顶栏 |

### 排序入口为什么不在顶栏

一开始确实做在顶栏（「搜索」和「设置」之间）。**实测走不到**：顶栏从左往右会「焦点即切换」，
要够到最右边必须先经过「搜索」tab，一经过就切页了 —— 从「媒体库」按 → ，
焦点直接落到「设置」上，排序被跳过；就算硬按到它，页面也早就不是媒体库了。

所以放在**左栏最上面**：它是「这一页怎么排」的动作，跟分类是一套东西，
↓ 就是「选哪个库」，动作链是一条直线。

两个连带约束：

- 排序行**不能放进 `lib_tab_column`** —— `buildLibTabs()` 开头就 `removeAllViews()`，
  会把它一起删掉；而且它是固定动作，滚到第 12 个分类时最想换排序，滚下去就找不到了。
  所以左栏是 `LinearLayout[排序行 + 分隔线 + ScrollView[lib_tab_column]]`。
- 第一个库的 ↑ 落点是**排序行**，不是顶栏。写了兜底（`requestFocus()` 返回 false 才退顶栏），
  免得排序行哪天被隐藏时焦点卡在原地。
- ⚠️ `TvMenu.showPersistent` 里 `notifyDataSetChanged()` 会把行**全部拆掉重建，焦点跟着没了** ——
  菜单此刻还开着，用户再按方向键毫无反应，看着像卡死。既然「点完继续改」是它的存在意义，
  就得在 `r.post {}` 里把焦点放回**同一行**（`findViewHolderForAdapterPosition(which)`）。

其余（行内 ←/→）全交给框架的 focus search，几何位置天然就对。

⚠️ 首页的**行与行之间↑/↓ 不能交给框架**（媒体库 / 搜索的网格可以）。
首页每一排是独立的横向 `RecyclerView`，上下关系只有「容器里的先后」，
框架看不到「这排上面是那排」；而滚过一屏之后上一排干脆不在 Attached 状态，
几何搜索必然落空。所以 `PosterAdapter.onUpFromFirstRow` 是**每一排都接**
（`firstRowCount = sec.items.size`，不是只给第一排），回调进 `focusRowAbove(rowIndex, pos)`。

另外几处细节：

- 顶栏下划线用 `invisible` 而不是 `gone` —— `gone` 会改行高，焦点一路按过去文字会上下跳 3dp。
- 跨容器塞焦点时 `RecyclerView` **自己不会滚**，目标卡片在屏幕外就先
  `scrollToPositionWithOffset(position, 0)` 再 `post { requestFocus() }`（见 `focusCardIn`）。
- 冷启动焦点要兜一层：数据是一行一行到的，第一张卡可能还没 inflate 出来。
  `focusHomeFirstCard()` 每 200ms 重试、最多 8 次；`appendSection()` 结束时如果发现
  **焦点悬空或停在滚动容器上**才补一次（用户已经在 tab 或卡片上时绝不抢）。

## 5. 四处踩过就忘不掉的坑

**首页滚动容器必须用 `TopInsetScrollView`，不能用系统 `ScrollView`**：系统那个算
「要不要为焦点滚动」时把可视上边界当成 y=0（`ScrollView.computeScrollDeltaToGetChildRectOnScreen`
里 `screenTop = getScrollY()`，它不看 padding），于是焦点落到第一排时只滚到
「第一排顶边压在 y=0」就收手 —— 上半张卡藏在顶栏底下。子类把上边界下移 `paddingTop`。
两个连带问题也一起在这里解决：

① **`ScrollView` 会自己吃掉方向键**：`dispatchKeyEvent` 是
`super.dispatchKeyEvent(event) || executeKeyEvent(event)`，卡片没消费掉的 ↑/↓ 会让外层
再 `arrowScroll` 一次（平滑滚 93px）—— 焦点明明回到了第一排，页面却停不在顶上。
所以 `executeKeyEvent` 直接返回 false：首页滚动只由「焦点进了哪一排」决定。

② **复位必须 `post`**：焦点变化回调跑在 `View.requestFocus()` 内部，紧接着父容器才调
`requestChildRectangleOnScreen` 把卡片滚进可视区，同步 `scrollTo(0,0)` 会被那次滚动盖掉。

**焦点圈画在海报上层**：卡片的 `state_focused` 描边本来写在 `bg_card` 里，但海报是
`match_parent` 全铺的 ImageView，把背景整个盖住 —— 遥控器走到哪张卡上一个像素都看不见。
所以焦点圈单独做一层（`bg_card_ring` + `duplicateParentState`），放在卡片布局最后，压在海报边缘上。

**加载圈不能放右上角**：原来用 `progressBarStyleSmall` 摆在 `top|right`，正好落在「设置」按钮上，
截图看着像「设置）」多了半个括号。现在是顶栏下面一条 3dp 的跑马灯，不占任何控件的位置。

**光有那条 3dp 跑马灯不够**：它在电视三米外几乎看不见，而取数要十几秒，屏幕上得有
一个明摆着在转的东西。所以正中再加「转圈 + 加载中…」（`box_home_loading`）。
两个指示器分工不同：**跑马灯说「在忙」，转圈说「还没结果」**。
第一行画出来就 `hideSpinner()` 收掉大转圈、跑马灯继续跑（后面几行还在路上）；
`showLoading(false)` 才两个一起收。

转圈是自绘的 `LoadingOrbit`，不用系统 `ProgressBar`：4.3 的 spinner 是 Holo 青色 + 12 张帧图轮播，
既跟暖色语言对不上，又比一笔弧线贵。颜色取 `indicator_bar` / `stroke_subtle`，换皮肤自动跟。
⚠️ `ValueAnimator` 不会因为 View 变 `GONE` 就停，一直跑就是一直 `invalidate()` ——
`onVisibilityChanged` 和 `onDetachedFromWindow` 里必须显式停，否则电视上白烧 CPU。

**媒体库切库：转圈只盖海报墙那一块**（`box_lib_loading`）：切库会把网格清空
（留着上一个库的海报是错的），而取一页要一两秒 —— 那两秒里右半边是纯黑，
跟「这个库是空的」分不出来。但也不能盖全屏：左栏有一列分类正在响应方向键，
整屏糊住等于告诉用户「现在什么都别按」。所以：

| 场景 | 用什么 | 盖住哪儿 |
|---|---|---|
| 冷启动 / 第一次进媒体库（还没有分类） | `showLoading(true)` | 整屏 |
| 切库（左栏已经在了） | `showLibLoading(true)` | 只有海报墙那块，居中 |

两个都是 `LoadingOrbit` +「加载中…」，同一个视觉。`selectLibrary` 里
`showLoading(firstEnter)` / `showLibLoading(!firstEnter)` 二选一，回调里两个都关（幂等，不怕连切）。

**「设置」是顶栏最右的同款药丸**（`item_home_nav`，60dp 高，和三个 tab 一模一样）。
它不是页面（点了弹菜单，不切页），所以：

- 靠右靠的是一个 `weight=1` 的 `Space` 把它顶过去，不是单独的布局样式；
- 拿到焦点时**下划线照样亮**（「焦点到哪儿」的反馈要一致），但**不参与「焦点即切换」**；
- 选中态（常驻下划线）不画 —— 它没有「当前正在看设置」这种状态。

> 曾经把它做成「半高的动作按钮」（30dp）：理由是「动作不是栏目，跟 tab 等高会让人以为点它会切页」。
> 但实际观感是**像个禁用按钮**（半高 + 空心框 + 灰字），而且顶栏右半边空出一大块，
> 视觉上跟三个 tab 不成一体。改成同款药丸之后，「顶栏 = 四个位置 + 一个设置」一眼就读出来了。

**提示语是全屏共用的一个 TextView —— 必须按页记账**：三个页面都会 `showHint` / `hideHint`。
一开始只改 `visibility`，结果搜索页那句「按确认键输入片名…」会跟着用户飘到首页上赖着不走
（实测：进过搜索页再回首页，那行字就一直停在屏幕中间）。
现在 `pageHints: Array<String?>(3)` 按 tab 索引存文案，`showHint(text, page)` / `hideHint(page)`
写账本，切页时 `showPage()` 调 `applyPageHint(index)` 整页替换。
两个连带要求：
① 异步回调里的 `showHint` **必须传死页号**（`loadHome` 传 0、媒体库传 1、`runSearch` 传 2），
不能用默认的 `tabIndex` —— 取数要十几秒，这期间用户可能已经切走了，记到错的页上等于
把首页的空态挂到搜索页；而且切回来还得能看见。
② 正因为、也只有这样，回调里**不再需要 `if (tabIndex != 1) return@post` 这种守卫**了 ——
守卫会连带把焦点恢复也挡掉（`focusGrid` / `restoreLibraryFocus` 写在同一段里）。

**「焦点即切换」要有闸门**：弹窗一关，Activity 重新拿焦点时系统把焦点塞给第一个可聚焦视图
（正好是第一个 tab），于是「焦点即切换」立刻把页面切回首页，搜索结果白搜。
所以开弹窗前置 `suppressTabSwitch = true`，关掉后 `restoreFocusAfterDialog(target)`
（80ms 后复位并把焦点扶回原处，返回键关掉也走 `setOnDismissListener` 这条同路）。

## 6. 视觉令牌

一个 `#` 都没写（AGENTS.md 的硬规则）。用到的语义层：`surface_base` / `surface_raised` /
`surface_overlay` / `surface_pressed`、`content_primary` / `content_secondary` /
`content_accent` / `content_disabled`、`accent_container`、`indicator_bar`、
`focus_border`、`scrim_soft`、`stroke_subtle`、`progress_fill` / `progress_track`。

要换皮肤只动 `colors.xml` 的 palette 层；要重做明暗两套只重写语义层，布局和代码一行不动。

**顶栏两个高度是一对**（`values/dimens.xml`）：

| 令牌 | 值 | 谁在用 |
|---|---|---|
| `home_nav_bar_height` | 76dp | 顶栏本体、渐隐层、内容区 `paddingTop`、加载条 `marginTop` |
| `home_nav_pill_height` | 60dp | tab 焦点药丸、设置按钮的 `minHeight` |

药丸早先是 `match_parent`（铺满 76dp），焦点态顶天立地、跟内容糊成一片。
现在固定 60dp，顶栏是 `gravity=center_vertical`，上下各剩 8dp —— 顶栏才像「浮」在内容上的
一条，而不是压在内容上的一块板。**改一个必须改另一个**：药丸高度得装得下
20sp 文字（约 27dp）+ 3dp 下划线 + 4dp 间距；顶栏也不能比药丸矮，否则药丸会被裁。

## 7. 已验证（模拟器 API 18 / 1920×1080 / 240dpi）

- 冷启动连续两次都落在第一排第一张卡（`[36,207][261,540]`）。
- 顶栏 ←/→ 四项逐项走通；↓ 进当前栏；卡片行内 ←/→ 逐张。
- **行与行 ↑/↓ 按排稳定移动**：从第一排一路 ↓ 到第 6 排，再一路 ↑ 回来，
  每一排都落在同序号卡片上（`兰香如故 → 封锁蛛楼 → 史努比特辑 → 罪梦者 → 玉簟秋`，
  回来是逆序）；无论中间滚过多少排，回到第一排时页面 `scrollY` 都是 0，
  卡片回到出厂姿势 `[36,207][261,540]`（区块标题「继续观看」在顶上）。
- 行首 ← → 「更多 ›」→ ↓ 回行首，可反复往返；第一排 ↑ → 顶栏当前项。
- 焦点停在「媒体库」tab 上 260ms 后，页面自动切到媒体库并选中「电影」库（6 列海报墙）。
- 媒体库左栏：↓ 逐库走（`[21,114][342,198]` → `[21,204][342,288]` → `[21,294][342,378]`）；
  焦点停住 260ms 后网格自动重取、暖色竖条跟着走；→ 进海报墙第一列、← 回左栏当前项。
- 切库时海报墙中间转圈（顶栏和左栏不被遮住），取完自动收；连按 5 下快速切库后
  转圈不残留、网格正常填满（dump 里 `box_lib_loading` 节点数 = 0）。
- 14 个库一屏放不下：滚到最后一项（播放列表）时整行完整可见（y=996..1080），
  `childH=1356`、`scrollY=288`。
- 焦点在「媒体库」tab 上停留 8 秒不再丢焦点（此前会被 `focusHomeFirstCard()` 抢走）。
- 媒体库排序：排序行 `[21,114][342,192]` 固定在最上面，左栏滚到「播放列表」时它还在；
  ↑ 回顶栏、↓ 到第一个库、→ 进海报墙；第一个库 ↑ 回排序行（不是顶栏）。
  确认 → 菜单十行（2 个分组标题 + 5 个字段 + 2 个方向 + 各带「当前」标记）；
  分组标题是禁用行，方向键直接跳过（从「时长」按 ↓ 落到「降序」，中间不经过「方向」）。
- 排序菜单点选**不关闭**：选「年份」后焦点仍停在该行，再按 ↓ 能继续走到「降序 / 升序」；
  选「升序」后菜单里「当前 · 旧的在前」跟着移位、左栏那行变成「排序 · 年份 ↑」，
  网格首位从「东北警察故事3」（2025）变成「套现」，「安全至下」（1923）/「马戏团」（1928）
  这类老片冒到前面来。
- 排序存盘：杀掉进程重进仍是「排序 · 年份 ↑」，首卡还是「套现」。
- 搜索页：确认键开输入框 → 输入 `2026` → 出结果；「手机输入」→ 二维码
  `http://10.0.2.15:5103/?mode=search`，关掉后焦点回到「手机输入」且仍在搜索页。
- 刷新首页不再白屏；首屏渐进出数：3 秒内出「继续观看」，10 秒内 6 行齐。

实拍图：[ui-home.png](../dist/ui-home.png)、[ui-library.png](../dist/ui-library.png)（左栏焦点态）、
[ui-library-grid.png](../dist/ui-library-grid.png)（焦点进海报墙）、
[ui-library-scrolled.png](../dist/ui-library-scrolled.png)（左栏滚到底）、
[sort-menu.png](../dist/sort-menu.png)（排序菜单，字段已选「开播时间」）、
[sort-menu-asc.png](../dist/sort-menu-asc.png)（选中「升序」，左栏同步成「排序 · 年份 ↑」）、
[sort-asc-grid.png](../dist/sort-asc-grid.png)（年份升序的网格，老片在前）、
[sort-scrolled.png](../dist/sort-scrolled.png)（滚到底时排序行仍钉在顶上）、
[ui-search-empty.png](../dist/ui-search-empty.png)、[ui-search-result.png](../dist/ui-search-result.png)、
[ui-phone-qr.png](../dist/ui-phone-qr.png)、[zoom-card.png](../dist/zoom-card.png)（焦点圈放大）。

## 8. 还没做的

1. **播放只能在真机验**（1080p 零丢帧已验）。模拟器是 x86 软解，只吃 Baseline H.264，
   4K HEVC 连解码器都没有 → 卡「正在缓冲 0 B/s」后 ExoPlayer 释放退回。详见 AGENTS.md 第 6 节。
2. **`LibraryActivity` 右栏仍是横向行**，不是网格（新界面里的媒体库页是网格，老界面没跟着改）。
3. 真机（`192.168.1.100:5555`）还没推这个版本。
4. 排序菜单目前是「字段 + 方向」两层，还缺更细的口径（按季 / 按评分 / 随机 / 手工置顶）。

> 详情页（点电影 / 剧集之后落地的那一页）已从 `TvDialog` 弹窗改成整页，
> 见 [detail-screen.md](detail-screen.md)。