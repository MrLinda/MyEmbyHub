package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.content.SharedPreferences

/**
 * 会话与凭据持久化。
 *
 * 用 `SharedPreferences`（API 1 就有），不用 DataStore（要 AndroidX 且更重）。
 *
 * ⚠️ token 属于敏感信息，这台电视是家里的设备，暂不做加密；
 * 但正式发布前建议至少混淆存储，或改用 Keystore。
 */
class Session private constructor(private val sp: SharedPreferences) {

    var server: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(v) = sp.edit().putString(KEY_SERVER, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    /**
     * 登录用户的真实 id（Emby 登录响应的 `User.Id`）。
     *
     * ⚠️ 必须存真实值。早先代码里用的是全 0 的假 id，
     * `/Users/{id}/Items/Resume` 会返回 0 条 —— 「最近观看」永远是空的。
     * 老版本升级上来的会话这里会是空串，[EmbyApi.uid] 会退回兜底 id。
     */
    var userId: String
        get() = sp.getString(KEY_USER_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_ID, v).apply()

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    /** 记住密码，方便下次直接登录（4.3 电视没有软键盘，输入成本很高）。 */
    var password: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_PASS, v).apply()

    /**
     * 在线更新的版本清单地址。
     *
     * 默认指向家里电脑上的静态服务；换网络时可以在应用里改（存这里即可）。
     */
    var updateManifestUrl: String
        get() = sp.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_MANIFEST)
            ?: DEFAULT_UPDATE_MANIFEST
        set(v) = sp.edit().putString(KEY_UPDATE_URL, v).apply()

    val isLoggedIn: Boolean get() = token.isNotEmpty() && server.isNotEmpty()

    fun clear() {
        sp.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val FILE = "myembyhub_session"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PASS = "password"
        private const val KEY_UPDATE_URL = "update_manifest_url"

        /** 默认的版本清单地址（电脑上的静态服务）。 */
        const val DEFAULT_UPDATE_MANIFEST = "http://192.168.1.50:8765/version.json"

        @Volatile
        private var instance: Session? = null

        fun get(ctx: Context): Session =
            instance ?: synchronized(this) {
                instance ?: Session(
                    ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
