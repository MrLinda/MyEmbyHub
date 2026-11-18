package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
 * **进来就是扫码界面** —— 手机登录服务在 `onCreate` 里直接起来，二维码立刻画出来。
 * 以前要先按一个「手机扫码登录」按钮才出二维码：遥控器上多按一次只是门槛，
 * 而这一页存在的理由就是「电视没法打字」，那个按钮等于让用户先证明自己想扫码。
 *
 * 右边保留电视端直接输入的三个框做兜底（服务器地址 / 用户名 / 密码），
 * 服务器已经配好或者临时换一台机器时还能用。
 */
class LoginActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: TextView
    private lateinit var btnQrRefresh: TextView
    private lateinit var tvStatus: TextView
    private lateinit var imgQr: ImageView
    private lateinit var tvQrUrl: TextView
    private lateinit var tvQrPlaceholder: TextView

    private var loginServer: PhoneControlServer? = null

    /** 页面是否已经销毁：worker 上的回调要自己判，别往死掉的 View 上写。 */
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etServer = findViewById(R.id.et_server)
        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_pass)
        btnLogin = findViewById(R.id.btn_login)
        btnQrRefresh = findViewById(R.id.btn_qr_refresh)
        tvStatus = findViewById(R.id.tv_status)
        imgQr = findViewById(R.id.img_qr)
        tvQrUrl = findViewById(R.id.tv_qr_url)
        tvQrPlaceholder = findViewById(R.id.tv_qr_placeholder)

        val session = Session.get(this)
        etServer.setText(session.server.ifEmpty { DEFAULT_SERVER })
        etUser.setText(session.username)
        etPass.setText(session.password)

        btnLogin.setOnClickListener { doLogin() }
        btnQrRefresh.setOnClickListener { startPhoneLogin() }

        if (session.isLoggedIn) {
            goLibrary()
            return
        }

        // 默认就是扫码：不用点，直接起服务画码
        startPhoneLogin()
        // 焦点落在「刷新二维码」上：遥控器一进来就有个明确的可按项，
        // 而不是停在某个输入框里让用户以为自己要开始打字
        btnQrRefresh.post { btnQrRefresh.requestFocus() }
    }

    // ==================== 电视端输入 ====================

    private fun doLogin() {
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
        tvStatus.text = getString(R.string.loading)

        val api = EmbyApi(this, server)
        worker.execute {
            val result = api.login(user, pass)
            main.post {
                if (destroyed) return@post
                btnLogin.isEnabled = true
                result.onSuccess { token ->
                    saveSession(api.server, token, user, pass, api.userId)
                    stopPhoneLogin()
                    goLibrary()
                }.onFailure { e ->
                    // 登录路径专用：401 时说的是「账号或密码不对」，
                    // 不能复用 friendlyError（它会把 401 翻成「登录已过期」）
                    tvStatus.text = getString(R.string.login_failed) + "：" +
                        work.luxiaoxiao.myembyhub.net.friendlyLoginError(e)
                }
            }
        }
    }

    // ==================== 手机扫码登录 ====================

    /**
     * 起手机登录服务并画二维码。
     *
     * 重复调用是安全的：服务还活着就只重画二维码；**已经死了也要能重新拉起来** ——
     * [PhoneControlServer] 有 10 分钟自杀，用户可能就是在电视前看完一集回来才发现码过期了。
     * 所以判断条件是 `isRunning`，不是 `loginServer == null`。
     */
    private fun startPhoneLogin() {
        if (loginServer?.isRunning != true) {
            tvStatus.text = getString(R.string.login_qr_waiting)

            loginServer = PhoneControlServer(
                this,
                mode = PhoneControlServer.MODE_LOGIN,
                onLogin = { srv, token, user, userId ->
                    main.post {
                        if (destroyed) return@post
                        tvStatus.text = "登录成功，正在进入媒体库…"
                        saveSession(srv, token, user, "", userId)
                        stopPhoneLogin()
                        goLibrary()
                    }
                },
                onSearch = { _ -> },
                onFail = { msg ->
                    main.post {
                        if (!destroyed) tvStatus.text = "手机登录失败：$msg"
                    }
                },
                onExpire = {
                    // 服务自己到 10 分钟上限关了。二维码还在屏幕上、看着完全正常，
                    // 但手机扫过去只会「无法连接」—— 必须在这里明说，
                    // 否则用户会怀疑是电视断网了。
                    main.post {
                        if (destroyed) return@post
                        tvQrPlaceholder.visibility = View.VISIBLE
                        tvQrPlaceholder.text = getString(R.string.login_qr_expired)
                        imgQr.setImageDrawable(null)
                        tvStatus.text = getString(R.string.login_qr_expired_tip)
                    }
                }
            )
            loginServer?.start()
        }
        refreshQr()
    }

    /** 生成二维码并显示。 */
    private fun refreshQr() {
        tvQrPlaceholder.visibility = View.VISIBLE
        tvQrPlaceholder.text = getString(R.string.login_qr_creating)

        val port = PhoneControlServer.PORT
        worker.execute {
            val url = QrUtil.selfUrl(this, port, "/?mode=login")
            val bmp = if (url.isEmpty()) null else QrUtil.encode(url, 560)
            main.post {
                if (destroyed) return@post
                if (url.isEmpty()) {
                    tvQrPlaceholder.visibility = View.VISIBLE
                    tvQrPlaceholder.text = getString(R.string.login_qr_no_ip)
                    imgQr.setImageDrawable(null)
                    tvQrUrl.text = ""
                    tvStatus.text = getString(R.string.login_qr_no_ip_tip)
                } else if (bmp == null) {
                    tvQrPlaceholder.visibility = View.VISIBLE
                    tvQrPlaceholder.text = getString(R.string.login_qr_encode_failed)
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

    private fun stopPhoneLogin() {
        loginServer?.stop()
        loginServer = null
        tvQrPlaceholder.visibility = View.GONE
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

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // 焦点从左边二维码列往右 / 往左走时，换列要落到一个真正可聚焦的项上，
        // 否则框架会退回到「屏幕最上/最左那个」，用户看着像焦点丢了
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
            currentFocus === btnQrRefresh
        ) {
            etServer.requestFocus()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
            currentFocus === etServer
        ) {
            btnQrRefresh.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统栏会在焦点变化后偷偷回来，这里再收一次（跟 HomeActivity 同一套处理）
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        destroyed = true
        loginServer?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LOW_PROFILE
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
