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
 * @param firstColumnCount 每排几个（行 = 1；网格 = 每行几个）。位置能被它整除的就是行首，按 ← 回调 [onLeftFromFirstColumn]。
 * @param onLeftFromFirstColumn 行首按 ←。返回 true 表示外面已经处理掉。
 * @param onCardFocused 某张卡拿到焦点（用来把整页滚回该回的姿势，见 `HomeActivity.createRow`）。
 */
class PosterAdapter(
    private val items: MutableList<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val firstRowCount: Int = 0,
    private val onUpFromFirstRow: ((Int) -> Boolean)? = null,
    private val firstColumnCount: Int = 1,
    private val onLeftFromFirstColumn: (() -> Boolean)? = null,
    private val onCardFocused: ((Int) -> Unit)? = null
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
        return Holder(v)
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
        holder.poster.visibility = View.INVISIBLE
        holder.placeholder.visibility = View.VISIBLE

        if (posterId.isNotEmpty()) {
            holder.request = ImageLoader.get(holder.itemView.context)
                .load(posterId, "Primary", POSTER_WIDTH) { bmp ->
                    // 回调回来时 holder 可能已经绑了别的条目
                    if (holder.itemId == item.id && bmp != null) {
                        holder.poster.setImageBitmap(bmp)
                        holder.poster.visibility = View.VISIBLE
                        holder.placeholder.visibility = View.GONE
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

        // 遥控器边界：交给外面处理，外面不接的话再让框架自己找邻居
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                position < firstRowCount && keyCode == KeyEvent.KEYCODE_DPAD_UP ->
                    onUpFromFirstRow?.invoke(position) ?: false

                // ⚠️ 行首按 ← 必须外面接住。不接的话框架的 focus search 会往左上
                //    找最近的可聚焦物 —— 正好是顶栏，用户按几下 ← 就被拽上去了（实测踩过）。
                position % firstColumnCount == 0 && keyCode == KeyEvent.KEYCODE_DPAD_LEFT ->
                    onLeftFromFirstColumn?.invoke() ?: false

                else -> false
            }
        }

        // 卡片拿到焦点时告诉外面「是第几张」，外面用它把整页滚回该有的姿势
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onCardFocused?.invoke(position)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        // 滚出屏幕就取消，别让看不见的海报继续占网络和线程池
        holder.request?.cancel()
        holder.request = null
        holder.itemId = null
    }

    override fun getItemCount(): Int = items.size

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

    companion object {
        /**
         * 取图宽度（像素）。
         *
         * 卡片在屏幕上是 150dp × 1.5 = 225px 宽，要 240 就够；
         * 位图内存按宽度平方涨，320 是 240 的 1.8 倍，纯浪费堆（电视只有 96MB）。
         */
        const val POSTER_WIDTH = 240
    }
}
