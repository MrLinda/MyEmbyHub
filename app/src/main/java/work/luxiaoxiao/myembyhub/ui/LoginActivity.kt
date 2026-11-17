package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.PhoneControlServer
import work.luxiaoxiao.myembyhub.net.QrUtil
import work.luxiaoxiao.myembyhub.net.Session
import java.util.concurrent.Executors

/**
 * 登录页。
 *
 * 两种登录方式：
 * 1. **手机扫码登录**（推荐）：电视显示二维码，手机扫码后在手机上输入账号密码。
 *    这台电视没有软键盘，用遥控器敲密码几乎不可用。
 * 2. 电视端直接输入（服务器已经配好时其实用不上）。
 */
class LoginActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView

    // ---- 扫码登录相关 ----
    private lateinit var panelQr: View
    private lateinit var imgQr: ImageView
    private lateinit var tvQrUrl: TextView
    private var loginServer: PhoneControlServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etServer = findViewById(R.id.et_server)
        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_pass)
        btnLogin = findViewById(R.id.btn_login)
        tvStatus = findViewById(R.id.tv_status)
        panelQr = findViewById(R.id.panel_qr)
        imgQr = findViewById(R.id.img_qr)
        tvQrUrl = findViewById(R.id.tv_qr_url)

        val session = Session.get(this)
        etServer.setText(session.server.ifEmpty { DEFAULT_SERVER })
        etUser.setText(session.username)
        etPass.setText(session.password)

        btnLogin.setOnClickListener { doLogin() }
        findViewById<Button>(R.id.btn_qr_login).setOnClickListener { startPhoneLogin() }

        if (session.isLoggedIn) {
            goLibrary()
        }
    }

    // ==================== 电视端输入 ====================

    private fun doLogin() {
        val server = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()

        if (server.isEmpty()) {
            tvStatus.text = "请填写服务器地址"
            return
        }
        if (user.isEmpty()) {
            tvStatus.text = "请填写用户名"
            return
        }

        btnLogin.isEnabled = false
        tvStatus.text = getString(R.string.loading)

        val api = EmbyApi(this, server)
        worker.execute {
            val result = api.login(user, pass)
            main.post {
                btnLogin.isEnabled = true
                result.onSuccess { token ->
                    saveSession(api.server, token, user, pass, api.userId)
                    goLibrary()
                }.onFailure { e ->
                    tvStatus.text = getString(R.string.login_failed) + "：" + work.luxiaoxiao.myembyhub.net.friendlyError(e)
                }
            }
        }
    }

    // ==================== 手机扫码登录 ====================

    private fun startPhoneLogin() {
        if (loginServer != null) {
            // 已经在扫码模式，刷新一次二维码
            refreshQr()
            return
        }
        panelQr.visibility = View.VISIBLE
        tvStatus.text = "请用手机扫描下方二维码"

        val server = PhoneControlServer(
            this,
            mode = PhoneControlServer.MODE_LOGIN,
            onLogin = { srv, token, user, userId ->
                main.post {
                    tvStatus.text = "登录成功，正在进入媒体库…"
                    saveSession(srv, token, user, "", userId)
                    stopPhoneLogin()
                    goLibrary()
                }
            },
            onSearch = { _ -> },
            onFail = { msg ->
                main.post { tvStatus.text = "手机登录失败：$msg" }
            }
        )
        loginServer = server
        server.start()
        refreshQr()
    }

    /** 生成二维码并显示。 */
    private fun refreshQr() {
        worker.execute {
            val url = QrUtil.selfUrl(this, PhoneControlServer.PORT, "/?mode=login")
            val bmp = if (url.isEmpty()) null else QrUtil.encode(url, 640)
            main.post {
                if (url.isEmpty()) {
                    tvQrUrl.text = "拿不到电视的局域网 IP，无法生成二维码"
                    imgQr.setImageDrawable(null)
                } else {
                    tvQrUrl.text = url
                    if (bmp != null) {
                        imgQr.setImageBitmap(bmp)
                    } else {
                        imgQr.setImageDrawable(null)
                        tvQrUrl.text = "$url（二维码生成失败，请手动输入上面的地址）"
                    }
                }
            }
        }
    }

    private fun stopPhoneLogin() {
        loginServer?.stop()
        loginServer = null
        panelQr.visibility = View.GONE
    }

    // ==================== 公共 ====================

    private fun saveSession(server: String, token: String, username: String, password: String, userId: String) {
        val s = Session.get(this)
        s.server = server
        s.token = token
        s.username = username
        // 真实用户 id：Resume / NextUp 都要用，缺了「最近观看」会是空的
        if (userId.isNotEmpty()) s.userId = userId
        if (password.isNotEmpty()) s.password = password
    }

    private fun goLibrary() {
        // 登录后进新的首页（顶部导航 + 横滑行）；老的列表视图还能从首页顶栏进
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        loginServer?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /**
         * 预填的服务器地址。
         *
         * ⚠️ **保持为空**：这里曾经硬编码过某个人的私有 Emby 服务器。
         * 一旦开源发布，任何人装上 APK 都会默认连到那台机器上，
         * 等于把别人的服务器开放给全世界当免费 Emby 用。
         * 空字符串 → 让用户自己填自己的服务器。
         */
        const val DEFAULT_SERVER = ""
    }
}
