package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.io.IOException

/**
 * 直接读远端原始字节的最小接口。
 *
 * UDF 解析不好塞进 [DataSource]（它要按绝对偏移随机读整块，和 ExoPlayer 的
 * 顺流语义完全不同），所以单独抽一层：谁实现都行，方便以后换成本地文件。
 */
interface RawReader {
    /** 读 [length] 字节，越界/短读抛 [IOException]。 */
    fun readAt(offset: Long, length: Int): ByteArray
}

/**
 * 用 [EmbyHttpDataSource] 按 HTTP Range 从远端 ISO 读原始字节。
 *
 * 每次 [readAt] 开关一次 DataSource —— 连接本身会还回 [EmbyHttpDataSource]
 * 的空闲池（15 秒内可复用），所以后续请求省掉 TCP + TLS 握手；
 * UDF 解析一共也就几十次小块读取，这个开销可以接受。
 */
class IsoRawReader(
    private val ctx: Context,
    private val token: String,
    private val uri: Uri
) : RawReader {

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(length > 0) { "length 必须 > 0" }
        val ds = EmbyHttpDataSource(ctx, token)
        val buf = ByteArray(length)
        try {
            ds.open(DataSpec(uri, offset, length.toLong()))
            var read = 0
            while (read < length) {
                val n = ds.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
            if (read < length) {
                throw IOException("ISO 读取不完整：offset=$offset 期望 $length 字节，实得 $read")
            }
            return buf
        } finally {
            try { ds.close() } catch (ignored: Throwable) {}
        }
    }
}

/**
 * 把 m2ts 还原成标准 TS 的 DataSource。
 *
 * ## 它干什么
 *
 * 蓝光 `BDMV/STREAM/` 下的 m2ts 每个包 192 字节：前 4 字节是 ATS（到达时间戳），
 * 后面才是 188 字节标准 TS。ExoPlayer 的 `TsExtractor` 只认 188
 * （`TS_PACKET_SIZE = 188`，sync 固定在 0/188/376…），所以这 4 字节必须剥掉。
 *
 * 剥壳后交给 ExoPlayer 的就是一条普通 188 字节 TS 流，现成的 `TsExtractor`
 * 能解出 H.264 / HEVC / AC3 等轨道，并且自带 sniff、时长探测、seek ——
 * 这些都不用自己写。
 *
 * ## 两个容易踩的坑
 *
 * 1. **文件在盘上是碎的**：实测这张盘正片分 4 个 extent、每段约 1GB。
 * 2. **extent 边界不受 192 对齐**：那些长度都是 2048（扇区）的整数倍
 *    （`1071644672 = 523264 × 2048`），而 2048 不是 192 的整数倍 ——
 *    所以一个 192 字节的包**可能横跨两个 extent**。
 *
 * 于是不能「按包算每个 extent 有多少个包、逐 extent 读整包」，必须按**字节**映射：
 * ```
 * 逻辑字节偏移（所有 extent 首尾相接）→ 落在哪个 extent 的哪个位置
 * ```
 * 一次读 [WINDOW_PACKETS] 个包（跨 extent 时拆成几段拼起来），再统一剥壳。
 */
