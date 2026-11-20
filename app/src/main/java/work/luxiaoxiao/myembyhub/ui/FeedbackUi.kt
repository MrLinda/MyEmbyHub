package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.DeviceReport
import work.luxiaoxiao.myembyhub.net.Session
import java.util.concurrent.Executor

/**
 * 设备反馈：填一句备注 → 采集 → 上报。
 *
 * 分三步，每一步都有独立的对话框（电视上不能「点了没反应」）：
 * 1. **填备注** —— 顺便说清会采集什么，别让人以为在偷数据；
 * 2. **采集 + 上传** —— 不可取消的进度框，**带百分比进度条**。
 *    采集要真去握手、真去建解码器，2~6 秒；原来只有一个转圈动画，
 *    用户看不出「在握手」还是「已经死了」，所以改成按实际阶段推进的进度条
 *    （2026-11-19 用户提的）；
 * 3. **结果** —— 成功给个编号（方便对账），失败把原因原样贴出来。
 *
 * 采集和上传都在传进来的 [worker] 上跑 —— 主线程只负责弹框。
 *
 * ## 为什么每个出口都要调 [onClosed]
 *
 * 调用方（[HomeActivity]）在弹反馈框之前会把「焦点即切换」的闸门关上，
 * 否则框一关、Activity 重新拿到焦点时系统会把焦点塞给第一个 tab，
 * 页面就被拽回首页。闸门必须在**所有**出口打开：取消、返回键、
 * 上报成功、上报失败 —— 漏一个就是「点了反馈之后顶栏失灵」。
 */
object FeedbackUi {

    fun show(
        activity: Activity,
        worker: Executor,
        main: Handler,
        toast: (String) -> Unit,
        onClosed: () -> Unit
    ) {
        val endpoint = Session.get(activity).feedbackEndpoint
        if (endpoint.isEmpty()) {
            TvDialog.message(
                activity,
                activity.getString(R.string.feedback),
                activity.getString(R.string.feedback_no_server),
                onDismiss = onClosed
            )
            return
        }

        val input = EditText(activity).apply {
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(activity, R.color.content_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.content_secondary))
            textSize = 17f
            isSingleLine = false
            maxLines = 3
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            hint = activity.getString(R.string.feedback_note_hint)
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.feedback_intro)
                setTextColor(ContextCompat.getColor(activity, R.color.content_secondary))
                textSize = 15f
                setLineSpacing(0f, 1.3f)
            })
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 14) })
        }

        TvDialog.show(
            activity,
            activity.getString(R.string.feedback),
            box,
            listOf(
                TvDialog.Btn(activity.getString(R.string.feedback_cancel)) { onClosed() },
                TvDialog.Btn(activity.getString(R.string.feedback_send), primary = true) {
                    submit(activity, worker, main, endpoint, input.text.toString().trim(), toast, onClosed)
                }
            ),
            initialFocus = input,
            onDismiss = onClosed
        )
    }

    private fun submit(
        activity: Activity,
        worker: Executor,
        main: Handler,
        endpoint: String,
        note: String,
        toast: (String) -> Unit,
        onClosed: () -> Unit
    ) {
        val progress = ProgressDialog(activity)
        val appCtx = activity.applicationContext
        val (host, port) = targetOf(Session.get(activity).server)

        // 采集线程上直接调 UI 会崩（见 AGENTS.md「后台线程碰 View」），
        // 所以进度回调统一 post 回主线程；进度条自己会丢掉倒退的值。
        val onProgress: (Int, String) -> Unit = { percent, label ->
            main.post { progress.update(percent, label) }
        }

        worker.execute {
            val result = try {
                val report = DeviceReport.collect(appCtx, note, host, port, onProgress)
                DeviceReport.upload(appCtx, endpoint, report, onProgress)
            } catch (e: Throwable) {
                DeviceReport.Result.Fail(e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            main.post {
                progress.close()
                val text = when (result) {
                    is DeviceReport.Result.Ok ->
                        activity.getString(R.string.feedback_ok, result.id)
                    is DeviceReport.Result.Fail -> {
                        val msg = activity.getString(R.string.feedback_failed, result.message)
                        toast(msg)
                        msg
                    }
                }
                TvDialog.message(
                    activity,
                    activity.getString(R.string.feedback),
                    text,
                    onDismiss = onClosed
                )
            }
        }
    }

    /**
     * 不可取消的进度框：一条进度条 + 百分比 + 一句「在干什么」。
     *
     * 为什么不是转圈动画：采集要 2~6 秒，转圈**不带任何信息**，
     * 用户分不清「在握手」和「已经死了」（2026-11-19 用户提的原话）。
     * 进度条即便走得慢，也明确告诉人「还在动、走到了哪一步」。
     */
    private class ProgressDialog(private val activity: Activity) {

        private val bar = TvProgressBar(activity)
        private val percent = TextView(activity).apply {
            text = activity.getString(R.string.feedback_percent, 0)
            setTextColor(ContextCompat.getColor(activity, R.color.content_primary))
            textSize = 15f
            gravity = Gravity.END
        }
        private val label = TextView(activity).apply {
            text = activity.getString(R.string.feedback_collect_tip)
            setTextColor(ContextCompat.getColor(activity, R.color.content_secondary))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }

        private val dialog: Dialog

        init {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(bar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FeedbackUi.dp(activity, 14) })
                addView(percent, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = FeedbackUi.dp(activity, 8) })
            }
            dialog = TvDialog.show(
                activity,
                activity.getString(R.string.feedback),
                box,
                buttons = emptyList(),
                cancelable = false
            )
        }

        /** 必须在主线程调用。 */
        fun update(value: Int, text: String?) {
            bar.progress = value
            percent.text = activity.getString(R.string.feedback_percent, value.coerceIn(0, 100))
            if (!text.isNullOrEmpty()) label.text = text
        }

        fun close() {
            try { dialog.dismiss() } catch (ignored: Throwable) {}
        }
    }

    /**
     * 从服务器地址里抠出主机名 + 端口（TLS 探测要用它）。
     *
     * ⚠️ 端口必须跟着走：Emby 跑在 1234 / 8096 这类非标端口上很常见，
     * 写死 443 探的是同机另一个服务（甚至是别人的站），
     * 握出来的结论和用户真正那条连接无关 —— 那是比「没数据」更坏的情况。
     */
    private fun targetOf(server: String): Pair<String, Int> {
        if (server.isEmpty()) return "" to 443
        return try {
            val u = java.net.URL(if (server.startsWith("http")) server else "https://$server")
            // 显式写了端口就用它；没写按 443（TLS 探测固定走 https，不管地址填的是 http 还是 https）
            (u.host ?: "") to (if (u.port > 0) u.port else 443)
        } catch (ignored: Throwable) {
            "" to 443
        }
    }

    private fun dp(activity: Activity, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
        ).toInt()
}
