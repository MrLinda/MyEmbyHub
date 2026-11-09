package work.luxiaoxiao.tvcheck

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.video.VideoSize
import java.io.File
import javax.net.ssl.HttpsURLConnection

/**
 * 网络播放探针：**用自写的 [EmbyHttpDataSource] 真播网络视频**。
 *
 * 这是整个可行性验证的最后一环：
 *   API 查库 → 拼取流 URL → 自定义 DataSource 取流 → ExoPlayer 解码 → 上屏。
 *
 * 会**连续播放多个样本**（按容器/编码分组，每组挑一个），
 * 一次运行就覆盖 mp4/h264、mkv/hevc 等不同格式组合，
 * 避免为了验一个格式就重装一次 APK。
 *
 * 界面上看不出来的信息（解码器名、丢帧、播放位置）全部落盘，
 * 用 `adb pull` 取回，避免依赖截图。
 *
 * ⚠️ 线程约束：查 API 走网络，放在后台线程；
 * ExoPlayer 的所有调用必须集中在同一个线程，这里统一放主线程。
 */
object NetPlayProbe {

    private const val TAG = "TvCheck"
    const val RESULT_FILE = "/sdcard/tvcheck-netplay.txt"

    /** 每个样本的播放观察时长（毫秒）。 */
    private const val WATCH_MS = 12000L

    /** 最多播几个样本。 */
    private const val MAX_SAMPLES = 4

