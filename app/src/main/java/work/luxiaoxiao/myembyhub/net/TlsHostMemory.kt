package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.util.Log

/**
 * 按**域名**记住「哪条 TLS 栈跟它谈得拢」。
 *
 * ## 为什么需要这一层
 *
 * [TlsStack] 的实现选择原来是全局的一个开关（自动 / Conscrypt / 系统 JSSE）。
 * 可现实是**同一个 App 同时连好几个域名**：Emby 服务器、反馈服务器、更新源，
 * 它们跑在不同的机器、不同的 nginx 配置上，能吃哪套密码套件各不相同。
 * 一个全局开关必然对其中某几个是错的。
 *
 * 记下「哪个域名用哪条栈」之后，选择就从「猜」变成「已知」：
 *
 * - 某个域名第一次连，按能力判断挑一条；
 * - 真握手失败了，换另一条重试一次，成功就把结论写下来；
 * - 以后连这个域名直接照记录走，不再付失败握手那一下。
 *
 * ## 为什么用 SharedPreferences 而不是内存表
 *
 * 4.3 的电视上进程被 `ActivityManager-Killer` 收掉是常态（内存只有 1.5GB）。
 * 记录只放在内存里，用户每次冷启动都要重新踩一遍坑 —— 那等于没记。
 *
 * ## 为什么容量有上限
 *
 * 用户可能换服务器、试好几个站点。留最近 [MAX_HOSTS] 个够了，
 * 再多的记录对排查也没有价值，反而让这个字符串无限长。
 * 超出时按写入顺序淘汰最早的（[LinkedHashMap] 保序）。
 */
internal object TlsHostMemory {

    private const val TAG = "TlsHostMemory"
    private const val PREFS = "myembyhub_tls_hosts"
    private const val KEY_MAP = "map"

    /** 最多记这么多个域名。 */
    internal const val MAX_HOSTS = 24

    /** 记录分隔符。域名里不可能出现，安全。 */
    private const val PAIR_SEP = ";"
    private const val KV_SEP = "="

    // ==================== 纯函数（可单测，不碰 Context） ====================

    /** 把记录表编码成一行字符串。 */
    internal fun encode(map: Map<String, String>): String =
        map.entries.joinToString(PAIR_SEP) { "${it.key}$KV_SEP${it.value}" }

    /** 解码成保序表；坏片段直接跳过，不让一条脏数据毁掉整张表。 */
    internal fun decode(raw: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isNullOrEmpty()) return out
        for (piece in raw.split(PAIR_SEP)) {
            if (piece.isEmpty()) continue
            val i = piece.indexOf(KV_SEP)
            if (i <= 0 || i == piece.length - 1) continue
            val host = piece.substring(0, i)
            val impl = piece.substring(i + 1)
            if (host.isEmpty()) continue
            out[host] = impl
        }
        return out
    }

    /** 写入时统一小写、去掉端口和空白 —— `EB.atrct.cn:443` 和 `eb.atrct.cn` 是同一个。 */
    internal fun normalize(host: String): String =
        host.trim().lowercase().substringBefore(':').trim()

    // ==================== 读写 ====================

    private fun load(ctx: Context): LinkedHashMap<String, String> {
        val raw = try {
            prefs(ctx).getString(KEY_MAP, null)
        } catch (e: Throwable) {
            null
        }
        return decode(raw)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 查这个域名记的是哪条栈；没记过返回 null（调用方自己按能力挑）。 */
    fun recall(ctx: Context, host: String): TlsStack.TlsImpl? {
        val key = normalize(host)
        if (key.isEmpty()) return null
        val v = load(ctx)[key] ?: return null
        return when (v) {
            "conscrypt" -> TlsStack.TlsImpl.CONSCRYPT
            "system" -> TlsStack.TlsImpl.SYSTEM
            // 认不出来的值（降级/手改）当没记过，别让它把连接带死
            else -> null
        }
    }

    /** 记下「这个域名用这条栈能通」。 */
    fun remember(ctx: Context, host: String, impl: TlsStack.TlsImpl) {
        val key = normalize(host)
        if (key.isEmpty()) return
        if (impl == TlsStack.TlsImpl.AUTO) return
        val map = load(ctx)
        // 重新插入以刷新淘汰顺序
        map.remove(key)
        map[key] = if (impl == TlsStack.TlsImpl.CONSCRYPT) "conscrypt" else "system"
        while (map.size > MAX_HOSTS) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
        try {
            prefs(ctx).edit().putString(KEY_MAP, encode(map)).commit()
            Log.i(TAG, "记住 $key → $impl（共 ${map.size} 条）")
        } catch (ignored: Throwable) {
        }
    }

    /** 忘掉单个域名的记录（设置页「忘记已记录」用）。 */
    fun forget(ctx: Context, host: String) {
        val key = normalize(host)
        if (key.isEmpty()) return
        val map = load(ctx)
        if (map.remove(key) == null) return
        try {
            prefs(ctx).edit().putString(KEY_MAP, encode(map)).commit()
        } catch (ignored: Throwable) {
        }
    }

    /** 全部清掉。 */
    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove(KEY_MAP).commit()
        } catch (ignored: Throwable) {
        }
    }

    /** 给设置页/反馈报告用：当前记了哪些域名。 */
    fun snapshot(ctx: Context): List<Pair<String, TlsStack.TlsImpl>> =
        load(ctx).mapNotNull { (h, v) ->
            when (v) {
                "conscrypt" -> h to TlsStack.TlsImpl.CONSCRYPT
                "system" -> h to TlsStack.TlsImpl.SYSTEM
                else -> null
            }
        }
}
