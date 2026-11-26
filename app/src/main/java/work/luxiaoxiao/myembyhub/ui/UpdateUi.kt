package work.luxiaoxiao.myembyhub.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.MediaItem
import work.luxiaoxiao.myembyhub.net.Session
import work.luxiaoxiao.myembyhub.net.UpdateManager
import work.luxiaoxiao.myembyhub.net.friendlyError
import java.util.concurrent.Executor

/**
 * 在线更新的界面部分（检查 → 确认 → 下载 → 调起安装器）。
 *
 * 首页从这里进，只传自己的 worker / 主线程 Handler / toast。
 */
object UpdateUi {

    /**
     * 检查更新。
     *
     * 三步都是异步的：网络在 [worker] 上，对话框回主线程。
     * `Release.from` 会显示在对话框里 —— 镜像站时通时不通，
     * 用户能念出「是哪台机器给的」，比一句「更新失败」有用。
     *
     * ⚠️ 等待期间必须**一直有东西在动**：检查要挨个试四台镜像，每台最多
     * 6 秒连接 + 12 秒读，全失败能拖到几十秒。早先这里只弹一句会自己消失的
     * Toast「正在检查更新…」，之后屏幕毫无反馈 —— 用户分不清「还在试」和
     * 「已经卡死」，只能再按一次，于是又叠一轮请求。见 [R.layout.dialog_loading]。
     */
    fun check(
        activity: Activity,
        worker: Executor,
        main: Handler,
        toast: (String) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_loading, null)
        val arc = view.findViewById<View>(R.id.arc_loading)
        val label = view.findViewById<TextView>(R.id.tv_loading_text)

        // 弧自己转。View.rotation 是 API 11 的，4.3 上能用；
        // 不走 ProgressBar 的 indeterminate —— 那套动画由系统 drawable 驱动，
        // 换成自定义图形之后转不转全看 ROM，不如自己拿 ValueAnimator 甩。
        val spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { arc.rotation = it.animatedValue as Float }
        }
        // 文字也在变：万一某个 ROM 上 sweep 渐变画不出来（弧是静止的一坨），
        // 这行点号照样能说明程序没死。
        val beats = arrayOf("正在检查更新", "正在检查更新 .", "正在检查更新 . .", "正在检查更新 . . .")
        var beat = 0
        val ticker = object : Runnable {
            override fun run() {
                label.text = beats[beat % beats.size]
                beat++
                main.postDelayed(this, 350)
            }
        }

        // 按返回把面板关掉 = 不想等了。结果回来时别再甩一个框过去，
        // 改一句 toast 收场；只是「有新版本」仍然弹框，那是值得打断的事。
        var closedByUser = false
        val dlg = TvDialog.show(
            activity,
            "检查更新",
            view,
            buttons = emptyList(),
            cancelable = true,
            onDismiss = {
                closedByUser = true
                spin.cancel()
                main.removeCallbacks(ticker)
            }
        )
        spin.start()
        main.post(ticker)

        worker.execute {
            val um = UpdateManager(activity)
            val manifest = Session.get(activity).updateManifest
            val result = um.check(manifest)
            main.post {
                main.removeCallbacks(ticker)
                spin.cancel()
                val wasClosed = closedByUser
                if (!wasClosed) dlg.dismiss()
                result.onSuccess { release ->
                    if (release == null) {
                        if (wasClosed) {
                            toast("已是最新版本")
                        } else {
                            TvDialog.message(
                                activity,
                                "没有新版本",
                                "当前已是最新版本：${um.currentVersionName()}"
                            )
                        }
                    } else {
                        confirm(activity, um, release, worker, main, toast)
                    }
                }.onFailure { e ->
                    // 原始异常是英文技术报错（failed to connect to ...），
                    // 电视上没法自救 → 翻成中文，更新地址照样留着
                    if (wasClosed) {
                        toast("检查更新失败")
                    } else {
                        TvDialog.message(
                            activity,
                            "检查更新失败",
                            "${friendlyError(e)}\n\n更新地址：$manifest",
                            scrollable = true
                        )
                    }
                }
            }
        }
    }

    private fun confirm(
        activity: Activity,
        um: UpdateManager,
        release: UpdateManager.Release,
        worker: Executor,
        main: Handler,
        toast: (String) -> Unit
    ) {
        val msg = buildString {
            append("发现新版本：").append(release.versionName).append('\n')
            if (release.notes.isNotEmpty()) append(release.notes).append('\n')
            append("大小：").append(MediaItem.formatSize(release.size)).append('\n')
            if (release.from.isNotEmpty()) append("来源：").append(release.from).append('\n')
            append("下载后需要你在电视上按一次「安装」。")
        }
        TvDialog.confirm(
            activity,
            "在线更新",
            msg,
            confirmText = "下载",
            cancelText = "稍后",
            // 更新说明会越来越长（每版都往里堆），不给滚动的话内容一多
            // 就把面板顶出屏幕、按钮直接看不见 —— 系统安装器也是内容自己滚
            scrollable = true
        ) {
            download(activity, um, release, worker, main, toast)
        }
    }

    private fun download(
        activity: Activity,
        um: UpdateManager,
        release: UpdateManager.Release,
        worker: Executor,
        main: Handler,
        toast: (String) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null)
        val tv = view.findViewById<TextView>(R.id.tv_progress_text)
        val bar = view.findViewById<ProgressBar>(R.id.pb_progress)
        // 不可取消：下载中按返回会把进度条关掉，但下载还在后台跑
        val dlg = TvDialog.show(
            activity,
            "正在下载更新",
            view,
            buttons = emptyList(),
            cancelable = false
        )

        um.download(release,
            onProgress = { done, total ->
                main.post {
                    tv.text = if (total > 0) {
                        "已下载 ${MediaItem.formatSize(done)} / ${MediaItem.formatSize(total)}"
                    } else {
                        "已下载 ${MediaItem.formatSize(done)}"
                    }
                    if (total > 0) {
                        bar.max = 100
                        bar.progress = ((done * 100) / total).toInt()
                    }
                }
            },
            onDone = { path ->
                main.post {
                    dlg.dismiss()
                    toast("下载完成")
                    // 未拿到「安装未知应用」授权时 install() 自己会 toast 说明并跳设置页，
                    // 这里不再叠一句「正在调起安装器」把真正的原因盖掉。
                    if (!um.install(path)) toast("调起安装器失败")
                }
            },
            onError = { msg ->
                main.post {
                    dlg.dismiss()
                    TvDialog.message(activity, "下载失败", msg)
                }
            })
    }
}