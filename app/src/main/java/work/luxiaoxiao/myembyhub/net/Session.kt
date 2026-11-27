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

    /**
     * 每台设备唯一的 Emby DeviceId（首次读取时生成并持久化）。
     *
     * Emby 服务端按 DeviceId 区分设备会话；固定常量会导致多台设备互相顶号、串进度。
     */
    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, "")
            if (!existing.isNullOrEmpty()) return existing
            val created = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    /** 记住密码，方便下次直接登录（4.3 电视没有软键盘，输入成本很高）。 */
    var password: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_PASS, v).apply()

    /** 是否记住密码（默认开；关掉时会清掉已保存密码）。 */
    var rememberPassword: Boolean
        get() = sp.getBoolean(KEY_REMEMBER_PASS, true)
        set(v) = sp.edit().putBoolean(KEY_REMEMBER_PASS, v).apply()

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

    /**
     * 播完自动播下一集。
     *
     * **默认开** —— 看剧的时候「一集播完自动接下一集」是绝大多数人的预期，
     * 关着的话用户每集都得摸一次遥控器，那是把播放器当手动翻页器用。
     *
     * 只在**自然播到结尾**时触发（`STATE_ENDED`）：中途按返回、切集、
     * 播放失败都不算，所以不会出现「我想退出却被塞了下一集」。
     *
     * 边界：已经是最后一集就正常结束，不会绕回第一集 —— 那会像卡在循环里。
     */
    var autoNextEpisode: Boolean
        get() = sp.getBoolean(KEY_AUTO_NEXT, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_NEXT, v).apply()

    /**
     * 播放时是否显示字幕。
     *
     * **默认关**。原因不是「省性能」，是**会重叠**：很多资源是压好的硬字幕
     * （字幕直接烧在画面里），此时再把内嵌字幕轨渲染上来，屏幕上就是上下两行字，
     * 而且内容常常还不一样（一条是压制组翻的，一条是原盘的）。
     *
     * 想看的用户在播放页菜单里打开即可（菜单里会列出这部片子实际有哪些字幕轨）。
     * 这不是「全局偏好」而更接近「临时开关」—— 换一部片子到底要不要字幕，
     * 取决于这部片子有没有硬字幕，所以设置页里不放它，只放播放页。
     */
    var subtitleEnabled: Boolean
        get() = sp.getBoolean(KEY_SUBTITLE, false)
        set(v) = sp.edit().putBoolean(KEY_SUBTITLE, v).apply()

    /**
     * 上次选中的字幕轨（`MediaTrackGroup` 里的下标）。
     *
     * -1 表示「没选过，交给 [com.google.android.exoplayer2.trackselection.DefaultTrackSelector]
     * 自己挑第一条」。切集时轨道结构可能变（不同集的内嵌字幕条数不一定相同），
     * 越界就当作没选过。
     */
    var subtitleTrackIndex: Int
        get() = sp.getInt(KEY_SUBTITLE_TRACK, -1)
        set(v) = sp.edit().putInt(KEY_SUBTITLE_TRACK, v).apply()

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
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PASS = "password"
        private const val KEY_REMEMBER_PASS = "remember_password"
        private const val KEY_UPDATE_URL = "update_manifest_url"
        private const val KEY_FEEDBACK_URL = "feedback_url"
        private const val KEY_PLAY_STATS = "play_stats_enabled"
        private const val KEY_AUTO_NEXT = "auto_next_episode"
        private const val KEY_SUBTITLE = "subtitle_enabled"
        private const val KEY_SUBTITLE_TRACK = "subtitle_track_index"

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
