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

/** UDF 元数据按精确 Range 读取，正片顺读时保留当前响应。 */
internal class IsoRawReader(
    private val source: EmbyHttpDataSource,
    private val uri: Uri
) : RawReader {

    private companion object {
        private const val METADATA_BLOCK_BYTES = 64 * 1024
        private const val MAX_METADATA_BLOCKS = 4
    }

    private var nextOffset = -1L
    private var rangeLeft = 0L
    private var metadataLength = 0L
    private val metadataBlocks = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    var requestHeaders: Map<String, String> = emptyMap()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(length > 0)
        val blockOffset = offset / METADATA_BLOCK_BYTES * METADATA_BLOCK_BYTES
        val inBlock = (offset - blockOffset).toInt()
        if (length <= 2048 && inBlock + length <= METADATA_BLOCK_BYTES && offset <= metadataLength - length) {
            var block = metadataBlocks[blockOffset]
            if (block == null) {
                val blockLength = minOf(metadataLength - blockOffset, METADATA_BLOCK_BYTES.toLong()).toInt()
                try {
                    block = readRange(blockOffset, blockLength)
                    if (metadataBlocks.size >= MAX_METADATA_BLOCKS) {
                        val oldest = metadataBlocks.keys.firstOrNull()
                        if (oldest != null) metadataBlocks.remove(oldest)
                    }
                    metadataBlocks[blockOffset] = block
                } catch (_: IOException) {
                    return readRange(offset, length)
                }
            }
            return block.copyOfRange(inBlock, inBlock + length)
        }
        return readRange(offset, length)
    }

    fun <T> withMetadataReadAhead(length: Long, parse: () -> T): T {
        metadataLength = length
        return try {
            parse()
        } finally {
            metadataLength = 0
            metadataBlocks.clear()
        }
    }

    private fun readRange(offset: Long, length: Int): ByteArray {
        val data = ByteArray(length)
        try {
            readInto(offset, data, 0, data.size, data.size.toLong())
            return data
        } finally {
            close()
        }
    }

    /** 复用连续读取的响应；rangeLength 只覆盖当前 extent 内的字节。 */
    fun readInto(offset: Long, output: ByteArray, outputOffset: Int, length: Int, rangeLength: Long) {
        var copied = 0
        try {
            while (copied < length) {
                val pos = offset + copied
                if (pos != nextOffset || rangeLeft == 0L) {
                    close()
                    val spec = DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(pos)
                        .setLength(rangeLength - copied)
                        .setHttpRequestHeaders(requestHeaders)
                        .build()
                    rangeLeft = source.open(spec)
                    nextOffset = pos
                }
                val want = minOf((length - copied).toLong(), rangeLeft).toInt()
                val n = if (want > 0) source.read(output, outputOffset + copied, want) else 0
                if (n <= 0) throw IOException("ISO 读取不完整：offset=$offset 期望 $length 字节，实得 $copied")
                rangeLeft -= n
                nextOffset += n
                copied += n
            }
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    fun close() {
        rangeLeft = 0
        nextOffset = -1
        source.close()
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
 * HTTP 响应覆盖 extent 内的一段连续数据；每次只把一小批包读入内存并剥壳。
 */
internal class M2tsDataSource(
    private val raw: IsoRawReader,
    private val video: Udf.Video,
    private val uri: Uri
) : DataSource {

    companion object {
        private const val TS_PACKET_BYTES = 188
        private const val WINDOW_PACKETS = 256
        private const val MAX_RANGE_BYTES = 16L * 1024 * 1024
    }

    private var listener: TransferListener? = null
    private var currentSpec: DataSpec? = null

    /** 本次 open 允许读到哪（逻辑偏移，开区间）。 */
    private var limit = 0L
    private var position = 0L

    private var winFirstPacket = -1L
    private var winPackets = 0
    private val win = ByteArray(WINDOW_PACKETS * TS_PACKET_BYTES)
    private val rawBuf = ByteArray(WINDOW_PACKETS * video.packetSize)

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
    }

    override fun getUri(): Uri = uri

    override fun open(dataSpec: DataSpec): Long {
        close()
        val start = dataSpec.position
        val available = video.logicalLength - start
        if (available < 0) throw IOException("ISO 逻辑长度不足：position=$start len=${video.logicalLength}")
        val length = if (dataSpec.length < 0) available else minOf(available, dataSpec.length)
        position = start
        limit = start + length
        currentSpec = dataSpec
        listener?.onTransferStart(this, dataSpec, false)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val spec = currentSpec ?: throw IOException("DataSource 未打开")
        if (position >= limit) return C.RESULT_END_OF_INPUT

        val packet = position / TS_PACKET_BYTES
        if (packet < winFirstPacket || packet >= winFirstPacket + winPackets) loadWindow(packet)
        val index = (position - winFirstPacket * TS_PACKET_BYTES).toInt()
        val available = winPackets * TS_PACKET_BYTES - index
        val count = minOf(length.toLong(), limit - position, available.toLong()).toInt()
        System.arraycopy(win, index, buffer, offset, count)
        position += count
        listener?.onBytesTransferred(this, spec, false, count)
        return count
    }

    override fun close() {
        raw.close()
        currentSpec?.let { listener?.onTransferEnd(this, it, false) }
        currentSpec = null
        winFirstPacket = -1
        winPackets = 0
    }

    private fun loadWindow(firstPacket: Long) {
        val lastPacket = (limit - 1) / TS_PACKET_BYTES
        val count = minOf(WINDOW_PACKETS.toLong(), lastPacket - firstPacket + 1).toInt()
        readDiskRange(firstPacket * video.packetSize, count * video.packetSize)

        for (i in 0 until count) {
            val src = i * video.packetSize + video.prefixLen
            System.arraycopy(rawBuf, src, win, i * TS_PACKET_BYTES, TS_PACKET_BYTES)
        }
        winFirstPacket = firstPacket
        winPackets = count
    }

    /** 按字节跨 extent，允许一个 192 字节包落在两段中。 */
    private fun readDiskRange(logicalStart: Long, length: Int) {
        var copied = 0
        var before = 0L
        val diskLimit = ((limit - 1) / TS_PACKET_BYTES + 1) * video.packetSize
        // 跳转后只打开目标 extent 的对应区间。顺读时接着消费上一窗口的响应。
        for (extent in video.extents) {
            val offset = (logicalStart + copied - before).coerceAtLeast(0L)
            val available = extent.length - offset
            if (available > 0) {
                val count = minOf((length - copied).toLong(), available).toInt()
                val rangeLength = minOf(available, diskLimit - logicalStart - copied, MAX_RANGE_BYTES)
                raw.readInto(extent.start + offset, rawBuf, copied, count, rangeLength)
                copied += count
                if (copied == length) return
            }
            before += extent.length
        }
        throw IOException("ISO 正片长度不足：offset=$logicalStart 期望 $length 字节，实得 $copied")
    }
}

/** 缓存最近一次成功解析的镜像，重播时省掉读取 UDF 目录的多次请求。 */
private object IsoProbe {

    private data class Parsed(val uri: Uri, val token: String, val video: Udf.Video)

    @Volatile private var parsed: Parsed? = null

    fun cached(uri: Uri, token: String): Udf.Video? =
        parsed?.takeIf { it.uri == uri && it.token == token }?.video

    fun parse(uri: Uri, token: String, raw: RawReader, onStep: (String) -> Unit): Udf.Video? {
        onStep("解析光盘镜像结构")
        return Udf.parse(raw, onStep)?.also { video ->
            Log.i("IsoPlay", "ISO 解析结果：$video")
            parsed = Parsed(uri, token, video)
        }
    }
}

/**
 * 能自动认出 ISO 的 DataSource。
 *
 * - 普通 MKV / MP4 → 从第一次响应的前缀检查 UDF 标记，保留同一条流供解封装读取。
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
    private val httpSource = EmbyHttpDataSource(ctx, token)
    private var probed = false
    private val raw = IsoRawReader(httpSource, uri)
    private var isoSource: M2tsDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
        inner?.addTransferListener(transferListener)
    }

    override fun getUri(): Uri = uri

    override fun open(dataSpec: DataSpec): Long {
        // ExoPlayer 的额外请求头也用于 UDF 和 m2ts Range，才能安全沿用首次响应的跳转地址。
        raw.requestHeaders = dataSpec.httpRequestHeaders
        if (!probed) {
            var video = IsoProbe.cached(uri, token)
            if (video == null) {
                val probeLength = Udf.VRS_OFFSET + Udf.VRS_BYTES
                val canPeek = dataSpec.position == 0L && (dataSpec.length < 0 || dataSpec.length >= probeLength)
                val httpLength = if (canPeek) {
                    inner = httpSource
                    listener?.let { httpSource.addTransferListener(it) }
                    httpSource.open(dataSpec)
                } else {
                    C.LENGTH_UNSET.toLong()
                }
                val isIso = if (canPeek) {
                    val prefix = httpSource.peekPrefix(16, probeLength) { head, len ->
                        !Udf.looksLikeKnownNonIso(head, len)
                    }
                    Udf.looksLikeIso(prefix, Udf.VRS_OFFSET)
                } else {
                    val vrs = try {
                        raw.readAt(Udf.VRS_OFFSET.toLong(), Udf.VRS_BYTES)
                    } catch (_: IOException) {
                        null
                    }
                    vrs != null && Udf.looksLikeIso(vrs, 0)
                }
                if (canPeek && !isIso) {
                    probed = true
                    return httpLength
                }
                if (canPeek) httpSource.close()
                if (isIso) {
                    video = raw.withMetadataReadAhead(httpLength) { IsoProbe.parse(uri, token, raw, onStep) }
                }
            }
            isoSource = video?.let { M2tsDataSource(raw, it, uri) }
            probed = true
        }
        val ds = isoSource ?: httpSource
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
