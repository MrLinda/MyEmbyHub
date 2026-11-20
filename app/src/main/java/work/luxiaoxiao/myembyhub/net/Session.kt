package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.content.SharedPreferences
import work.luxiaoxiao.myembyhub.BuildConfig

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
     * 默认指向 **GitHub Release 里的固定资产**（`releases/latest/download/version.json`），
     * 换网络或想换镜像时可以在应用里改（存这里即可）。
     * 清空则退回 [UpdateManager.DEFAULT_MANIFEST]。
     */
    var updateManifestUrl: String
        get() = sp.getString(KEY_UPDATE_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_UPDATE_URL, v).apply()

    /** 实际要请求的清单地址：用户填的优先，没填走打包时定下的默认值。 */
    val updateManifest: String
        get() = updateManifestUrl.ifEmpty { UpdateManager.DEFAULT_MANIFEST }

    /**
     * 设备反馈服务器地址（如 `https://feedback.example.com`）。
     *
     * 用户填的优先，没填走打包时烤进来的 [BuildConfig.FEEDBACK_URL]。
     * 留空 = 这个版本没配反馈服务，界面上会提示「没有配置反馈服务器」。
     *
     * ⚠️ 反馈服务端**不在这个公开仓库里**（放在 `MyEmbyHub-feedback`），
     * 所以地址只能由使用者自己填或自己打包时指定。
     */
    var feedbackUrl: String
        get() = sp.getString(KEY_FEEDBACK_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_FEEDBACK_URL, v).apply()

    /** 实际要上报的地址：用户填的优先，没填走打包默认值。 */
    val feedbackEndpoint: String
        get() = feedbackUrl.ifEmpty { BuildConfig.FEEDBACK_URL }

    /**
     * 是否加入「体验优化计划」—— 也就是每次播放结束自动发一条性能打点
     * （[PlayStats] → `/api/v1/play`）。
     *
     * **默认关**，必须用户自己去设置里打开。理由：
     *
     * - 这条链路**不弹框、不确认**，每次退出播放器就发一次。
     *   默认开的话等于「用户从没同意过，但一直在发」，这个形态不能用 ——
     *   哪怕发的内容只有首帧毫秒数和掉帧数。
     * - 手动反馈（[DeviceReport]）是用户**主动点的**，那是另一条链路，
     *   不受这个开关影响。
     *
     * ⚠️ 关的时候不能只在 [PlayStats.finish] 里 return：`begin()` 仍在被调用、
     * `startedAt` 一直被刷新，用户在很久以后打开开关，第一次 `finish()`
     * 会把那次陈年播放发出去。所以两处都要拦（见 PlayStats）。
     */
    var playStatsEnabled: Boolean
        get() = sp.getBoolean(KEY_PLAY_STATS, false)
        set(v) = sp.edit().putBoolean(KEY_PLAY_STATS, v).apply()

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
        private const val KEY_FEEDBACK_URL = "feedback_url"
        private const val KEY_PLAY_STATS = "play_stats_enabled"

        /**
         * 默认的版本清单地址（GitHub Release 固定资产）。
         *
         * 保留这个常量只是为了兼容旧引用；实际默认值在 [UpdateManager.DEFAULT_MANIFEST]，
         * 由打包时的 `-PupdateRepo=owner/repo` 决定。
         */
        @Deprecated("改用 UpdateManager.DEFAULT_MANIFEST", ReplaceWith("UpdateManager.DEFAULT_MANIFEST"))
        const val DEFAULT_UPDATE_MANIFEST = "https://github.com/OWNER/REPO/releases/latest/download/version.json"

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
