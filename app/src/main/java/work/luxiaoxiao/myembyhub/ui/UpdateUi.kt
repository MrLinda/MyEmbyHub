package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.os.Handler
import android.view.LayoutInflater
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
 * 首页和老的列表视图（[LibraryActivity]）都从这里进，各自只传自己的
 * worker / 主线程 Handler / toast —— 那两个 Activity 已经各有一份，
 * 没必要为了这个功能再各写一遍三段对话框。
 */
object UpdateUi {

    /**
     * 检查更新。
     *
     * 三步都是异步的：网络在 [worker] 上，对话框回主线程。
     * `Release.from` 会显示在对话框里 —— 镜像站时通时不通，
     * 用户能念出「是哪台机器给的」，比一句「更新失败」有用。
     */
    fun check(
        activity: Activity,
        worker: Executor,
        main: Handler,
        toast: (String) -> Unit
    ) {
        toast("正在检查更新…")
        worker.execute {
            val um = UpdateManager(activity)
            val manifest = Session.get(activity).updateManifest
            val result = um.check(manifest)
            main.post {
                result.onSuccess { release ->
                    if (release == null) {
                        TvDialog.message(
                            activity,
                            "没有新版本",
                            "当前已是最新版本：${um.currentVersionName()}"
                        )
                    } else {
                        confirm(activity, um, release, worker, main, toast)
                    }
                }.onFailure { e ->
                    // 原始异常是英文技术报错（failed to connect to ...），
                    // 电视上没法自救 → 翻成中文，更新地址照样留着
                    TvDialog.message(
                        activity,
                        "检查更新失败",
                        "${friendlyError(e)}\n\n更新地址：$manifest"
                    )
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
            cancelText = "稍后"
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
                    toast("下载完成，正在调起安装器…")
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