class M2tsDataSource(
    private val raw: RawReader,
    private val video: Udf.Video,
    private val uri: Uri
) : DataSource {

    companion object {
        private const val TAG = "IsoPlay"

        /** 一次预读多少个包。2048 包 = 384KB 原始数据 / 385KB 剥壳后。 */
        private const val WINDOW_PACKETS = 2048
    }

    private var listener: TransferListener? = null
    private var currentSpec: DataSpec? = null
    private var opened = false
    private var ended = false

    /** 本次 open 允许读到哪（逻辑偏移，开区间）。 */
    private var limit = 0L

    /** 当前逻辑位置（剥壳后的 TS 字节偏移）。 */
    private var position = 0L

    // ---- 预读窗口 ----
    private var winFirstPacket = -1L
    private var winPackets = 0
    /** 窗口内容：188 字节/包紧凑存放。 */
    private var win = ByteArray(0)
    /** 原始数据缓冲（192 字节/包），跨 extent 拼接用。 */
    private var rawBuf = ByteArray(0)

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
    }

    override fun getUri(): Uri = uri

    override fun open(dataSpec: DataSpec): Long {
        val len = video.logicalLength
        val start = dataSpec.position.coerceAtLeast(0L)
        if (start > len) throw IOException("ISO 逻辑长度不足：position=$start len=$len")
        position = start
        limit = if (dataSpec.length < 0) len else minOf(len, start + dataSpec.length)
        winFirstPacket = -1
        winPackets = 0
        ended = false
        opened = true
        currentSpec = dataSpec
        listener?.onTransferStart(this, dataSpec, false)
        return limit - start
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ended || position >= limit) return C.RESULT_END_OF_INPUT
        if (!opened) throw IOException("DataSource 未打开")

        val want = minOf(length.toLong(), limit - position).toInt()
        var remain = want
        var out = offset
        while (remain > 0) {
            val needPacket = position / 188
            if (needPacket < winFirstPacket || needPacket >= winFirstPacket + winPackets) {
                loadWindow(needPacket)
            }
            if (winPackets <= 0) throw IOException("ISO 窗口为空（位置 $position）")
            val idx = (position - winFirstPacket * 188).toInt()
            val avail = winPackets * 188 - idx
            if (avail <= 0) break   // 兜底，防死循环
            val n = minOf(remain, avail)
            System.arraycopy(win, idx, buffer, out, n)
            out += n
            remain -= n
            position += n
        }
        val read = want - remain
        if (read > 0) {
            currentSpec?.let { listener?.onBytesTransferred(this, it, false, read) }
        }
        if (position >= limit) ended = true
        return if (read > 0) read else C.RESULT_END_OF_INPUT
    }

    override fun close() {
        currentSpec?.let { listener?.onTransferEnd(this, it, false) }
        opened = false
        ended = true
        currentSpec = null
        winFirstPacket = -1
        winPackets = 0
        // 缓冲留着复用：拖动进度条会反复 open/read，
        // 每次重新分配会把这台 96MB 堆上限的老机器拖进 GC。
    }

    // ==================== 窗口加载 ====================

    private fun loadWindow(firstPacket: Long) {
        val totalPackets = video.logicalLength / 188
        if (firstPacket >= totalPackets) throw IOException("请求位置超出 ISO 末尾")
        val count = minOf(WINDOW_PACKETS.toLong(), totalPackets - firstPacket).toInt()
        if (count <= 0) throw IOException("ISO 空")

        if (win.size < count * 188) win = ByteArray(count * 188)
        val need = count * video.packetSize
        if (rawBuf.size < need) rawBuf = ByteArray(need)

        winFirstPacket = firstPacket

        // 按字节把这一段读出来（可能跨 extent）
        val diskStart = firstPacket * video.packetSize
        val got = readDiskRange(diskStart, need)

        // 统一剥壳：192 → 188
        var src = 0
        var dst = 0
        val usable = (got / video.packetSize).toInt()
        for (i in 0 until usable) {
            System.arraycopy(rawBuf, src + video.prefixLen, win, dst, 188)
            src += video.packetSize
            dst += 188
        }
        winPackets = usable
        if (usable < count) {
            Log.w(TAG, "ISO 窗口只读到 $usable/$count 个包（first=$firstPacket）")
        }
    }

    /**
     * 从「逻辑字节偏移」读 [length] 字节，跨 extent 时拆成几段。
     *
     * @return 实际读到的字节数（正常情况等于 [length]）
     */
    private fun readDiskRange(logicalStart: Long, length: Int): Int {
        var got = 0
        var remaining = length
        // 先定位起点落在哪个 extent 的第几个字节
        var extIndex = 0
        var bytesBefore = 0L
        while (extIndex < video.extents.size) {
            val len = video.extents[extIndex].length
            if (logicalStart < bytesBefore + len) break
            bytesBefore += len
            extIndex++
        }
        while (remaining > 0 && extIndex < video.extents.size) {
            val ext = video.extents[extIndex]
            val offsetInExt = logicalStart + got - bytesBefore
            if (offsetInExt >= ext.length) {
                bytesBefore += ext.length
                extIndex++
                continue
            }
            val n = minOf(remaining.toLong(), ext.length - offsetInExt).toInt()
            val data = raw.readAt(ext.start + offsetInExt, n)
            System.arraycopy(data, 0, rawBuf, got, data.size)
            got += data.size
            remaining -= data.size
            if (data.size < n) break   // 读到盘尾（数据不一致）
            bytesBefore += ext.length
            extIndex++
        }
        return got
    }
}

