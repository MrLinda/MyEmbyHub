package work.luxiaoxiao.myembyhub.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView

/**
 * 顶部「让位」的 ScrollView：焦点滚动时把顶栏占的那一条从可视区里扣掉。
 *
 * **为什么需要这个类**：首页的 `scroll_sections` 是 `clipToPadding=false` +
 * `paddingTop=76dp`，内容能滚到顶栏底下（顶上那层渐隐再把它们吃掉），这是想要的效果。
 * 但框架算「要不要为焦点滚动」时，把可视上边界当成 y=0
 * （`ScrollView.computeScrollDeltaToGetChildRectOnScreen` 里 `screenTop = getScrollY()`，
 * 它不看 padding），于是焦点落到第一排时，它只滚到「第一排的顶边正好压在 y=0」就收手 ——
 * **上半张卡藏在顶栏底下，整页也不再是刚进来的样子**（实测截图：第一排海报被切了顶）。
 *
 * **修法**：把可视上边界下移 `paddingTop`。顶栏高度本来就写在 `android:paddingTop` 上，
 * 所以不用额外的自定义属性，直接拿 `paddingTop` 当这个 inset。
 *
 * ⚠️ 这里是自己重算，不是「调 super 再把 rect 挪一挪」：
 *    整体平移会把**下边界**也一起挪，导致贴在屏幕下沿、只被切掉一点点的焦点项
 *    反倒不滚了（那点切口同样难看）。只挪上边界才两个方向都对。
 */
class TopInsetScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {

    /**
     * 关掉 ScrollView 自带的「方向键翻页」。
     *
     * `ScrollView.dispatchKeyEvent` 是 `super.dispatchKeyEvent(event) || executeKeyEvent(event)` ——
     * 也就是说焦点在卡片上、卡片自己没吃掉方向键时，**外层 ScrollView 会再滚一次**
     * （`arrowScroll` → 平滑滚动）。实测日志：按 ↓ 两下再 ↑ 两下，
     * 焦点确实回到了第一排，但 ScrollView 自己还滚了 93px，整页停不到最顶上
     * （`scrollY 93 -> 324` 来自 `ScrollView.arrowScroll`）。
     *
     * 首页的滚动只该由「焦点进入哪个区块」决定，不该再叠一层按键翻页 ——
     * 遥控器上下本来就是在换排，页面跟着滚两遍必然错位。
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean = false

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val inset = paddingTop
        if (inset <= 0 || childCount == 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val content: View = getChildAt(0) ?: return super.computeScrollDeltaToGetChildRectOnScreen(rect)

        val viewHeight = height
        // 真正的可视上边界：顶栏底边在屏幕坐标里就是 paddingTop
        val screenTop = scrollY + inset
        val screenBottom = scrollY + viewHeight

        return if (rect.bottom > screenBottom && rect.top > screenTop) {
            // 焦点项在下方看不见：往下滚
            val delta =
                if (rect.height() > viewHeight) rect.top - screenTop        // 比一屏还高，露出顶边就够
                else rect.bottom - screenBottom                             // 否则整个塞进可视区
            // 别滚过头越过内容底部
            minOf(delta, content.bottom - screenBottom)
        } else if (rect.top < screenTop && rect.bottom < screenBottom) {
            // 焦点项在上方被顶栏挡住：往上滚
            val delta =
                if (rect.height() > viewHeight) -(screenBottom - rect.bottom)
                else -(screenTop - rect.top)
            // 别滚过顶部
            maxOf(delta, -scrollY)
        } else {
            0
        }
    }
}
