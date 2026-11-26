package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import javax.net.ssl.HttpsURLConnection

/**
 * Emby REST API 客户端。
 *
 * ## 为什么业务接口可以用 `HttpURLConnection`
 *
 * 播放链路必须自定义 `DataSource`（`DefaultHttpDataSource` 不接受外部 socket factory），
 * 但**业务接口**（登录、列库、列条目）用 `HttpURLConnection` + 自定义 factory 就够了，
 * 真机实测 HTTP 200。
 *
 * ## 两个必须记住的坑
 *
 * 1. **401 且没有 `WWW-Authenticate` 头** → Android 在 `getResponseCode()` 就抛
 *    `IOException: No authentication challenges found`，响应体读不到。
 *    Emby 正好不发这个头 → 必须手工兜住，把异常还原成 401，
 *    否则「密码错」会被误报成「网络失败」。
 * 2. **URL 要自己拼**。这台电视的 Android 4.3 上 `java.net.URL(URL, String)`
 *    会把 base 的 query 重复拼到绝对 URL 后面 → 404。
 *    所以这里不用 `URL(URL, String)`，只用单参数构造 + 字符串拼接。
 */
class EmbyApi(
    private val ctx: Context,
    var server: String,
    var token: String = "",
    /**
     * 当前登录用户的 id。
     *
     * ⚠️ **必须用登录时服务端返回的真实 id，不能用固定值。**
     * 早先这里写的是全 0 的假 id（`00000000000000000000000000000000`），
     * `/Users/{id}/Views` 碰巧还能返回库列表，但 `/Users/{id}/Items/Resume`
     * 直接返回 0 条 —— 「最近观看」永远是空的。
     * 实测同一账号：假 id → Resume 0 条，真实 id → 2 条。
     *
     * 也别改用 `/Users/Me/...`：这台服务器的 `Views` 支持 `Me`，
     * 但 `Items/Resume` 用 `Me` 同样返回 0 条。
     */
    var userId: String = ""
) {

    companion object {
        private const val TAG = "EmbyApi"
        private const val CLIENT_NAME = "MyEmbyHub"
        private const val CLIENT_VERSION = "1.0.0"
        private const val DEVICE_ID = "myembyhub-tv"

        /**
         * 兜底用户 id，仅在 [userId] 为空时使用（老版本升级上来的会话）。
         * 用它会拿不到「最近观看」，但至少列表能显示。
         */
        const val FALLBACK_USER_ID = "00000000000000000000000000000000"
    }

    /** 实际使用的用户 id：优先真实 id，取不到才退回兜底值。 */
    private fun uid(): String = userId.ifEmpty { FALLBACK_USER_ID }

    /** 统一的鉴权头。 */
    private fun authHeader(): String =
        "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"TV\", DeviceId=\"$DEVICE_ID\", Version=\"$CLIENT_VERSION\""

    /** 规范化服务器地址：去掉末尾斜杠，补上 https://。 */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        while (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /**
     * 账号密码登录。
     *
     * @return 成功返回 AccessToken；失败返回错误说明。
     */
    fun login(username: String, password: String): Result<String> {
        val base = normalize(server)
        if (base.isEmpty()) return Result.failure(IllegalArgumentException("服务器地址为空"))

        val body = org.json.JSONObject().apply {
            put("Username", username)
            put("Pw", password)
        }.toString()

        val url = "$base/Users/AuthenticateByName"
        return try {
            withTls(url) { factory ->
                val conn = open(url, "POST", factory)
                try {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    logTls(conn)
                    if (code == 200) {
                        val text = conn.inputStream.use { readAll(it) }
                        val obj = org.json.JSONObject(text)
                        val t = obj.optString("AccessToken")
                        if (t.isEmpty()) {
                            Result.failure(IllegalStateException("登录成功但没拿到 AccessToken"))
                        } else {
                            server = base
                            token = t
                            // 顺带取出真实用户 id：后面的 Resume / NextUp 都要用它
                            userId = obj.optJSONObject("User")?.optString("Id").orEmpty()
                            Result.success(t)
                        }
                    } else {
                        val err = readError(conn)
                        try { conn.disconnect() } catch (_: Throwable) {}
                        Result.failure(IllegalStateException("登录失败 HTTP $code$err"))
                    }
                } catch (e: IOException) {
                    try { conn.disconnect() } catch (_: Throwable) {}
                    if (e.message?.contains("No authentication challenges") == true) {
                        Result.failure(IllegalStateException("登录失败 HTTP 401", e))
                    } else {
                        Result.failure(e)
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** 服务器信息（含版本号），用于连上后展示。 */
    fun systemInfo(): Result<String> {
        return try {
            val resp = get("/System/Info/Public")
            val obj = org.json.JSONObject(resp.body)
            val name = obj.optString("ServerName")
            val ver = obj.optString("Version")
            Result.success("$name $ver")
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** 媒体库（顶层 UserView 列表）。 */
    fun libraries(): Result<List<MediaItem>> {
        return try {
            val resp = get("/Users/${uid()}/Views?Fields=Id,Name")
            val arr = org.json.JSONObject(resp.body).optJSONArray("Items") ?: org.json.JSONArray()
            val out = ArrayList<MediaItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    MediaItem(
                        id = o.optString("Id"),
                        name = o.optString("Name"),
                        type = o.optString("Type"),
                        isFolder = o.optBoolean("IsFolder", true)
                    )
                )
            }
            Result.success(out)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 列表接口要的字段（**不含 `Overview`、也不含 `MediaSources`**）。
     *
     * `Overview`（简介）动辄几百字到几 KB，只有详情弹窗用得到，走 [itemDetail] 单独取。
     *
     * ⚠️ **`MediaSources` 也要从大列表里去掉**（2026-11-19）：
     * 它不是「一个 id」，而是整个媒体源对象 —— 里面塞着 `MediaStreams` 数组
     * （每条流十几个字段：编解码、分辨率、码率、语言、色彩空间……）、文件路径、
     * 容器信息。一条片子光这一坨就有 1~3KB，海报墙一页 60 条就是几十上百 KB 的 JSON，
     * 在 1GHz 的 MStar 上光解析就要好几百毫秒 —— 用户感受就是「翻页要顿一下」。
     *
     * 列表页其实**用不到**它：卡片只显示名字/年份/进度（`UserData`），
     * 播放前各页都有「`mediaSourceId` 为空就补一次 [itemDetail]」的兜底
     * （见 `HomeActivity.play` / `DetailActivity.startPlayback`）。
     *
     * 只有**详情页的剧集列表**（[episodes]）例外，见 [PLAYABLE_LIST_FIELDS]。
     */
    private val LIST_FIELDS =
        "Id,Name,Type,MediaType,IsFolder,Container,Size,ProductionYear,IndexNumber," +
            "ParentIndexNumber,SeriesId,SeriesName,RunTimeTicks,UserData"

    /**
     * 剧集列表用：多带一个 `MediaSources`。
     *
     * 详情页的「选集」是**点了就要立刻播**的场景，多一次往返就是多几百毫秒的黑屏，
     * 而这批数据本身只有几十条（一季十几集），体积可以接受。
     */
    private val PLAYABLE_LIST_FIELDS = "$LIST_FIELDS,MediaSources"

    /**
     * 详情用：列表字段 + `Overview` + 分级/评分/类型。
     *
     * 后三个只在详情页显示，列表里用不上 —— 列表页一页几十上百条，
     * 每条都拖着 `Genres` 数组会让 JSON 明显变大（这台电视解析不起）。
     */
    private val DETAIL_FIELDS =
        "$PLAYABLE_LIST_FIELDS,Overview,OfficialRating,CommunityRating,Genres"

    /** 把 `{Items:[...]}` 解析成 [MediaItem] 列表。 */
    private fun parseItems(body: String, skipFolders: Boolean = false): List<MediaItem> {
        val arr = org.json.JSONObject(body).optJSONArray("Items") ?: return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (skipFolders && o.optBoolean("IsFolder", false)) continue
            out.add(parseItem(o))
        }
        return out
    }

    /** 单个条目的 JSON 对象 → [MediaItem]。 */
    private fun parseItem(o: org.json.JSONObject): MediaItem {
        val ms = o.optJSONArray("MediaSources")
        val src = if (ms != null && ms.length() > 0) ms.getJSONObject(0) else null
        var codec = ""
        var width = 0
        var height = 0
        if (src != null) {
            val streams = src.optJSONArray("MediaStreams")
            if (streams != null) {
                for (s in 0 until streams.length()) {
                    val st = streams.getJSONObject(s)
                    if (st.optString("Type") == "Video") {
                        codec = st.optString("Codec")
                        width = st.optInt("Width")
                        height = st.optInt("Height")
                        break
                    }
                }
            }
        }
        val ud = o.optJSONObject("UserData")
        // 类型标签在 Emby 里是数组（["科幻","悬疑"]），拼成一行直接给 TextView 用。
        // 分隔符用全角斜杠而不是逗号 —— 中文片名里逗号太常见，会跟标题混在一起。
        val genresArr = o.optJSONArray("Genres")
        val genres = if (genresArr == null) "" else buildString {
            for (i in 0 until genresArr.length()) {
                if (isNotEmpty()) append(" / ")
                append(genresArr.optString(i))
            }
        }
        return MediaItem(
            id = o.optString("Id"),
            name = o.optString("Name"),
            type = o.optString("Type"),
            isFolder = o.optBoolean("IsFolder", false),
            container = o.optString("Container"),
            size = o.optLong("Size"),
            codec = codec,
            width = width,
            height = height,
            seriesName = o.optString("SeriesName"),
            seriesId = o.optString("SeriesId"),
            overview = o.optString("Overview"),
            mediaSourceId = src?.optString("Id") ?: "",
            indexNumber = o.optInt("IndexNumber", 0),
            parentIndexNumber = o.optInt("ParentIndexNumber", 0),
            runtimeTicks = o.optLong("RunTimeTicks", 0),
            played = ud?.optBoolean("Played", false) ?: false,
            isFavorite = ud?.optBoolean("IsFavorite", false) ?: false,
            positionTicks = ud?.optLong("PlaybackPositionTicks", 0) ?: 0,
            productionYear = o.optInt("ProductionYear", 0),
            officialRating = o.optString("OfficialRating"),
            communityRating = o.optDouble("CommunityRating", 0.0),
            genres = genres
        )
    }

    /**
     * 单个条目的完整信息（**带简介**）。
     *
     * 详情弹窗要用简介时单独取一次，而不是让每个列表页都拖着 `Overview`。
     */
    fun itemDetail(itemId: String): Result<MediaItem?> {
        return try {
            val resp = get("/Users/${uid()}/Items/$itemId?Fields=$DETAIL_FIELDS")
            Result.success(parseItem(org.json.JSONObject(resp.body)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 某个媒体库下的条目。
     *
     * @param recursive 是否递归展开所有子级。
     *        **做分层下钻时必须传 false** —— 递归会把「剧 → 季 → 集」全糊在一层，
     *        电视上没法用。
     * @param includeVideo 是否只要视频
     * @param sortBy 排序字段，见 [SORT_OPTIONS]
     * @param descending 是否降序
     */
    fun items(
        parentId: String,
        recursive: Boolean = false,
        includeVideo: Boolean = true,
        limit: Int = 200,
        sortBy: String = SortOption.SORT_NAME,
        descending: Boolean = false,
        includeItemTypes: String = "",
        startIndex: Int = 0
    ): Result<List<MediaItem>> {
        return try {
            // ⚠️ **不要加 `MediaTypes=Video`**：
            //    剧（Series）和季（Season）的 MediaType 不是 Video，
            //    加了这个过滤会把「剧 → 季 → 集」的分层结构整层抹掉 →
            //    剧集库里第一层就空了（动画电影库因为直接是 Movie 所以看着正常）。
            //    `IncludeMedia=Video` 只影响「是否附带媒体信息」，不影响条目类型，可以留。
            val filter = if (includeVideo) "&IncludeMedia=Video" else ""
            // ⚠️ 递归列库时**必须**带 `IncludeItemTypes`：
            //    这个 Emby 库里每个影片外面还套了一层同名文件夹
            //    （「恐怖X档案 (2022)」folder 里面才是 Movie「恐怖X档案」），
            //    不带类型过滤就会拿回一半「文件夹」，海报墙上出现两套同名卡片。
            val types = if (includeItemTypes.isNotEmpty()) "&IncludeItemTypes=$includeItemTypes" else ""
            val order = if (descending) "Descending" else "Ascending"
            // StartIndex 用于海报墙翻页（Emby 默认从 0 开始）
            val start = if (startIndex > 0) "&StartIndex=$startIndex" else ""
            val resp = get(
                "/Users/${uid()}/Items?ParentId=$parentId&Recursive=$recursive$filter$types&Limit=$limit" +
                    "&Fields=$LIST_FIELDS&SortBy=$sortBy&SortOrder=$order$start"
            )
            Result.success(parseItems(resp.body))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 搜索。
     *
     * Emby 的 `/Items?SearchTerm=` 支持模糊匹配（部分标题、演员名也能命中）。
     */
    fun search(term: String, limit: Int = 40): Result<List<MediaItem>> {
        return try {
            val t = term.trim()
            if (t.isEmpty()) return Result.success(emptyList())
            val resp = get(
                "/Users/${uid()}/Items?SearchTerm=${java.net.URLEncoder.encode(t, "UTF-8")}" +
                    "&Recursive=true&IncludeMedia=Video&Limit=$limit&Fields=$LIST_FIELDS&SortBy=SortName"
            )
            Result.success(parseItems(resp.body, skipFolders = true))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 「继续观看」列表（Emby 的 `/Items/Resume`）。
     *
     * 返回的条目带 `UserData.PlaybackPositionTicks`，直接拿来续播。
     */
    fun resume(limit: Int = 30): Result<List<MediaItem>> {
        return try {
            val resp = get(
                "/Users/${uid()}/Items/Resume?MediaTypes=Video&Limit=$limit&Fields=$PLAYABLE_LIST_FIELDS"
            )
            Result.success(parseItems(resp.body))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 一部剧的全部剧集（详情页「选集」用）。
     *
     * 排序必须写 `ParentIndexNumber,IndexNumber`：
     * 只按 `IndexNumber` 排的话，第 2 季第 1 集会排到第 1 季第 5 集前面 ——
     * Emby 里 `IndexNumber` 只是「季内集号」，跨季会重复。
     */
    fun episodes(seriesId: String, limit: Int = 300): Result<List<MediaItem>> {
        return try {
            val resp = get(
                "/Shows/$seriesId/Episodes?UserId=${uid()}&Limit=$limit" +
                    "&SortBy=ParentIndexNumber,IndexNumber&Fields=$PLAYABLE_LIST_FIELDS"
            )
            Result.success(parseItems(resp.body))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 这个剧「接下来该看」的那一集。
     *
     * Emby 的 `Shows/NextUp` 已经处理了「没看完的那集优先」——
     * 看过一半的集会先返回，全看完则返回下一集，没看过返回第一集。
     */
    fun nextUp(seriesId: String): Result<MediaItem?> {
        return try {
            val resp = get(
                "/Shows/NextUp?SeriesId=$seriesId&UserId=${uid()}&Limit=1&Fields=$PLAYABLE_LIST_FIELDS"
            )
            Result.success(parseItems(resp.body).firstOrNull())
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** 兜底：这个剧的第一集（`NextUp` 返回空时用）。 */
    fun firstEpisode(seriesId: String): Result<MediaItem?> {
        return try {
            val resp = get(
                "/Shows/$seriesId/Episodes?UserId=${uid()}&Limit=1&SortBy=IndexNumber&Fields=$PLAYABLE_LIST_FIELDS"
            )
            Result.success(parseItems(resp.body).firstOrNull())
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 拼取流地址。
     *
     * ⚠️ `static=true` 是**直放原文件**（不转码），配合自写 DataSource 播 mkv/mp4。
     * 转码地址（`/Videos/{id}/master.m3u8`）需要服务端起 ffmpeg，4K 这类片源才用。
     *
     * ⚠️ **`mediaSourceId` 是必需的，不是「带上更稳」**（2026-11-05 实测更正）：
     * 少了它，`tv.example.com` 照样 302 到 `v10.example.com`，但 v10 直接回
     * `HTTP 404 Not Found`；补上就 200。所以调用方必须先保证这个值非空
     * （见 `HomeActivity.play()` 里的补取逻辑）。
     *
     * @param mediaSourceId 覆盖条目自带的 mediaSourceId（补取到的值走这里传）。
     */
    fun streamUrl(item: MediaItem, mediaSourceId: String = item.mediaSourceId): String {
        return buildFallbackStreamUrl(normalize(server), item.id, mediaSourceId)
    }

    /**
     * 标准 Emby/Jellyfin 播放信息协商：`POST /Items/{id}/PlaybackInfo`。
     *
     * 部分服务端（如 smartstrm / 302 反代）依赖客户端先发 PlaybackInfo 才会返回真实的
     * `DirectStreamUrl`（例如 `/smartstrm?item_id=...`）；直接硬拼 `/Videos/{id}/stream`
     * 可能绕开分流逻辑甚至被重定向到错误文件。
     */
    fun playbackInfo(itemId: String, mediaSourceId: String = ""): Result<PlaybackPlan> {
        val base = normalize(server)
        if (base.isEmpty()) return Result.failure(IllegalArgumentException("服务器地址为空"))
        return try {
            val profile = org.json.JSONObject().apply {
                put("MaxStreamingBitrate", 120_000_000)
                put(
                    "DirectPlayProfiles",
                    org.json.JSONArray().apply {
                        put(org.json.JSONObject().put("Type", "Video"))
                        put(org.json.JSONObject().put("Type", "Audio"))
                    }
                )
            }
            val payload = org.json.JSONObject().apply {
                put("UserId", uid())
                put("IsPlayback", true)
                put("AutoOpenLiveStream", false)
                if (mediaSourceId.isNotEmpty()) put("MediaSourceId", mediaSourceId)
                put("DeviceProfile", profile)
            }.toString()
            val resp = post("/Items/$itemId/PlaybackInfo?UserId=${uid()}", payload)
            val plan = parsePlaybackPlan(base, itemId, mediaSourceId, resp.body)
                ?: return Result.failure(IllegalStateException("PlaybackInfo 未返回可用的媒体源"))
            Result.success(plan)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 解析最终播放地址：优先走标准 `PlaybackInfo`，若服务端不支持或没给媒体源再回退到旧直连地址。
     */
    fun resolvePlayback(item: MediaItem, mediaSourceId: String = item.mediaSourceId): Result<PlaybackPlan> {
        val infoRes = playbackInfo(item.id, mediaSourceId)
        infoRes.getOrNull()?.let { return Result.success(it) }
        val infoErr = infoRes.exceptionOrNull()
        if (isAuthExpired(infoErr)) return Result.failure(infoErr!!)

        var msId = mediaSourceId
        if (msId.isEmpty()) {
            val detailRes = itemDetail(item.id)
            val detailErr = detailRes.exceptionOrNull()
            if (isAuthExpired(detailErr)) return Result.failure(detailErr!!)
            msId = detailRes.getOrNull()?.mediaSourceId.orEmpty()
        }
        if (msId.isEmpty()) {
            return Result.failure(infoErr ?: IllegalStateException("没有可播放的媒体源"))
        }
        return Result.success(
            PlaybackPlan(
                url = streamUrl(item, msId),
                mediaSourceId = msId,
                playSessionId = ""
            )
        )
    }

    // ==================== 播放上报 ====================

    /**
     * Emby 靠这三个接口把进度写进 `UserData`——
     * 「继续观看」列表和续播位置全靠它，不上报的话进度永远是 0。
     *
     * 失败只记日志：上报出问题不能影响播放本身。
     */
    fun reportStart(item: MediaItem, playSessionId: String) {
        reportPlayback("/Sessions/Playing", item, item.positionTicks, playSessionId, paused = false)
    }

    fun reportProgress(item: MediaItem, positionTicks: Long, paused: Boolean, playSessionId: String) {
        reportPlayback("/Sessions/Playing/Progress", item, positionTicks, playSessionId, paused)
    }

    fun reportStopped(item: MediaItem, positionTicks: Long, playSessionId: String) {
        reportPlayback("/Sessions/Playing/Stopped", item, positionTicks, playSessionId, paused = true)
    }

    // ==================== 用户状态（收藏 / 已看 / 移除继续观看） ====================

    fun setFavorite(itemId: String, favorite: Boolean): Result<Unit> {
        return try {
            requestEmpty(if (favorite) "POST" else "DELETE", "/Users/${uid()}/FavoriteItems/$itemId")
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun setPlayed(itemId: String, played: Boolean): Result<Unit> {
        return try {
            requestEmpty(if (played) "POST" else "DELETE", "/Users/${uid()}/PlayedItems/$itemId")
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 从「继续观看」移除：优先用 Emby 4.8+ 的 `HideFromResume`，不支持时回退到清零进度/清除已看。
     */
    fun removeFromResume(itemId: String): Result<Unit> {
        return try {
            requestEmpty("POST", "/Users/${uid()}/Items/$itemId/HideFromResume?Hide=true")
            Result.success(Unit)
        } catch (e: Throwable) {
            if (isAuthExpired(e)) return Result.failure(e)
            try {
                post("/Users/${uid()}/Items/$itemId/UserData", "{\"PlaybackPositionTicks\":0}")
                Result.success(Unit)
            } catch (e2: Throwable) {
                if (isAuthExpired(e2)) return Result.failure(e2)
                try {
                    requestEmpty("DELETE", "/Users/${uid()}/PlayedItems/$itemId")
                    Result.success(Unit)
                } catch (e3: Throwable) {
                    Result.failure(e3)
                }
            }
        }
    }

    private fun reportPlayback(
        path: String,
        item: MediaItem,
        positionTicks: Long,
        playSessionId: String,
        paused: Boolean
    ) {
        try {
            val body = org.json.JSONObject().apply {
                put("ItemId", item.id)
                if (item.mediaSourceId.isNotEmpty()) put("MediaSourceId", item.mediaSourceId)
                put("PlaySessionId", playSessionId)
                put("PositionTicks", positionTicks)
                put("CanSeek", true)
                put("IsPaused", paused)
                put("IsMuted", false)
                put("PlayMethod", "DirectPlay")
                put("RepeatMode", "RepeatNone")
            }.toString()
            postJson(path, body)
        } catch (e: Throwable) {
            Log.w(TAG, "播放上报失败 $path: ${e.message}")
        }
    }

    private fun postJson(path: String, body: String) {
        val url = normalize(server) + path
        withTls(url) { factory ->
            val conn = open(url, "POST", factory)
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                logTls(conn)
                if (code !in 200..299) Log.w(TAG, "POST $path → HTTP $code")
                // 204 没有响应体，读一下把连接还回池子（读失败无所谓）
                try { conn.inputStream.use { readAll(it) } } catch (ignored: Throwable) {}
            } catch (e: Throwable) {
                // 真出错时才主动断开，别让坏连接留在池子里
                try { conn.disconnect() } catch (ignored: Throwable) {}
                throw e
            }
            // ⚠️ **成功时不要调 `conn.disconnect()`**：
            //    disconnect() 会把 socket 直接关掉，连接池里的复用记录一并作废。
            //    播放上报每 10 秒一次，每次都重新做 TCP + TLS 握手的话，
            //    在这台 1GHz 的机器上光握手就要几百毫秒，还会白烧电。
            //    正常读完响应体后不 disconnect，连接会自动回到池子里被下次复用。
            //    （ImageLoader 里踩过同一个坑，那边注释也写了。）
        }
    }

    // ==================== 底层 HTTP ====================

    class Response(val code: Int, val body: String)

    /**
     * 把这一跳实际谈成的协议与套件记一行日志。
     *
     * 为什么值得单独打：`TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA` 和
     * `…_CHACHA20_POLY1305_SHA256` 的区别，就是「走的是系统 JSSE 还是 Conscrypt」的
     * 直接证据 —— 电视上没法截图，设置页写的「自动」也不说明实际用了谁，
     * 只有这一行说得清。`HttpsURLConnection.getCipherSuite()` 是 API 1 就有的公开方法。
     */
    private fun logTls(conn: java.net.HttpURLConnection) {
        if (conn !is HttpsURLConnection) return
        try {
            Log.d(TAG, "TLS ${conn.cipherSuite} @ ${conn.url.host}")
        } catch (ignored: Throwable) {
        }
    }

    private fun open(
        url: String,
        method: String,
        factory: javax.net.ssl.SSLSocketFactory? = null
    ): java.net.HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = factory
                ?: TlsStack.socketFactory(ctx, TrustMode.BUNDLED)
                ?: throw java.io.IOException("无法构造 TLS socket factory")
        }
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Emby-Authorization", authHeader())
        if (token.isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token)
        return conn
    }

    /**
     * 跑一次 HTTPS 请求，**握手谈不拢时自动换 TLS 栈重试一次**。
     *
     * 换栈的结论会按域名记下来（见 [TlsHostMemory]），所以每个服务器
     * 这辈子最多只付一次失败握手 —— 第一版登录、之后都不再付。
     */
    private fun <T> withTls(url: String, block: (javax.net.ssl.SSLSocketFactory?) -> T): T {
        if (!url.startsWith("https", ignoreCase = true)) return block(null)
        val host = try {
            UrlKit.parse(url).host
        } catch (e: Throwable) {
            ""
        }
        if (host.isEmpty()) return block(null)
        return TlsConn.withRetry(ctx, TrustMode.BUNDLED, host, block)
    }

    private fun get(path: String): Response {
        val url = normalize(server) + path
        return withTls(url) { factory ->
            try {
                val conn = open(url, "GET", factory)
                val code = conn.responseCode
                logTls(conn)
                if (code == 200) {
                    Response(code, conn.inputStream.use { readAll(it) })
                } else {
                    throw IllegalStateException("HTTP $code ${readError(conn)}")
                }
            } catch (e: IOException) {
                // ⚠️ Android 的坑：401 且无 WWW-Authenticate 头 → getResponseCode() 直接抛
                //    "No authentication challenges found"，响应体读不到。
                //    这里还原成明确的 401，避免把「密码错」误报成「网络失败」。
                if (e.message?.contains("No authentication challenges") == true) {
                    throw IllegalStateException("HTTP 401（token 无效或已过期）", e)
                }
                throw e
            }
        }
    }

    private fun post(path: String, body: String): Response {
        val url = normalize(server) + path
        return withTls(url) { factory ->
            val conn = open(url, "POST", factory)
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                logTls(conn)
                if (code in 200..299) {
                    Response(code, conn.inputStream.use { readAll(it) })
                } else {
                    val err = readError(conn)
                    try { conn.disconnect() } catch (_: Throwable) {}
                    throw IllegalStateException("HTTP $code$err")
                }
            } catch (e: IOException) {
                try { conn.disconnect() } catch (_: Throwable) {}
                if (e.message?.contains("No authentication challenges") == true) {
                    throw IllegalStateException("HTTP 401（token 无效或已过期）", e)
                }
                throw e
            }
        }
    }

    private fun requestEmpty(method: String, path: String): Response {
        val url = normalize(server) + path
        return withTls(url) { factory ->
            val conn = open(url, method, factory)
            try {
                if (method.equals("POST", ignoreCase = true)) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.use {}
                }
                val code = conn.responseCode
                logTls(conn)
                if (code in 200..299) {
                    val text = try {
                        conn.inputStream.use { readAll(it) }
                    } catch (_: Throwable) {
                        ""
                    }
                    Response(code, text)
                } else {
                    val err = readError(conn)
                    try { conn.disconnect() } catch (_: Throwable) {}
                    throw IllegalStateException("HTTP $code$err")
                }
            } catch (e: IOException) {
                try { conn.disconnect() } catch (_: Throwable) {}
                if (e.message?.contains("No authentication challenges") == true) {
                    throw IllegalStateException("HTTP 401（token 无效或已过期）", e)
                }
                throw e
            }
        }
    }

    private fun readAll(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            val buf = CharArray(4096)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    private fun readError(conn: java.net.HttpURLConnection): String {
        return try {
            val err = conn.errorStream ?: return ""
            val text = err.use { readAll(it) }.trim()
            if (text.isEmpty()) "" else " " + text.take(200)
        } catch (e: Throwable) {
            ""
        }
    }
}

/**
 * 判断异常是否属于「登录态失效（401）」。
 *
 * 只认明确的 `HTTP 401` 或 Android 4.3 无 `WWW-Authenticate` 头的
 * `No authentication challenges`，避免把含 `401` 的条目 id / 端口号误判成掉登录。
 */
fun isAuthExpired(e: Throwable?): Boolean {
    var cur = e
    var depth = 0
    while (cur != null && depth < 8) {
        val msg = cur.message
        if (msg != null && (
                msg.contains("HTTP 401", ignoreCase = true) ||
                    msg.contains("No authentication challenges", ignoreCase = true)
                )
        ) {
            return true
        }
        cur = cur.cause
        depth++
    }
    return false
}

/**
 * 把底层异常翻成用户能看懂的话。
 *
 * ⚠️ 直接 `e.message` 抛到界面上，用户会看到
 * `No authentication challenges found`、`Protocol not found: /Users/...` 这种
 * 纯英文技术报错 —— 在电视上完全没法自救。这里统一兜住。
 */
fun friendlyError(e: Throwable): String {
    val msg = e.message ?: ""
    return when {
        // Android 4.3：401 且服务端不给 WWW-Authenticate 头时 getResponseCode() 抛这个
        msg.contains("No authentication challenges", ignoreCase = true) ->
            "账号或密码不对（服务端拒绝了这次登录）"
        msg.contains("Protocol not found", ignoreCase = true) ->
            "服务器地址没设置，请先登录"
        msg.contains("401") -> "登录已过期，请重新登录"
        msg.contains("403") -> "这个账号没有权限访问该内容"
        msg.contains("404") -> "服务器上找不到这个内容"
        msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("UnknownHost", ignoreCase = true) ->
            "找不到服务器，检查网络连接"
        msg.contains("timed out", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ->
            "连接服务器超时，检查网络"
        msg.contains("SSL", ignoreCase = true) ||
            msg.contains("handshake", ignoreCase = true) ->
            // ⚠️ 这句要带上「换 TLS 实现」这条出路：
            //    这条路径上最常见的成因**不是证书**，而是 4.3 的系统 JSSE
            //    没有 GCM 套件（见 TlsStack 的注释）。用户在设置里切一次
            //    TLS 实现就能连上，光说「检查服务器地址和网络」只会让他白折腾。
            "安全连接失败（多因系统 TLS 太老），可到设置 → TLS 实现改用 Conscrypt"
        msg.contains("Connection refused", ignoreCase = true) ->
            "服务器拒绝连接"
        msg.contains("failed to connect", ignoreCase = true) ->
            "连不上服务器，检查网络"
        // 网关层错误：服务器本体没起来 / Cloudflare Tunnel 断了。
        // ⚠️ 这种响应体常常是一大坨 HTML 或 JSON（实测 Cloudflare 530 回的是
        //    {"type":"https://developers.cloudflare.com/...","title":"Error 1033..."}），
        //    整段怼到电视屏幕上会糊满整页，而且用户一个字也用不上。
        msg.contains("HTTP 530") || msg.contains("HTTP 502") ||
            msg.contains("HTTP 503") || msg.contains("HTTP 504") ->
            "服务器暂时不可用，稍后再试"
        msg.isEmpty() -> e.javaClass.simpleName
        // 兜底：响应体是 JSON / HTML 就只留开头一小段，别糊满屏幕。
        msg.contains("{") || msg.contains("<") -> msg.take(60) + "…"
        else -> msg
    }
}

/**
 * 登录失败该说什么。
 *
 * ⚠️ **不能直接拿 [friendlyError] 用在登录页上**（2026-11-17 实测踩到）：
 * 它把任何带 `401` 的消息都翻成「登录已过期，请重新登录」—— 那是**已登录用户**
 * token 失效时说的话。可密码打错时服务端回的也是 401
 * （`登录失败 HTTP 401 无效用户名或密码。请重试。`），
 * 于是用户在登录页上看到「登录已过期，请重新登录」，完全对不上，
 * 会以为是自己网络或服务器的问题，反复重试。
 *
 * 其余情况（超时、DNS、TLS、网关 5xx）仍然交给 [friendlyError]。
 */
fun friendlyLoginError(e: Throwable): String {
    val msg = e.message ?: ""
    return when {
        msg.contains("No authentication challenges", ignoreCase = true) ||
            msg.contains("HTTP 401") -> "账号或密码不对（服务端拒绝了这次登录）"
        else -> friendlyError(e)
    }
}

/**
 * 老版本遗留的固定用户 id。
 *
 * ⚠️ **不要再用它**：它是个假 id，`/Users/{id}/Items/Resume` 会返回 0 条，
 * 「最近观看」永远为空。真实 id 从登录响应的 `User.Id` 取，见 [EmbyApi.userId]。
 * 这里保留只是为了兼容可能残留的外部引用。
 */
@Deprecated("用 EmbyApi.userId（登录时从 User.Id 取）")
const val USER_ID = "00000000000000000000000000000000"

/**
 * 可用的排序方式。
 *
 * Emby 的 `SortBy` 直接透传给服务器，各字段的行为以服务端为准：
 * - [SORT_NAME] 名称（字母序）
 * - [SORT_DATE_CREATED] 加入媒体库的时间
 * - [SORT_PREMIERE_DATE] 首播 / 上映时间（客户端默认，降序）
 * - [SORT_PRODUCTION_YEAR] 制作年份
 * - [SORT_RUNTIME] 时长
 */
object SortOption {
    const val SORT_NAME = "SortName"
    const val SORT_DATE_CREATED = "DateCreated"
    const val SORT_PREMIERE_DATE = "PremiereDate"
    const val SORT_PRODUCTION_YEAR = "ProductionYear"
    const val SORT_RUNTIME = "Runtime"

    /**
     * 集号。只用于「季」这一层的子项（集），**不放进 [ALL]**：
     * 对剧/电影这一层没有意义，出现在菜单里只会让人误选。
     */
    const val SORT_INDEX_NUMBER = "IndexNumber"

    /**
     * 一种排序方式。
     *
     * [descending] 是这一项**自带的默认方向**，用在两个地方：
     * ① 没存过排序时的默认；② 用户换到这个字段时方向重置成它。
     * （方向本身用户还可以再单独改，见 HomeActivity.libSortDesc。）
     */
    data class Opt(val key: String, val label: String, val descending: Boolean = false)

    /**
     * 菜单里的顺序：**默认那一项排最前面**，用户一眼看到的就是出厂设置。
     *
     * 默认是「开播时间」降序而不是「最近添加」：
     * 库里的「加入时间」取决于什么时候拷进去的（一次补齐的季整批同一天，
     * 全排在前面），而开播时间才是内容本身的先后 —— 翻新片看它更准。
     */
    val ALL = listOf(
        Opt(SORT_PREMIERE_DATE, "开播时间", descending = true),
        Opt(SORT_DATE_CREATED, "最近添加", descending = true),
        Opt(SORT_NAME, "片名"),
        Opt(SORT_PRODUCTION_YEAR, "年份", descending = true),
        Opt(SORT_RUNTIME, "时长", descending = true)
    )

    /** 出厂默认：开播时间降序。 */
    val DEFAULT: Opt = ALL.first()

    fun labelOf(key: String): String = ALL.firstOrNull { it.key == key }?.label ?: DEFAULT.label

    /** 按 key 取一项；找不到（没存过 / 存的值不认识）就退回 [DEFAULT]。 */
    fun of(key: String): Opt = ALL.firstOrNull { it.key == key } ?: DEFAULT
}

/**
 * 一个媒体条目。
 *
 * 字段按实际用到的裁剪，避免 JSON 太大（这台电视只有 96MB 堆）。
 */
data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val isFolder: Boolean,
    val container: String = "",
    val size: Long = 0,
    val codec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val seriesName: String = "",
    /** 所属剧的 id（Episode 才有）。集本身通常没有海报，要拿剧的。 */
    val seriesId: String = "",
    val overview: String = "",
    val mediaSourceId: String = "",
    /** 集号（Episode 才有）。季条目的 IndexNumber 是季号。 */
    val indexNumber: Int = 0,
    /** 所属季号（Episode 才有）。 */
    val parentIndexNumber: Int = 0,
    /** 总时长（100ns ticks）。 */
    val runtimeTicks: Long = 0,
    /** 用户是否已看完。 */
    val played: Boolean = false,
    /** 是否已加入收藏夹。 */
    val isFavorite: Boolean = false,
    /** 上次播放到的位置（100ns ticks）。 */
    val positionTicks: Long = 0,
    /** 出品年份，0 表示服务器没给。 */
    val productionYear: Int = 0,
    /** 分级（「PG-13」「TV-MA」这类），可能为空。 */
    val officialRating: String = "",
    /** 评分（0~10），0 表示没有。 */
    val communityRating: Double = 0.0,
    /** 类型标签，已拼成「科幻 / 悬疑」这种可直接显示的样子。 */
    val genres: String = ""
) {
    /** 续播起点（毫秒）。 */
    val resumeMs: Long get() = positionTicks / 10_000

    /** 「已看 12:34 / 45:00」，没看过返回空串。 */
    fun progressText(): String {
        if (positionTicks <= 0) return ""
        val seen = formatDuration(positionTicks / 10_000)
        return if (runtimeTicks > 0) "已看 $seen / ${formatDuration(runtimeTicks / 10_000)}" else "已看 $seen"
    }
    /**
     * 列表标题。
     *
     * Emby 的 `Name` 规则是「有分集标题就用标题，没有才回落成『第 N 集』」，
     * 所以直接显示 `Name` 时，同一季里会出现「第 30 集」和「谢画楼跪求…」混排，
     * 看起来像没按集编好号。这里统一用 `IndexNumber` 补上集号。
     */
    fun displayTitle(): String {
        if (type != "Episode" || indexNumber <= 0) return name
        val label = "第 $indexNumber 集"
        return if (name.isEmpty() || name == label) label else "$label · $name"
    }

    /** 展示用的副标题。 */
    fun subtitle(): String {
        val parts = ArrayList<String>()
        if (seriesName.isNotEmpty()) parts.add(seriesName)
        if (codec.isNotEmpty()) parts.add(codec.uppercase())
        if (width > 0 && height > 0) parts.add("${width}×$height")
        if (size > 0) parts.add(formatSize(size))
        val progress = progressText()
        if (progress.isNotEmpty()) parts.add(progress)
        return parts.joinToString(" · ")
    }

    /** 是否是可以直接播放的视频。 */
    val isPlayable: Boolean
        get() = !isFolder && (type == "Episode" || type == "Movie" || type == "Video" ||
            type == "Audio" || type == "TvChannel")

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / 1024.0 / 1024)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }

        /** 毫秒 → `mm:ss` 或 `h:mm:ss`。 */
        fun formatDuration(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%02d:%02d", m, s)
        }
    }
}

data class PlaybackPlan(
    val url: String,
    val mediaSourceId: String,
    val playSessionId: String
)

internal fun buildFallbackStreamUrl(serverBase: String, itemId: String, mediaSourceId: String): String {
    val base = serverBase.trimEnd('/')
    val ms = if (mediaSourceId.isNotEmpty()) "&mediaSourceId=$mediaSourceId" else ""
    return "$base/Videos/$itemId/stream?static=true$ms"
}

internal fun resolveServerPath(serverBase: String, rawPath: String): String {
    val trimmed = rawPath.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }
    val base = serverBase.trimEnd('/')
    val m = Regex("^(https?://[^/?#]+)(.*)$", RegexOption.IGNORE_CASE).find(base)
        ?: return if (trimmed.startsWith("/")) "$base$trimmed" else "$base/$trimmed"
    val origin = m.groupValues[1]
    val basePath = m.groupValues[2].trimEnd('/')
    if (trimmed.startsWith("/")) {
        return if (basePath.isNotEmpty() && !trimmed.startsWith("$basePath/") && trimmed != basePath) {
            base + trimmed
        } else {
            origin + trimmed
        }
    }
    return "$base/$trimmed"
}

internal fun parsePlaybackPlan(
    serverBase: String,
    itemId: String,
    preferredMediaSourceId: String,
    body: String
): PlaybackPlan? {
    val obj = org.json.JSONObject(body)
    val playSessionId = obj.optString("PlaySessionId")
    val sources = obj.optJSONArray("MediaSources") ?: return null
    if (sources.length() == 0) return null
    var src = sources.getJSONObject(0)
    if (preferredMediaSourceId.isNotEmpty()) {
        for (i in 0 until sources.length()) {
            val candidate = sources.getJSONObject(i)
            if (candidate.optString("Id") == preferredMediaSourceId) {
                src = candidate
                break
            }
        }
    }
    val msId = src.optString("Id").ifEmpty { preferredMediaSourceId }
    val direct = src.optString("DirectStreamUrl").trim()
    val url = if (direct.isNotEmpty()) {
        resolveServerPath(serverBase, direct)
    } else {
        buildFallbackStreamUrl(serverBase, itemId, msId)
    }
    return PlaybackPlan(url = url, mediaSourceId = msId, playSessionId = playSessionId)
}
