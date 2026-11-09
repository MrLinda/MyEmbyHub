package com.example.tvcheck

import java.util.Locale

/**
 * 手写 URL 解析 / 重定向解析。
 *
 * ## 为什么要自己写
 *
 * 2026-11-03 真机踩过的坑：
 * - `java.net.URL(URL context, String spec)`：Android 4.3 的 libcore 处理绝对 URL 时
 *   会把 context 的 query 也留着，拼成 `https://v10.example.com/Videos/1/stream?a=b?a=b`
 *   → 服务器 404。
 * - `android.net.Uri`：**根本没有 `resolve` 方法**。
 * - `java.net.URI.resolve`：各版本行为有差异，遇中文/空格路径会抛异常。
 *
 * 所以这里纯手工切分，行为在所有 Android 版本上一致，且有单元测试兜底。
 */
internal object UrlKit {

    data class Parts(
        val scheme: String,
        val host: String,
        val port: Int,
        /** 路径，**一定以 `/` 开头**。 */
        val path: String,
        /** query 字符串（不含 `?`），没有则 null。 */
        val query: String?
    )

    /** 解析 URL。 */
    fun parse(urlStr: String): Parts {
        val scheme = urlStr.substringBefore("://", missingDelimiterValue = "https").lowercase(Locale.US)
        val rest = urlStr.substringAfter("://", missingDelimiterValue = urlStr)
        val hostPort = rest.substringBefore('/')
        // ⚠️ substringAfter 会把分隔符本身吃掉，路径必须自己补回前导斜杠，
        //    否则请求行变成 `GET Videos/666/stream` → openresty 回 400 Bad Request。
        val pathQuery = if (rest.contains('/')) "/" + rest.substringAfter('/') else "/"
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
            ?: if (scheme == "http") 80 else 443
        val qIdx = pathQuery.indexOf('?')
        val rawPath = if (qIdx >= 0) pathQuery.substring(0, qIdx) else pathQuery
        val rawQuery = if (qIdx >= 0) pathQuery.substring(qIdx + 1) else ""
        return Parts(
            scheme = scheme,
            host = host,
            port = port,
            path = if (rawPath.isEmpty()) "/" else rawPath,
            query = if (rawQuery.isEmpty()) null else rawQuery
        )
    }

    /**
     * 把 `Location` 头解析成绝对 URL。
     *
     * - 已经是绝对 URL → 原样返回（**不能拿 base 再拼一遍**，否则 query 会重复）
     * - `/path` 形式 → origin + path
     * - `path` 形式 → origin + base 的目录 + path
     */
    fun resolve(base: String, location: String): String {
        val loc = location.trim()
        if (loc.startsWith("http://", true) || loc.startsWith("https://", true)) return loc

        val m = Regex("^(https?://[^/?#]+)([^?#]*)(\\?.*)?$", RegexOption.IGNORE_CASE).find(base)
            ?: return loc
        val origin = m.groupValues[1]
        if (loc.startsWith("/")) return origin + loc

        val basePath = m.groupValues[2]
        val dir = if (basePath.endsWith('/')) basePath else basePath.substringBeforeLast('/', "") + "/"
        return origin + dir + loc
    }
}
