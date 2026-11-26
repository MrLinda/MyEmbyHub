package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.PhoneControlServer
import work.luxiaoxiao.myembyhub.net.QrUtil
import work.luxiaoxiao.myembyhub.net.Session
import work.luxiaoxiao.myembyhub.net.friendlyLoginError
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 主页居中悬浮登录窗（占屏幕宽度的 60%–80%，兼容 Android 4.3 / API 18）。
 *
 * 复用 [Theme.MyEmbyHub.Dialog]（透明窗口 + 暗色遮罩 + 自绘圆角卡片），
 * 进来直接起手机扫码服务并画二维码，右侧保留电视端输入表单做兜底。
 */
object LoginDialog {

    fun show(
        activity: Activity,
        statusHint: String? = null,
        onLoggedIn: () -> Unit,
        onCancelExit: () -> Unit
    ): Dialog {
        val dialog = Dialog(activity, R.style.Theme_MyEmbyHub_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_login)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val etServer = dialog.findViewById<EditText>(R.id.et_server)
        val etUser = dialog.findViewById<EditText>(R.id.et_user)
        val etPass = dialog.findViewById<EditText>(R.id.et_pass)
        val btnLogin = dialog.findViewById<TextView>(R.id.btn_login)
        val btnQrRefresh = dialog.findViewById<TextView>(R.id.btn_qr_refresh)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_status)
        val imgQr = dialog.findViewById<ImageView>(R.id.img_qr)
        val tvQrUrl = dialog.findViewById<TextView>(R.id.tv_qr_url)
        val tvQrPlaceholder = dialog.findViewById<TextView>(R.id.tv_qr_placeholder)

        val main = Handler(Looper.getMainLooper())
        val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "login-dialog").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }

        var loginServer: PhoneControlServer? = null
        var dismissed = false

        fun stopPhoneLogin() {
            loginServer?.stop()
            loginServer = null
            tvQrPlaceholder.visibility = View.GONE
        }

        fun saveSession(server: String, token: String, username: String, password: String, userId: String) {
            val s = Session.get(activity)
            s.server = server
            s.token = token
            s.username = username
            if (userId.isNotEmpty()) s.userId = userId
            if (password.isNotEmpty()) s.password = password
        }

        fun completeLogin() {
            if (dismissed) return
            stopPhoneLogin()
            dialog.dismiss()
            onLoggedIn()
        }

        fun refreshQr() {
            tvQrPlaceholder.visibility = View.VISIBLE
            tvQrPlaceholder.text = activity.getString(R.string.login_qr_creating)

            val port = PhoneControlServer.PORT
            worker.execute {
                val url = QrUtil.selfUrl(activity, port, "/?mode=login")
                val bmp = if (url.isEmpty()) null else QrUtil.encode(url, 420)
                main.post {
                    if (dismissed || activity.isFinishing || activity.isDestroyed) return@post
                    if (url.isEmpty()) {
                        tvQrPlaceholder.visibility = View.VISIBLE
                        tvQrPlaceholder.text = activity.getString(R.string.login_qr_no_ip)
                        imgQr.setImageDrawable(null)
                        tvQrUrl.text = ""
                        tvStatus.text = activity.getString(R.string.login_qr_no_ip_tip)
                    } else if (bmp == null) {
                        tvQrPlaceholder.visibility = View.VISIBLE
                        tvQrPlaceholder.text = activity.getString(R.string.login_qr_encode_failed)
                        imgQr.setImageDrawable(null)
                        tvQrUrl.text = url
                    } else {
                        tvQrPlaceholder.visibility = View.GONE
                        imgQr.setImageBitmap(bmp)
                        tvQrUrl.text = url
                    }
                }
            }
        }

        fun startPhoneLogin() {
            if (loginServer?.isRunning != true) {
                tvStatus.text = activity.getString(R.string.login_qr_waiting)
                loginServer = PhoneControlServer(
                    activity,
                    mode = PhoneControlServer.MODE_LOGIN,
                    onLogin = { srv, token, user, userId ->
                        main.post {
                            if (dismissed || activity.isFinishing || activity.isDestroyed) return@post
                            tvStatus.text = "登录成功，正在加载媒体库…"
                            saveSession(srv, token, user, "", userId)
                            completeLogin()
                        }
                    },
                    onSearch = { _ -> },
                    onFail = { msg ->
                        main.post {
                            if (!dismissed) tvStatus.text = "手机登录失败：$msg"
                        }
                    },
                    onExpire = {
                        main.post {
                            if (dismissed || activity.isFinishing || activity.isDestroyed) return@post
                            tvQrPlaceholder.visibility = View.VISIBLE
                            tvQrPlaceholder.text = activity.getString(R.string.login_qr_expired)
                            imgQr.setImageDrawable(null)
                            tvStatus.text = activity.getString(R.string.login_qr_expired_tip)
                        }
                    }
                )
                loginServer?.start()
            }
            refreshQr()
        }

        fun doLogin() {
            val server = etServer.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()

            if (server.isEmpty()) {
                tvStatus.text = "请填写服务器地址"
                etServer.requestFocus()
                return
            }
            if (user.isEmpty()) {
                tvStatus.text = "请填写用户名"
                etUser.requestFocus()
                return
            }

            btnLogin.isEnabled = false
            tvStatus.text = activity.getString(R.string.loading)

            val api = EmbyApi(activity, server)
            worker.execute {
                val result = api.login(user, pass)
                main.post {
                    if (dismissed || activity.isFinishing || activity.isDestroyed) return@post
                    btnLogin.isEnabled = true
                    result.onSuccess { token ->
                        saveSession(api.server, token, user, pass, api.userId)
                        completeLogin()
                    }.onFailure { e ->
                        tvStatus.text = activity.getString(R.string.login_failed) + "：" +
                            friendlyLoginError(e)
                    }
                }
            }
        }

        val session = Session.get(activity)
        etServer.setText(session.server)
        etUser.setText(session.username)
        etPass.setText(session.password)

        btnLogin.setOnClickListener { doLogin() }
        btnQrRefresh.setOnClickListener { startPhoneLogin() }

        btnQrRefresh.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                etServer.requestFocus()
                true
            } else {
                false
            }
        }
        etServer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                btnQrRefresh.requestFocus()
                true
            } else {
                false
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP &&
                !event.isCanceled
            ) {
                stopPhoneLogin()
                dialog.dismiss()
                onCancelExit()
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            dismissed = true
            loginServer?.stop()
            loginServer = null
            worker.shutdownNow()
        }

        startPhoneLogin()
        if (!statusHint.isNullOrEmpty()) {
            tvStatus.text = statusHint
        }

        dialog.show()
        dialog.window?.let { w ->
            val dm = activity.resources.displayMetrics
            val dp = dm.density
            val targetWidth = (dm.widthPixels * 0.72f).toInt()
                .coerceIn((640 * dp).toInt(), (860 * dp).toInt())
                .coerceAtMost((dm.widthPixels * 0.80f).toInt())
            w.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.72f)
            w.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            )
        }
        PopupGate.track(dialog)
        btnQrRefresh.post { btnQrRefresh.requestFocus() }
        return dialog
    }
}
