package work.luxiaoxiao.myembyhub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R

/**
 * 一条细的**确定进度**进度条（0~100）。
 *
 * 为什么不用系统的 `ProgressBar`：
 * ① 4.3 的 `progressBarStyleHorizontal` 是 Holo 主题的方角蓝色，
 *    跟这套暖色 + 深底的语言完全两套；
 * ② 系统那套要 `progressDrawable` 里塞多层 layer-list 才能改颜色，
 *    而这台机器上每多一层 drawable 就多一次绘制开销 —— 这里两笔 canvas 就画完。
 *
 * ## 为什么要它
 *
 * 反馈采集要真去握手、真去建解码器，2~6 秒。原来只有一个转圈动画，
 * 用户看不到「还剩多少」，体感就是卡住了（用户 2026-11-19 提的原话）。
 * 有了百分比，哪怕慢，人也知道它在动。
 *
 * `progress` 只做 `invalidate()`，不 requestLayout —— 尺寸恒定，重排纯属浪费。
 */
class TvProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 轨道：整条暗底，既给出总长度的参照，也避免 0% 时看着像没加载出来。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.stroke_subtle)
    }

    /** 已完成的那一段。 */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.indicator_bar)
    }

    private val rect = RectF()

    var progress: Int = 0
        set(value) {
            val v = value.coerceIn(0, 100)
            if (v == field) return
            field = v
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 高度只认自己定的 6dp：调用方给 WRAP_CONTENT 时也得有个确定值，
        // 否则 MeasureSpec.getSize 在 UNSPECIFIED 下返回 0，条会「消失」。
        val h = resolveSize((HEIGHT_DP * density).toInt().coerceAtLeast(1), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (progress <= 0) return
        val fillW = width.toFloat() * progress / 100f
        // 圆角半径不能超过实际宽度的一半，否则刚过 0% 时会画成一个奇怪的水滴
        val fr = minOf(r, fillW / 2f)
        rect.set(0f, 0f, fillW, h)
        canvas.drawRoundRect(rect, fr, fr, fillPaint)
    }

    private companion object {
        /** 6dp：电视上 3 米外看得见，又不至于抢文字的位置。 */
        const val HEIGHT_DP = 6f
    }
}
