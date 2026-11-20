package work.luxiaoxiao.myembyhub.net

/**
 * 上报前的脱敏。
 *
 * 单独拎出来是因为它是**纯函数**、正确性要求明确、值得单测 ——
 * 埋在 `DeviceReport` 那 800 多行里既不好测也不好改。
 *
 * ## 为什么只动这三样
 *
 * SSID + 局域网 IP + DNS 三样合起来，基本能把「这是谁家的网」定位到小区；
 * 而它们对排查「播不出来 / 连不上」的贡献只有「连的哪个 AP、信号多强、是不是内网」。
 * 保留判断力、去掉定位能力，是这里唯一的标准。
 *
 * ## 刻意不动的东西
 *
 * - `device.fingerprint` / `buildTags`：同型号同固件的机器完全一样，**不是唯一标识**，
 *   却是「这台到底刷的什么固件」的直接证据；
 * - `tls.handshakes[].host`：就是 Emby 服务器地址本身，不带上等于没报；
 * - `player.lastItemTitle`：用户点反馈就是要说这部片，抹了没法查。
 */
internal object PiiMask {

    /**
     * SSID 只留前两个字符：「CMCC-」和「TP-LINK_」能分辨出连的是哪个 AP，
     * 但认不出是谁家的网。星号数量也收窄（最多 6 个），
     * 别让补出来的长度本身泄露原名有多长。
     */
    fun ssid(raw: String): String {
        val s = raw.trim().trim('"')
        // 拿不到权限时系统回的就是 `<unknown ssid>` 这个字面量，别把它当网络名打码上报
        if (s.isEmpty() || s.contains("unknown", ignoreCase = true)) return ""
        if (s.length <= 2) return "**"
        val stars = (s.length - 2).coerceIn(1, 6)
        return s.take(2) + "*".repeat(stars)
    }

    /**
     * 局域网 IP 只留前两段：够判断「192.168.x 内网 / 10.x 大内网 / 和服务器同不同网段」，
     * 但定位不到具体哪台机器。
     */
    fun ip(raw: String): String {
        if (raw.isEmpty()) return ""
        val p = raw.split('.')
        return if (p.size == 4) "${p[0]}.${p[1]}.x.x" else "x.x.x.x"
    }

    /**
     * DNS 不报地址，只报**形状**。
     *
     * 这里唯一有诊断价值的判断是「电视上是不是被塞了个 fake-IP 代理」（198.18/198.19 段）——
     * AGENTS.md 里记着的那个坑：Clash fake-IP 会把域名解析成 198.18.0.x，
     * 于是所有 TLS 握手测的都是代理出口，跟电视真正的连通性无关。
     * 这个判断不需要原始 IP。
     */
    fun dns(raw: String): String = when {
        raw.isEmpty() -> ""
        raw.startsWith("198.18.") || raw.startsWith("198.19.") -> "fake-ip 段（可能挂了代理）"
        else -> "已配置（非 fake-ip 段）"
    }
}