    /**
     * 阶段一（后台线程）：查 API 找候选视频项 → 切主线程起播放器。
     */
    fun run(
        ctx: Context,
        server: String,
        token: String,
        onLine: (String) -> Unit,
        onPlayerReady: (ExoPlayer) -> Unit,
        onFinished: (String) -> Unit
    ) {
        val sb = StringBuilder()
        val emit: (String) -> Unit = { s ->
            sb.append(s).append('\n')
            onLine(s)
            Log.i(TAG, s)
        }

        emit("===== 网络播放探针（自写 DataSource + ExoPlayer）=====")
        emit("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        emit("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        emit("服务器: $server")
        emit("")

        if (token.isEmpty()) {
            emit("✗ 没有 api_key，先跑 cmd=login")
            finish(sb.toString(), onFinished)
            return
        }

        // ---------- 1) 从 API 找候选视频项 ----------
        emit("--- 1) 查 API 找候选视频项 ---")
        val candidates = findVideoCandidates(ctx, server, token) { emit(it) }
        if (candidates.isEmpty()) {
            emit("✗ 没找到可播的视频项")
            finish(sb.toString(), onFinished)
            return
        }
        val samples = candidates.take(MAX_SAMPLES)
        for (item in samples) {
            emit("  · ${item.container}/${item.codec} ${item.width}x${item.height}  ${item.name}  (${item.size} 字节)")
        }
        emit("")

        // ---------- 2) 主线程逐个实播 ----------
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                playAll(ctx, server, token, samples, emit, onPlayerReady, sb, onFinished)
            } catch (e: Throwable) {
                emit("✗ 起播放器失败：${e.javaClass.simpleName}: ${e.message}")
                finish(sb.toString(), onFinished)
            }
        }
    }

    /** 阶段二（主线程）：一个接一个地播，每个观察 12 秒。 */
    private fun playAll(
        ctx: Context,
        server: String,
        token: String,
        samples: List<VideoItem>,
        emit: (String) -> Unit,
        onPlayerReady: (ExoPlayer) -> Unit,
        sb: StringBuilder,
        onFinished: (String) -> Unit
    ) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun playAt(index: Int) {
            if (index >= samples.size) {
                emit("--- 全部样本播完 ---")
                finish(sb.toString(), onFinished)
                return
            }
            val item = samples[index]
            val url = "$server/Videos/${item.id}/stream?static=true&mediaSourceId=${item.mediaSourceId}"
            val mimeType = mimeTypeOf(item.container)

            emit("--- 样本 ${index + 1}/${samples.size}：${item.container}/${item.codec} ${item.width}x${item.height} ---")
            emit("  $url")

            val dsLog = StringBuffer()
            // ⚠️ **每次 createDataSource() 必须返回新实例**：
            // ExoPlayer 会并发使用多个 DataSource（嗅探 + 正式读取），
            // 而本 DataSource 是**有状态**的（各自持有 socket）。
            // 返回同一个实例 → 两个线程抢同一个 socket → 字节流错乱 →
            // ExoPlayer 所有 extractor 都认不出容器（PARSING_CONTAINER_UNSUPPORTED）。
            val dsFactory = DataSource.Factory { EmbyHttpDataSource(ctx, token, dsLog) }
            val mediaSource = ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(
                    MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
                )

            var state = "未开始"
            var decoderName = "(未触发)"
            var videoSize = "(未知)"
            var dropped = 0L
            var errorText: String? = null
            var ready = false

            val player = ExoPlayer.Builder(ctx).build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, playbackState: Int) {
                    state = when (playbackState) {
                        ExoPlayer.STATE_IDLE -> "IDLE"
                        ExoPlayer.STATE_BUFFERING -> "BUFFERING"
                        ExoPlayer.STATE_READY -> "READY"
                        ExoPlayer.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN"
                    }
                    if (playbackState == ExoPlayer.STATE_READY) ready = true
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long
                ) {
                    dropped += droppedFrames
                }

                override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                    errorText = "${error.errorCodeName}: ${error.message}"
                }

                // AnalyticsListener 在 2.19.1 是 interface，回调名 onVideoDecoderInitialized
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoder: String,
                    initializationTimeMs: Long
                ) {
                    decoderName = decoder
                }

                override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, size: VideoSize) {
                    videoSize = "${size.width}x${size.height}"
                }
            })

            onPlayerReady(player)
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true

            main.postDelayed({
                val positionMs = player.currentPosition
                val buffered = player.bufferedPosition
                val duration = if (player.duration == C.TIME_UNSET) -1L else player.duration

                emit("  取流: ${if (dsLog.isNotEmpty()) dsLog.toString().trim().replace("\n", " | ") else "(空)"}")
                emit("  状态=$state  位置=${positionMs}ms  缓冲到=${buffered}ms  时长=${if (duration < 0) "未知" else "$duration ms"}")
                emit("  分辨率=$videoSize  解码器=$decoderName  丢帧=$dropped  错误=${errorText ?: "无"}")

                val ok = ready && positionMs > 0 && errorText == null
                if (ok) {
                    emit("  ✅ **播放成功**（${item.container}/${item.codec} 硬解上屏）")
                } else if (errorText != null) {
                    emit("  ❌ **播放失败**：$errorText")
                } else {
                    emit("  ⚠️ **未确认成功**（状态=$state 位置=${positionMs}ms）")
                }
                emit("")

                try { player.release() } catch (ignored: Throwable) {}
                main.postDelayed({ playAt(index + 1) }, 500)
            }, WATCH_MS)
        }

        playAt(0)
    }

    private fun finish(text: String, onFinished: (String) -> Unit) {
        try {
            File(RESULT_FILE).writeText(text)
            Log.i(TAG, "网络播放探针结果已写入 $RESULT_FILE")
        } catch (e: Throwable) {
            Log.e(TAG, "写网络播放结果失败", e)
        }
        onFinished(text)
    }

    internal class VideoItem(
        val id: String,
        val mediaSourceId: String,
        val name: String,
        val container: String,
        val size: Long,
        val codec: String,
        val width: Int,
        val height: Int
    ) {
        override fun toString(): String = "$container/$codec ${width}x$height"
    }

    /** 从 /sdcard/tvcheck.conf 里读 api_key。 */
    private fun currentToken(ctx: Context): String {
        return try {
            val f = File("/sdcard/tvcheck.conf")
            if (!f.exists()) return ""
            var key = ""
            f.forEachLine { l ->
                val s = l.trim()
                if (s.startsWith("api_key=")) key = s.substringAfter("api_key=").trim()
            }
            key
        } catch (e: Throwable) {
            ""
        }
    }

    /** 由容器名推 MIME；显式给 mimeType 可以绕过 ExoPlayer 的嗅探路径。 */
    private fun mimeTypeOf(container: String): String = when (container.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "video/*"
    }

    /**
     * 用已登录的 token 查视频项，按「容器/编码」分组，
     * 每组挑一个代表（优先 ≤1080p），返回候选列表。
     */
    internal fun findVideoCandidates(
        ctx: Context,
        server: String,
        token: String,
        emit: (String) -> Unit
    ): List<VideoItem> {
        var conn: HttpsURLConnection? = null
        return try {
            val factory = TlsStack.forceTls12(
                TlsStack.socketFactory(ctx, false, TlsMatrix.TrustMode.BUNDLED)!!
            )
            // ⚠️ Fields 会过滤返回字段，IsFolder / Type 也要显式带上，
            //    否则 optBoolean("IsFolder", true) 会把视频项全判成文件夹。
            conn = (java.net.URL(
                "$server/Items?Limit=80&Recursive=true&IncludeMedia=Video" +
                    "&Fields=Id,Name,Type,MediaType,IsFolder,Container,Size,MediaSources" +
                    "&SortBy=SortName"
            ).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = factory
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("X-Emby-Token", token)
            }
            val code = conn.responseCode
            emit("  GET /Items → HTTP $code")
            if (code != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val items = org.json.JSONObject(body).optJSONArray("Items") ?: run {
                emit("  响应里没有 Items 数组：${body.take(300)}")
                return emptyList()
            }

            val groups = LinkedHashMap<String, VideoItem>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (o.optBoolean("IsFolder", false)) continue
                val ms = o.optJSONArray("MediaSources") ?: continue
                if (ms.length() == 0) continue
                val src = ms.getJSONObject(0)
                var codec = "?"
                var width = 0
                var height = 0
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
                val item = VideoItem(
                    id = o.optString("Id"),
                    mediaSourceId = src.optString("Id"),
                    name = o.optString("Name"),
                    container = o.optString("Container").lowercase(),
                    size = o.optLong("Size"),
                    codec = codec.lowercase(),
                    width = width,
                    height = height
                )
                val key = "${item.container}/${item.codec}"
                val old = groups[key]
                // 同组里挑分辨率低的（解码压力小）
                if (old == null || (item.height in 1..1080 && old.height !in 1..1080)) {
                    groups[key] = item
                }
            }
            emit("  共 ${items.length()} 项，得到 ${groups.size} 种「容器/编码」组合")
            groups.values.toList()
        } catch (e: Throwable) {
            emit("  ✗ ${e.javaClass.simpleName}: ${(e.message ?: "").substringBefore('\n')}")
            emptyList()
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }
}
