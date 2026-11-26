package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 登录页兼容跳板。
 *
 * 正式登录体验已改为主页居中悬浮窗（[LoginDialog]），保留此 Activity 只为兼容旧快捷方式或外部调用。
 */
class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(HomeActivity.loginIntent(this, intent.getStringExtra(EXTRA_STATUS_HINT)))
        finish()
    }

    companion object {
        const val DEFAULT_SERVER = ""
        const val EXTRA_STATUS_HINT = "status_hint"

        /** 登录态失效后跳回主页并弹出悬浮登录窗。 */
        fun expiredIntent(ctx: Context, hint: String = "登录已过期，请重新登录"): Intent =
            HomeActivity.loginIntent(ctx, hint)
    }
}
