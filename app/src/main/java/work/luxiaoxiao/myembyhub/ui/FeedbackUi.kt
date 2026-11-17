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
 * 2. **采集 + 上传** —— 不可取消的进度框。采集要真去握手、真去建解码器，
 *    2~6 秒，没有反馈就是「卡死了」；
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
        val progress = showProgress(activity)
        val appCtx = activity.applicationContext
        val host = hostOf(Session.get(activity).server)

        worker.execute {
            val result = try {
                val report = DeviceReport.collect(appCtx, note, host)
                DeviceReport.upload(appCtx, endpoint, report)
            } catch (e: Throwable) {
                DeviceReport.Result.Fail(e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            main.post {
                try { progress.dismiss() } catch (ignored: Throwable) {}
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

    /** 不可取消的进度框：转圈 + 一句「在采什么」，让人知道没死。 */
    private fun showProgress(activity: Activity): Dialog {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LoadingOrbit(activity), LinearLayout.LayoutParams(
                dp(activity, 56), dp(activity, 56)
            ))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.feedback_collecting)
                setTextColor(ContextCompat.getColor(activity, R.color.content_secondary))
                textSize = 16f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 18) })
        }
        return TvDialog.show(
            activity,
            activity.getString(R.string.feedback),
            box,
            buttons = emptyList(),
            cancelable = false
        )
    }

    /** 从服务器地址里抠出主机名（TLS 探测要用它）。 */
    private fun hostOf(server: String): String {
        if (server.isEmpty()) return ""
        return try {
            val u = java.net.URL(if (server.startsWith("http")) server else "https://$server")
            u.host ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun dp(activity: Activity, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
        ).toInt()
}
