package com.example.myembyhub.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myembyhub.R

/**
 * 遥控器菜单。
 *
 * ⚠️ **不要用系统 `AlertDialog` + `ListView`**：
 * 系统对话框在 4.3 上是 Holo 蓝框、标题带一条青蓝色分隔线，
 * 跟本 App 的暖色（accent）语言完全两套，看着像「塞进来的系统弹窗」。
 * 现在改成 **透明窗口 + 自绘面板**（`tv_menu_dialog.xml`）：
 * 深色圆角卡片 + 暖色竖条标题 + 圆角焦点行，跟左栏 tab 同一套视觉。
 *
 * 列表用 RecyclerView 而不是 ListView：
 * 主界面已经验证过 RecyclerView + focusable item 在 4.3 上的焦点行为，
 * 焦点框样式也更好控制（`state_focused` 立刻变色）。
 *
 * ⚠️ 不用 `AlertDialog.setItems`：它没法把某一项画成灰色（禁用态），
 * 也无法做到「点选后不关闭」，所以自己拿 Adapter 控制。
 */
object TvMenu {

    /**
     * 菜单项。
     *
     * @param enabled false → 灰色、不可点（比如「季」这一层没有排序可选）
     */
    data class Item(
        val title: String,
        val sub: String = "",
        val enabled: Boolean = true,
        val action: () -> Unit
    )

    /** 支持禁用态 + 焦点竖条的 adapter。 */
    private class MenuAdapter(private val onPick: (Int) -> Unit) :
        RecyclerView.Adapter<MenuAdapter.Holder>() {

        /** 菜单行。 */
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val indicator: View = view.findViewById(R.id.menu_indicator)
            val title: TextView = view.findViewById(R.id.tv_menu_item_title)
            val sub: TextView = view.findViewById(R.id.tv_menu_item_sub)
        }

        private var items: List<Item> = emptyList()

        fun bind(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.tv_menu_item, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.title.text = item.title
            holder.sub.text = item.sub
            // 用 invisible 而不是 gone：留出位置，副标题出现/消失时标题不左右跳
            holder.sub.visibility = if (item.sub.isEmpty()) View.INVISIBLE else View.VISIBLE

            val main = ContextCompat.getColor(
                ctx, if (item.enabled) R.color.text_primary else R.color.text_disabled)
            val second = ContextCompat.getColor(
                ctx, if (item.enabled) R.color.text_secondary else R.color.text_disabled)
            holder.title.setTextColor(main)
            holder.sub.setTextColor(second)

            // 禁用项：不可聚焦，方向键会直接跳过去
            holder.itemView.isEnabled = item.enabled
            holder.itemView.isFocusable = item.enabled
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.indicator.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            }
            holder.itemView.setOnClickListener { onPick(position) }
        }

        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            holder.indicator.visibility =
                if (holder.itemView.hasFocus()) View.VISIBLE else View.INVISIBLE
        }

        override fun getItemCount(): Int = items.size
    }

    /**
     * 弹出菜单，**选择后自动关闭**（适合「选了就跳走」的动作）。
     *
     * @param onDismiss 取消（按返回）时的回调
     */
    fun show(
        ctx: Context,
        title: String,
        items: List<Item>,
        onDismiss: (() -> Unit)? = null
    ) {
        lateinit var dialog: Dialog
        val adapter = MenuAdapter { which ->
            val item = items.getOrNull(which)
            if (item != null && item.enabled) {
                // 先关自己再执行动作：动作里多半会再弹一个对话框
                //（比如「排序方式」→ 排序子菜单），不先关的话焦点会打架。
                dialog.dismiss()
                item.action.invoke()
            }
        }
        adapter.bind(items)
        val (dlg, rv) = buildDialog(ctx, title, adapter, onDismiss)
        dialog = dlg
        showDialog(dlg, rv, items)
    }

    /**
     * 弹出菜单，**选择后不关闭**（适合需要连续改几项的设置）。
     *
     * 排序就是这种场景：要先选排序字段、再选升序/降序，点一下就关掉很别扭。
     * 这里点选只回调 + 重绘勾选状态，菜单由用户按「返回」自己关。
     *
     * @param provider 每次渲染 / 点击时调用，返回**当前状态**下的菜单项快照
     * @param onDismiss 取消（按返回）时的回调
     */
    fun showPersistent(
        ctx: Context,
        title: String,
        provider: () -> List<Item>,
        onDismiss: (() -> Unit)? = null
    ) {
        // 点击回调里要用 adapter 重新渲染，而 adapter 又要拿这个回调 → 用 lateinit 解开循环
        lateinit var adapter: MenuAdapter
        adapter = MenuAdapter { which ->
            val item = provider().getOrNull(which)
            if (item != null && item.enabled) {
                item.action.invoke()
                adapter.bind(provider())
            }
        }
        adapter.bind(provider())
        val (dlg, rv) = buildDialog(ctx, title, adapter, onDismiss)
        showDialog(dlg, rv, provider())
    }

    /**
     * 搭出对话框：透明窗口 + 自绘面板。
     *
     * @return 对话框本身 + 里面的列表（用来之后摆焦点）
     */
    private fun buildDialog(
        ctx: Context,
        title: String,
        adapter: MenuAdapter,
        onDismiss: (() -> Unit)?
    ): Pair<Dialog, RecyclerView> {
        val dialog = Dialog(ctx, R.style.Theme_MyEmbyHub_Dialog)
        // 自己画标题，系统那个不要
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(ctx).inflate(R.layout.tv_menu_dialog, null, false)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tv_menu_title).text = title
        dialog.setOnCancelListener { onDismiss?.invoke() }

        val rv = view.findViewById<RecyclerView>(R.id.rv_menu)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        return dialog to rv
    }

    /** 显示对话框，并把焦点交给第一项（可用的那一项）。 */
    private fun showDialog(dialog: Dialog, rv: RecyclerView, items: List<Item>) {
        // 窗口背景透明：只留自绘的面板，系统那个九宫格边框不要
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 宽度固定（电视上 1280dp 宽，菜单 560dp 刚好留出两边余量）
        val width = (560 * dialog.context.resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 焦点给第一项：菜单刚弹出时不能一个高亮都没有，
        // 否则用户不知道方向键能往哪儿走。
        rv.post {
            var tries = 0
            val attempt = object : Runnable {
                override fun run() {
                    val pos = items.indexOfFirst { it.enabled }
                    val holder =
                        if (pos >= 0) rv.findViewHolderForAdapterPosition(pos) else null
                    if (holder != null && holder.itemView.requestFocus()) return
                    if (tries++ < 8) rv.postDelayed(this, 60) else rv.requestFocus()
                }
            }
            rv.post(attempt)
        }
    }
}
