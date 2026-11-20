package work.luxiaoxiao.myembyhub.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放器切集用的「剧集引用」。
 *
 * ## 为什么不直接把整个 [MediaItem] 传进播放器
 *
 * `MediaItem` 有 20 多个字段（简介、评分、类型标签、海报 id…），
 * 一集能序列化出几百字节，一部 300 集的剧就是几十 KB 塞进 Intent ——
 * 而播放器切集真正需要的只有「id / 名字 / 集号 / 播放源 / 看到哪儿了」五项。
 *
 * 这里只留这五项，300 集大约 20KB，4.3 的 Binder 事务（上限约 500KB）装得下，
 * 而且单测能直接覆盖编解码（见 `EpisodeRefTest`）。
 *
 * ## 为什么「看到哪儿了」要跟着走
 *
 * 用户从第 8 集切到第 9 集，如果第 9 集他之前看过一半，就应该从一半接着播 ——
 * 这是 Emby 自己的语义（`UserData.PlaybackPositionTicks`）。丢了这个字段，
 * 切集就会变成「每次都从头」，比不能切还烦。
 *
 * @param resumeMs 上次看到的位置（毫秒），0 = 没看过
 * @param runtimeMs 总时长（毫秒），0 = 服务器没给（只影响「第 3 集 / 共 40 集」这类展示）
 */
data class EpisodeRef(
    val id: String,
    val name: String,
    val indexNumber: Int = 0,
    val parentIndexNumber: Int = 0,
    val mediaSourceId: String = "",
    val resumeMs: Long = 0L,
    val runtimeMs: Long = 0L
) {
    /**
     * 播放器顶栏显示的名字。
     *
     * 和 [MediaItem.displayTitle] 保持同一套规则：有集号就补上，避免同一季里
     * 「第 30 集」和「谢画楼跪求…」混排时看不出这是第几集。
     */
    fun label(): String {
        if (indexNumber <= 0) return name
        val base = "第 $indexNumber 集"
        return if (name.isEmpty() || name == base) base else "$base · $name"
    }

    companion object {
        /** 从列表接口拿到的一条条目转过来。 */
        fun of(item: MediaItem): EpisodeRef = EpisodeRef(
            id = item.id,
            name = item.name,
            indexNumber = item.indexNumber,
            parentIndexNumber = item.parentIndexNumber,
            mediaSourceId = item.mediaSourceId,
            resumeMs = item.positionTicks / 10_000L,
            runtimeMs = item.runtimeTicks / 10_000L
        )

        /**
         * 序列化成 JSON 数组（塞进 Intent extra）。
         *
         * 用短键名不是为了省那几个字节，而是让这份字符串在 `adb shell am start`
         * 手工构造 intent 时还能读得动 —— 真机排查经常要手敲。
         */
        fun encode(list: List<EpisodeRef>): String {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject().apply {
                        put("i", e.id)
                        put("n", e.name)
                        put("x", e.indexNumber)
                        put("p", e.parentIndexNumber)
                        put("m", e.mediaSourceId)
                        put("r", e.resumeMs)
                        put("t", e.runtimeMs)
                    }
                )
            }
            return arr.toString()
        }

        /**
         * 反序列化。
         *
         * **容错优先**：任何一条坏数据只丢那一条，整体解析失败返回空表 ——
         * 播放页拿到空表就是「没有剧集信息，切集功能静默关闭」，
         * 绝不会因为一段脏 JSON 让用户连正片都看不了。
         */
        fun decode(raw: String?): List<EpisodeRef> {
            if (raw.isNullOrEmpty()) return emptyList()
            val arr = try {
                JSONArray(raw)
            } catch (e: Throwable) {
                return emptyList()
            }
            val out = ArrayList<EpisodeRef>(arr.length())
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val id = o.optString("i")
                if (id.isEmpty()) continue
                out.add(
                    EpisodeRef(
                        id = id,
                        name = o.optString("n"),
                        indexNumber = o.optInt("x"),
                        parentIndexNumber = o.optInt("p"),
                        mediaSourceId = o.optString("m"),
                        resumeMs = o.optLong("r").coerceAtLeast(0L),
                        runtimeMs = o.optLong("t").coerceAtLeast(0L)
                    )
                )
            }
            return out
        }
    }
}
