package work.luxiaoxiao.myembyhub.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.ImageLoader
import work.luxiaoxiao.myembyhub.net.MediaItem

/**
 * 海报卡片适配器：**横滑行和网格共用一套**。
 *
 * 首页一条行和媒体库/搜索的海报墙，卡片长得一模一样（`item_home_poster`），
 * 差别只在 `LayoutManager`：行是横向 `LinearLayoutManager`，墙是 `GridLayoutManager`。
 * 所以适配器只有一份，靠构造参数告诉它「第几行算第一排」「第几列算第一列」，
 * 好让遥控器的 ↑ / ← 在边界上交给外面处理。
 *
 * @param firstRowCount 前多少个位置按 ↑ 时交给外面处理（行 = 整行；网格 = 每排几个）。
 *        注意不只是「视觉上的第一排」：第二排上面那一排可能已经被滚出屏幕，
 *        框架的几何 focus search 找不到它就会把焦点丢给顶栏，所以第二排也要自己接住。
 * @param onUpFromFirstRow 这些位置按 ↑。参数是位置序号，返回 true 表示外面已经处理掉这次按键。
 * @param firstColumnCount 每排几个（行 = 1；网格 = 每行几个）。行首按 ← 回调 [onLeftFromFirstColumn]。
 *        注意「行首」不是「位置能被它整除」—— 参数是 1 时那样写恒真，见 [isRowStart]。
 * @param onLeftFromFirstColumn 行首按 ←。返回 true 表示外面已经处理掉。
 * @param onRightFromLastCard 行末按 →。返回 true 表示外面已经处理掉（首页拿它去「更多 ›」）。
 * @param onCardFocused 某张卡拿到焦点（用来把整页滚回该回的姿势，见 `HomeActivity.createRow`）。
 */