/**
 * 判断「这条取流地址里装的是不是光盘镜像」，结果按 URL 缓存。
 *
 * ## 为什么要缓存
 *
 * ExoPlayer 会**并发**创建两个 DataSource（一个 sniff、一个正式读取），
 * 每个都要判断一次；而判断一次 ISO 就是几十次 Range 请求。
 * 缓存之后第二次起直接复用。
 */
private object IsoProbe {

    private val lock = Any()
    private var cacheUrl: String? = null
    private var cacheVideo: Udf.Video? = null
    private var cacheDone = false

    fun probe(ctx: Context, token: String, uri: Uri, onStep: (String) -> Unit): Udf.Video? {
        val key = uri.toString()
        synchronized(lock) {
            if (cacheDone && cacheUrl == key) return cacheVideo
        }
        val raw = IsoRawReader(ctx, token, uri)
        var video: Udf.Video? = null
        try {
            if (Udf.looksLikeIso(raw)) {
                onStep("解析光盘镜像结构")
                video = Udf.parse(raw, onStep)
                Log.i(TAG, "ISO 解析结果：$video")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ISO 探测失败：${e.message}")
            video = null
        }
        synchronized(lock) {
            cacheUrl = key
            cacheVideo = video
            cacheDone = true
        }
        return video
    }

    private const val TAG = "IsoPlay"
}

/**
 * 能自动认出 ISO 的 DataSource。
 *
 * 这是把整套东西接进 ExoPlayer 的**唯一改动点**：`PlayerActivity` 里
 * 原来 `DataSource.Factory { EmbyHttpDataSource(this, token) }` 换成
 * `IsoDataSourceFactory(this, token, uri)`，其余一行不动。
 *
 * - 普通 mkv / mp4 → 探测一次发现不是 UDF，直接透传给 [EmbyHttpDataSource]，
 *   行为和老版本完全一致，没有任何额外开销（探测本身只发一次 8 字节 Range）。
 * - 光盘镜像 → 解析出正片 m2ts 的 extent，内部换成 [M2tsDataSource]，
 *   对外表现为「一条 188 字节的长 TS 流」，ExoPlayer 的 TsExtractor 照常解。
 */
class IsoDataSource(
    private val ctx: Context,
    private val token: String,
    private val uri: Uri,
    private val onStep: (String) -> Unit = {}
) : DataSource {

    private var listener: TransferListener? = null
    private var inner: DataSource? = null
    private var probed = false
    private var video: Udf.Video? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
        inner?.addTransferListener(transferListener)
    }

    override fun getUri(): Uri = uri

    override fun open(dataSpec: DataSpec): Long {
        if (!probed) {
            probed = true
            video = IsoProbe.probe(ctx, token, uri, onStep)
        }
        val ds: DataSource = if (video != null) {
            M2tsDataSource(IsoRawReader(ctx, token, uri), video!!, uri)
        } else {
            EmbyHttpDataSource(ctx, token)
        }
        listener?.let { ds.addTransferListener(it) }
        inner = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ds = inner ?: throw IOException("DataSource 未打开")
        return ds.read(buffer, offset, length)
    }

    override fun close() {
        val ds = inner
        inner = null
        if (ds != null) {
            try { ds.close() } catch (ignored: Throwable) {}
        }
    }
}

/**
 * [IsoDataSource] 的工厂。
 *
 * ⚠️ `createDataSource()` **每次都要返回新实例** —— ExoPlayer 会并发用多个
 * DataSource（嗅探一个、正式读取一个），共用有状态的实例会让字节流错乱。
 * 这个 Factory 本身只持有不可变的 ctx / token / uri，可以放心共享。
 */
class IsoDataSourceFactory(
    private val ctx: Context,
    private val token: String,
    private val uri: Uri,
    private val onStep: (String) -> Unit = {}
) : DataSource.Factory {

    override fun createDataSource(): DataSource = IsoDataSource(ctx, token, uri, onStep)
}