class PosterAdapter(
    private val items: MutableList<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val firstRowCount: Int = 0,
    private val onUpFromFirstRow: ((Int) -> Boolean)? = null,
    private val firstColumnCount: Int = 1,
    private val onLeftFromFirstColumn: (() -> Boolean)? = null,
    private val onRightFromLastCard: ((Int) -> Boolean)? = null,
    private val onCardFocused: ((Int) -> Unit)? = null,
    private val onMenu: ((MediaItem, Int) -> Unit)? = null
) : RecyclerView.Adapter<PosterAdapter.Holder>() {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.img_poster)
        val placeholder: TextView = view.findViewById(R.id.tv_poster_ph)
        val title: TextView = view.findViewById(R.id.tv_poster_title)
        val progress: ProgressBar = view.findViewById(R.id.pb_poster_progress)

        /** 当前绑的是哪一条（取图回调回来时用来判断 holder 是否已被复用）。 */
        var itemId: String? = null

        /** 这个 holder 正在进行的取图请求。 */
        var request: ImageLoader.Request? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_poster, parent, false)
        val holder = Holder(v)

        // ⚠️ 三个监听器在这里**只挂一次**，位置在事件发生时用 `holder.adapterPosition` 现取。
        //    早先是写在 onBindViewHolder 里的，每绑一次就 new 两个 lambda + 走一遍
        //    `setOnKeyListener` 的 ListenerInfo 写入。快速滚动时一屏十几张卡、
        //    每张反复绑，光这些对象就是实打实的 GC 压力（96MB 堆的机器上很敏感）。
        holder.itemView.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val position = holder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when {
                position < firstRowCount && keyCode == KeyEvent.KEYCODE_DPAD_UP ->
                    onUpFromFirstRow?.invoke(position) ?: false

                // ⚠️ 行首按 ← 必须外面接住。不接的话框架的 focus search 会往左上
                //    找最近的可聚焦物 —— 正好是顶栏，用户按几下 ← 就被拽上去了（实测踩过）。
                isRowStart(position) && keyCode == KeyEvent.KEYCODE_DPAD_LEFT ->
                    onLeftFromFirstColumn?.invoke() ?: false

                // 行末按 → 交给外面（首页用它跳到区块标题右边的「更多 ›」）。
                // 不接的话 RecyclerView 只在自己肚子里找邻居，直接掉到下一排 ——
                // 「更多」在 header 里，是它的兄弟视图，永远轮不到。
                position == items.size - 1 && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                    onRightFromLastCard?.invoke(position) ?: false

                // ⚠️ 行内左右挪动自己来。RecyclerView 的 focus search 只在自己
                //    **已经布局出来**的孩子里找邻居：一排 20 条、屏幕只够 8 张时，
                //    走到第 8 张按 → 就彻底不动了（框架不会替你滚，实测 1.2 秒按一次也一样），
                //    后面十几张永远够不着。所以这里「滚一张卡 + 交焦点」自己做。
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(view, position + 1)
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(view, position - 1)
                onMenu != null && keyCode in MENU_KEYS -> {
                    if (event.repeatCount == 0) onMenu.invoke(items[position], position)
                    true
                }

                else -> false
            }
        }

        // 卡片拿到焦点时告诉外面「是第几张」，外面用它把整页滚回该有的姿势
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val position = holder.adapterPosition
                if (position != RecyclerView.NO_POSITION) onCardFocused?.invoke(position)
            }
        }

        holder.itemView.setOnClickListener {
            val position = holder.adapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(items[position])
        }
        if (onMenu != null) {
            holder.itemView.setOnLongClickListener {
                val position = holder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMenu.invoke(items[position], position)
                    true
                } else {
                    false
                }
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.itemId = item.id

        // 单集的 name 是「剧名 - S01E01 - 2160p」这种长串，150dp 宽的卡片塞不下，
        // 卡面上只留剧名 —— 集数在详情页里看得更清楚
        holder.title.text =
            if (item.type == "Episode" && item.seriesName.isNotEmpty()) item.seriesName
            else item.displayTitle()
        holder.placeholder.text = item.name.take(1)

        // 海报：单集自己没有图（取图会 500），用所属剧的
        val posterId = if (item.type == "Episode") item.seriesId else item.id
        holder.request?.cancel()
        holder.request = null
        holder.poster.setImageDrawable(null)

        // ⚠️ **不要在这里切 `visibility`**（2026-11-19 改）。
        //    `setVisibility()` 每次都会 `requestLayout()` —— 一张卡里有 4 层，
        //    绑一次就引发好几轮 measure/layout，快速滚动时这就是掉帧的主因。
        //    占位字本来就在 ImageView **下面**那一层，ImageView 没有 drawable 时
        //    什么也不画，占位字自然透出来；有图时整张盖住。所以根本不需要切可见性。

        if (posterId.isNotEmpty()) {
            holder.request = ImageLoader.get(holder.itemView.context)
                .load(posterId, "Primary", POSTER_WIDTH) { bmp ->
                    // 回调回来时 holder 可能已经绑了别的条目
                    if (holder.itemId == item.id && bmp != null) {
                        holder.poster.setImageBitmap(bmp)
                    }
                }
        }

        if (item.positionTicks > 0 && item.runtimeTicks > 0) {
            holder.progress.progress =
                ((item.positionTicks * 100) / item.runtimeTicks).toInt().coerceIn(0, 100)
            holder.progress.visibility = View.VISIBLE
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        // 滚出屏幕就取消，别让看不见的海报继续占网络和线程池
        holder.request?.cancel()
        holder.request = null
        holder.itemId = null
    }

    override fun getItemCount(): Int = items.size

    /**
     * 这个位置是不是所在行的第一个。
     *
     * ⚠️ 别写成 `position % firstColumnCount == 0`：首页每一排传的是 `firstColumnCount = 1`，
     *    而任何整数取模 1 都是 0 —— 于是**每一张卡按 ← 都被当成「行首」**，
     *    焦点从中间某张卡直接被丢去「更多」或顶栏，用户看到的是「左键乱跳」
     *    （2026-11-18 真机反馈「在某排非最左边按左键也会回到更多键」就是这个）。
     *    一维列表的行首只有第 0 个；只有网格才需要取模。
     */
    private fun isRowStart(position: Int): Boolean =
        if (firstColumnCount > 1) position % firstColumnCount == 0 else position == 0

    /**
     * 焦点挪到同排的相邻卡片，不够宽就先滚。
     *
     * 顺序是「目标已在布局里就直接交焦点，否则滚动一张卡的距离再重试」——
     * 用 `scrollBy` 而不是 `scrollToPosition`：后者会按「让目标贴边」重排整排，
     * 和焦点自己触发的 `requestChildRectangleOnScreen` 打架，实测会来回抖。
     *
     * @return 是否接管了这次按键（拿不到 RecyclerView 就返回 false，让框架照旧处理）
     */
    private fun moveTo(view: View, target: Int): Boolean {
        if (target < 0 || target >= items.size) return false
        val rv = view.parent as? RecyclerView ?: return false
        rv.findViewHolderForAdapterPosition(target)?.itemView?.let {
            if (it.requestFocus()) return true
        }
        val step = rowStep(rv, view)
        rv.scrollBy(step, 0)
        focusAt(rv, target, step, 3)
        return true
    }

    /** 一张卡 + 间距的横向步长（从已布局的两个兄弟实测，取不到就按卡宽算）。 */
    private fun rowStep(rv: RecyclerView, view: View): Int {
        val a = rv.getChildAt(0)
        val b = rv.getChildAt(1)
        if (a != null && b != null && b.left > a.left && a.width > 0) {
            return (b.left - a.left).coerceAtLeast(a.width)
        }
        return view.width.coerceAtLeast(1)
    }

    /**
     * 等目标卡布局出来再交焦点（最多重试 [tries] 帧，每帧再滚 [step]）。
     *
     * ⚠️ 不能用 `post {}`：`scrollBy` 只是请求一次遍历，`post` 常常排在那次遍历之前跑，
     *    此时目标卡还没 attach，`findViewHolderForAdapterPosition` 拿到 null —— 焦点原地不动。
     *    `postOnAnimation` 排在遍历之后，稳。
     */
    private fun focusAt(rv: RecyclerView, position: Int, step: Int, tries: Int) {
        rv.postOnAnimation {
            val holder = rv.findViewHolderForAdapterPosition(position)
            if (holder != null && holder.itemView.requestFocus()) return@postOnAnimation
            if (tries > 0) {
                rv.scrollBy(step, 0)
                focusAt(rv, position, step, tries - 1)
            }
        }
    }

    /** 换一批数据（第一页 / 换库 / 换关键词）。 */
    fun submit(list: List<MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 追加一页（翻页）。 */
    fun append(list: List<MediaItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    /** 供外面判断「还有没有下一页」用。 */
    fun size(): Int = items.size

    /**
     * 找某条内容在第几个（返回 -1 表示这批里没有）。
     *
     * 用途：从详情页返回时把焦点放回**刚才点的那张卡**，而不是跳回第一张 ——
     * 用户点开第七部片看完回来，焦点飞回首页第一张，等于让他重新找路。
     */
    fun indexOf(itemId: String): Int = items.indexOfFirst { it.id == itemId }

    fun updateAt(position: Int, item: MediaItem) {
        if (position !in items.indices) return
        items[position] = item
        notifyItemChanged(position)
    }

    fun removeAt(position: Int): Int {
        if (position !in items.indices) return items.size
        items.removeAt(position)
        notifyItemRemoved(position)
        return items.size
    }

    companion object {
        private val MENU_KEYS = setOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_GUIDE
        )

        /**
         * 取图宽度（像素）。
         *
         * 卡片在屏幕上是 150dp × 1.5 = 225px 宽，要 240 就够；
         * 位图内存按宽度平方涨，320 是 240 的 1.8 倍，纯浪费堆（电视只有 96MB）。
         */
        const val POSTER_WIDTH = 240
    }
}
