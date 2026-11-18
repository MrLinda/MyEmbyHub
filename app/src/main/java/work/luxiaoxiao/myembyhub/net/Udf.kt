package work.luxiaoxiao.myembyhub.net

import android.util.Log
import java.io.IOException

/**
 * 蓝光光盘镜像（.iso）的最小 UDF / BDMV 解析。
 *
 * ## 为什么需要
 *
 * 服务器把一张 `.iso` 挂进媒体库时，`/Videos/{id}/stream?static=true`
 * 吐出来的是**整张盘的原始字节**：开头 32KB 全是 0，到 0x8000 才是 UDF 的
 * "BEA01" 卷识别序列。ExoPlayer 的十六个 extractor 挨个 sniff 一遍，
 * 全是零 → `UnrecognizedInputFormatException` / `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`。
 * 2026-11-18 实测：emby.example.com《玉簟秋》S01E09 《Lament.of.Autumn.S01E09.iso》
 * 就是标准蓝光 UDF 2.50 盘（tsMuxeR 2.18 生成的）。
 *
 * 所以这里做的是**在盘里找到正片**：
 *
 * 1. 按 UDF 规范走 anchor(sector 256) → VDS → PD/LVD → FSD → 根目录 FID 列表；
 * 2. 进 `BDMV/STREAM/`，挑体积最大的 m2ts 文件；
 * 3. 拿到它的 **extent 列表**（盘内零散分布的连续块）。
 *
 * 拿到 extent 之后交给 [M2tsDataSource] 按范围读、剥掉 m2ts 的 4 字节 ATS 头，
 * 还原成标准 188 字节 TS，再喂 ExoPlayer 现成的 `TsExtractor`。
 *
 * ## 只做正片，不做导航
 *
 * 不解析 `index.bdmv` / `PLAYLIST` → 没有「选段 / 花絮 / 字幕轨」菜单。
 * 用户要的是「这一集能播」，不是复刻蓝光播放器。
 * 加密盘（有 `AACS/` 目录）也不处理 —— 实测这张盘没有，干净的。
 *
 * ## 分区映射（UDF 2.50 的坑）
 *
 * 蓝光 UDF 2.50 带一个 **Metadata Partition**，绝大多数文件条目（FE）和目录项（FID）
 * 都住在它里面，不在物理分区里。所以「块号 → 绝对扇区」不能一律 `partStart + loc`：
 *
 * - `partRef == 0` → 物理分区：`partStart + loc`
 * - `partRef >= 1` → 元数据分区：`partStart + metaStart + loc`
 *
 * 实测这张盘的映射链（全部核对过字节）：
 * ```
 * partStart   = 288（PD 里 partitionStartingLocation）
 * metaStart   = 32 （物理块 0 那个 fileType=250 的元数据文件，extent 指向块 32）
 * FSD         = partRef 1, loc 0  → 绝对扇区 320（确实 tag=256）
 * 根目录 FE   = partRef 1, loc 2  → 绝对扇区 322（tag=266, fileType=4 目录）
 * BDMV  FE    = partRef 1, loc 4  → 324
 * STREAM FE   = partRef 1, loc 36 → 356
 * 00000.m2ts  = partRef 1, loc 38 → 358（4 个 extent，partRef 都是 0）
 *    extent1: 物理扇区 288+160=448 → 那里是 `47 40 00 10` 的 TS 包 ✓
 * ```
 */
object Udf {

    /** 一个 extent：盘内连续的一段字节；[start] 是绝对字节偏移。 */
    class Extent(val start: Long, val length: Long)

    /** 盘里找到的正片。 */
    class Video(
        val name: String,
        val extents: List<Extent>,
        /** 192（m2ts，每包 4 字节 ATS 头）或 188（标准 TS）。 */
        val packetSize: Int,
        /** 每个包要剥掉的头部字节数。 */
        val prefixLen: Int,
        /** 剥完壳以后的逻辑长度（TS 字节数）。 */
        val logicalLength: Long
    ) {
        override fun toString(): String {
            val head = extents.firstOrNull()
            return "Video(name=$name, extents=${extents.size}, " +
                "first=(start=${head?.start}, len=${head?.length}), " +
                "packetSize=$packetSize, prefixLen=$prefixLen, logicalLength=$logicalLength, " +
                "totalBytes=${extents.fold(0L) { a, x -> a + x.length }})"
        }
    }

    private const val SECTOR = 2048L

    private const val TAG_ANCHOR = 2
    private const val TAG_PD = 5
    private const val TAG_LVD = 6
    private const val TAG_FSD = 256
    private const val TAG_FID = 257
    private const val TAG_FE = 261
    private const val TAG_EFE = 266

    /** 目录的 fileType。其余值（0 / 5 / 250 / 249 …）都按「非目录」处理。 */
    private const val FT_DIR = 4
    /** UDF 2.50 元数据分区文件。 */
    private const val FT_METADATA = 250

    /** ISO 是不是 UDF。只看卷识别序列，一次 8 字节的 Range 就够了。 */
    fun looksLikeIso(reader: RawReader): Boolean {
        return try {
            // VRS 在 sector 16（偏移 32768），第一个描述符以 "BEA01" 开头。
            val vrs = reader.readAt(16 * SECTOR, 8)
            vrs.size >= 6 && vrs.copyOfRange(1, 6).contentEquals("BEA01".toByteArray())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析出正片。失败返回 null（调用方退回「直接播原始流」的老路径）。
     *
     * @param onStep 进度回调（解析要发若干次 Range 请求，界面上给句话，别让用户以为卡死）
     */
    fun parse(reader: RawReader, onStep: (String) -> Unit = {}): Video? {
        try {
            onStep("读取卷信息")
            val anchor = reader.readAt(256 * SECTOR, SECTOR.toInt())
            if (u16(anchor, 0) != TAG_ANCHOR) return fail(onStep, "anchor 标记不是 AVDP")
            val vdsLen = u32(anchor, 16)
            val vdsLoc = u32(anchor, 20)
            if (vdsLen <= 0 || vdsLen > 1024L * 1024 || vdsLoc <= 0) {
                return fail(onStep, "anchor 里的 VDS 位置无效：len=$vdsLen loc=$vdsLoc")
            }

            onStep("解析卷描述符")
            val vds = reader.readAt(vdsLoc * SECTOR, vdsLen.toInt())
            var partStart = -1L
            var lvdOff = -1
            // UDF 规定每个卷描述符**占整数个逻辑扇区**，所以直接按扇区扫最稳：
            // VDS 撑死 16 个扇区（32KB），一次 Range 就全读回来了，不差这点计算。
            //
            // 不要按「16 + CRC 长度」推算步长：PVD 的 CRC 长度字段在这张盘上
            // 读到的是 5780（512 字节的 PVD 根本不该有这么长），照它算会一步跨过头，
            // 直接跳过后面的 PD/LVD —— 症状是「有 anchor 有 VDS，却说找不到分区」。
            var sec = 0
            while (sec + 2048 <= vds.size) {
                val tag = u16(vds, sec)
                if (tag == TAG_PD) {
                    partStart = u32(vds, sec + 188) * SECTOR
                } else if (tag == TAG_LVD) {
                    lvdOff = sec
                }
                sec += 2048
            }
            if (partStart < 0 || lvdOff < 0) {
                return fail(onStep, "VDS 里没找到 PD/LVD（partStart=$partStart lvdOff=$lvdOff）")
            }

            // 元数据分区起点：物理块 0..15 里那个 fileType=250 的文件条目
            var metaStart = 0L
            for (blk in 0..15) {
                val fe = readFe(reader, partStart, 0L, 0, blk.toLong()) ?: continue
                if (fe.fileType == FT_METADATA && fe.extents.isNotEmpty()) {
                    metaStart = fe.extents[0].start - partStart
                    break
                }
            }

            onStep("定位文件集")
            // FSD：LVD 的 logicalVolumeContentsUse 直接指着它（long_ad 在 LVD + 248）
            var fsd: ByteArray? = null
            val fsdLen = u32(vds, lvdOff + 248)
            val fsdLoc = u32(vds, lvdOff + 252)
            val fsdPref = u16(vds, lvdOff + 256)
            if (fsdLen > 0) {
                fsd = readBlock(reader, partStart, metaStart, fsdPref, fsdLoc)
                if (fsd != null && u16(fsd, 0) != TAG_FSD) fsd = null
            }
            if (fsd == null) {
                // 兜底：元数据分区前 128 块里扫一遍（不是所有刻录器都老实填这个字段）
                for (blk in 0..127) {
                    val b = readBlock(reader, partStart, metaStart, 1, blk.toLong()) ?: continue
                    if (u16(b, 0) == TAG_FSD) { fsd = b; break }
                }
            }
            if (fsd == null) return fail(onStep, "找不到文件集描述符（FSD）")

            // 根目录 ICB：FSD 里那个「指向一个目录条目」的 long_ad。
            //
            // 不硬编码偏移：按 ECMA-167 推算该在 411 附近，但实测这张盘写在 400
            // （tsMuxeR 自己的排法）。与其猜，不如把整块扫一遍、逐个验证
            // 「那个块确实是个目录条目」—— 这一步只有 2~3 次网络读，划算。
            onStep("定位根目录")
            var rootLoc = -1L
            var rootPref = 0
            var off = 16
            while (off + 16 <= fsd.size) {
                val len = u32(fsd, off)
                val loc = u32(fsd, off + 4)
                val pref = u16(fsd, off + 8)
                if (len == SECTOR && loc in 1L..2_000_000L && pref <= 2) {
                    val probe = readFe(reader, partStart, metaStart, pref, loc)
                    if (probe != null && probe.fileType == FT_DIR) {
                        rootLoc = loc
                        rootPref = pref
                        break
                    }
                }
                off += 4
            }
            if (rootLoc < 0) return fail(onStep, "FSD 里没有指向目录的 long_ad，定位不到根目录")

            val root = readFe(reader, partStart, metaStart, rootPref, rootLoc)
                ?: return fail(onStep, "根目录 ICB 读不到")
            val rootEntries = readDir(reader, partStart, metaStart, root)
            onStep("进入 BDMV/STREAM（根目录 ${rootEntries.size} 项）")
            val bdmv = rootEntries.firstOrNull { it.name.equals("BDMV", ignoreCase = true) }
                ?: return fail(onStep, "根目录里没有 BDMV（不是蓝光结构）")
            val bdmvFe = readFe(reader, partStart, metaStart, bdmv.partRef, bdmv.icbLoc)
                ?: return fail(onStep, "BDMV 目录 ICB 读不到")
            if (bdmvFe.fileType != FT_DIR) return fail(onStep, "BDMV 不是目录（fileType=${bdmvFe.fileType}）")
            val bdmvEntries = readDir(reader, partStart, metaStart, bdmvFe)
            val stream = bdmvEntries.firstOrNull { it.name.equals("STREAM", ignoreCase = true) }
                ?: return fail(onStep, "BDMV 里没有 STREAM（目录项 ${bdmvEntries.map { it.name }}）")
            val streamFe = readFe(reader, partStart, metaStart, stream.partRef, stream.icbLoc)
                ?: return fail(onStep, "STREAM 目录 ICB 读不到")
            if (streamFe.fileType != FT_DIR) return fail(onStep, "STREAM 不是目录")
            val streamEntries = readDir(reader, partStart, metaStart, streamFe)

            onStep("查找正片（STREAM 里 ${streamEntries.size} 个文件）")
            // 正片 = STREAM 里体积最大的 m2ts（实测这张盘只有一个：00000.m2ts）
            var best: Video? = null
            var bestBytes = 0L
            for (e in streamEntries) {
                val lower = e.name.lowercase()
                if (!lower.endsWith(".m2ts") && !lower.endsWith(".mts")) continue
                val fe = readFe(reader, partStart, metaStart, e.partRef, e.icbLoc) ?: continue
                if (fe.fileType == FT_DIR || fe.size <= 0 || fe.extents.isEmpty()) continue
                if (fe.size <= bestBytes) continue
                val head = reader.readAt(fe.extents[0].start, 1024)
                val detected = detectPacketSize(head)
                    ?: return fail(onStep, "m2ts 分包结构认不出来（头 ${head.take(8).joinToString(" ") { "%02X".format(it) }}）")
                val (pkt, prefix) = detected
                val total = fe.extents.fold(0L) { acc, x -> acc + x.length }
                val packets = total / pkt
                best = Video(
                    name = e.name,
                    extents = fe.extents,
                    packetSize = pkt,
                    prefixLen = prefix,
                    logicalLength = packets * (pkt - prefix)
                )
                bestBytes = fe.size
            }
            if (best == null) return fail(onStep, "STREAM 里没有可用的 m2ts")
            return best
        } catch (e: Exception) {
            return fail(onStep, "解析中断：${e.message}")
        }
    }

    // ==================== 内部 ====================

    /**
     * 解析失败时把原因交给 [onStep]。
     *
     * 电视上没有截图、日志也不好抓，所以失败原因必须能直接显示在播放页上 ——
     * 一句「解析失败」什么信息都不给，下次只能从头再猜。
     */
    private fun <T> fail(onStep: (String) -> Unit, reason: String): T? {
        Log.w("IsoPlay", "UDF 解析失败：$reason")
        onStep("镜像解析失败：$reason")
        return null
    }

    private class Fe(
        val fileType: Int,
        val size: Long,
        /** 已换算成绝对字节偏移的 extent。 */
        val extents: List<Extent>,
        /** ad_type=3（内联数据）时，内容直接躺在 ICB 里，没有 extent。 */
        val inline: ByteArray?
    )

    private class Fid(val name: String, val partRef: Int, val icbLoc: Long)

    private fun readBlock(
        reader: RawReader, partStart: Long, metaStart: Long, partRef: Int, loc: Long
    ): ByteArray? {
        val abs = if (partRef >= 1 && metaStart > 0) partStart + metaStart + loc * SECTOR
        else partStart + loc * SECTOR
        if (abs < 0) return null
        return try { reader.readAt(abs, SECTOR.toInt()) } catch (e: IOException) { null }
    }

    /**
     * 读一个文件条目（File Entry / Extended File Entry）。
     *
     * @param icbPartRef ICB 自身所在的分区（决定 short_ad 里的块号往哪个分区映射）
     */
    private fun readFe(
        reader: RawReader, partStart: Long, metaStart: Long, icbPartRef: Int, loc: Long
    ): Fe? {
        val blk = readBlock(reader, partStart, metaStart, icbPartRef, loc) ?: return null
        val tag = u16(blk, 0)
        if (tag != TAG_FE && tag != TAG_EFE) return null
        // ICB Tag 从偏移 16 开始，fileType 在它内部第 11 字节 → 偏移 27
        val fileType = blk[27].toInt() and 0xFF
        val size = if (tag == TAG_FE) u64(blk, 56) else u64(blk, 64)
        val eaOff = if (tag == TAG_FE) 168 else 208
        val adType = u16(blk, 34) and 0x07
        val lEaLong = u32(blk, eaOff)
        val lAdLong = u32(blk, eaOff + 4)
        if (lEaLong > SECTOR || lAdLong > SECTOR) return null
        val lEa = lEaLong.toInt()
        val lAd = lAdLong.toInt()
        val start = eaOff + 8 + lEa
        if (start + lAd > blk.size) return null

        if (adType == 3) {
            // 内联数据（很小的目录 / 符号链接）：整段就在 ICB 里，没有 extent
            return Fe(fileType, size, emptyList(), blk.copyOfRange(start, start + lAd))
        }

        // partRef 决定 extent 落在哪个分区：
        //   short_ad 不带分区号 → 跟 ICB 同级
        //   long_ad 自带分区号 → 按号来（正片是 0，落在物理分区）
        val extents = ArrayList<Extent>(8)
        val step = if (adType == 0) 8 else 16
        var k = 0
        while (k + step <= lAd) {
            val lenRaw = u32(blk, start + k)
            val locRaw = u32(blk, start + k + 4)
            if (lenRaw == 0L) break
            val len = lenRaw and 0x3FFF_FFFFL
            var extPartRef = icbPartRef
            if (adType == 1 && k + 10 <= lAd) {
                // long_ad 自带分区号，**它是权威的，0 也算数**：
                //   非 0 → 数据在那个分区
                //   为 0 → 数据在物理分区（正片就是这种：ICB 住在元数据分区 partRef=1，
                //          但 m2ts 实体在物理分区 partRef=0）
                // 早先写成「p != 0 才采用」，于是正片的 extent 被错误地映射到
                // 元数据分区里（983040 而不是 917504），读出来的"头"是 B8 68 88 27…
                // 认不出 TS 同步字，症状是「m2ts 分包结构认不出来」。
                extPartRef = u16(blk, start + k + 8)
            }
            val sector = if (extPartRef >= 1 && metaStart > 0) {
                partStart + metaStart + locRaw * SECTOR
            } else {
                partStart + locRaw * SECTOR
            }
            extents.add(Extent(sector, len))
            k += step
        }
        return Fe(fileType, size, extents, null)
    }

    private fun readDir(reader: RawReader, partStart: Long, metaStart: Long, fe: Fe): List<Fid> {
        val buf = readData(reader, fe) ?: return emptyList()
        val out = ArrayList<Fid>(16)
        var off = 0
        while (off + 38 <= buf.size) {
            if (u16(buf, off) != TAG_FID) { off += 1; continue }
            val nameLen = buf[off + 19].toInt() and 0xFF
            // FID: 20 起是 ICB 的 long_ad（len/loc/partRef）
            val icbLoc = u32(buf, off + 24)
            val icbPref = u16(buf, off + 28)
            val lIu = u16(buf, off + 36).toInt()
            val nameStart = off + 38 + lIu
            if (nameLen > 0 && nameStart + nameLen <= buf.size) {
                out.add(Fid(decodeDstring(buf, nameStart, nameLen), icbPref, icbLoc))
            }
            var rec = 38 + lIu + nameLen
            rec = ((rec + 3) / 4) * 4
            if (rec <= 0) break
            off += rec
        }
        return out
    }

    /**
     * 解 UDF 的 d-string（ECMA-167 3/7.2）。
     *
     * ⚠️ 名字字段的第 1 个字节是**压缩标识**，不是字符本身：
     *   `0x08` → 后面是 8 位字符（ASCII / Latin-1）
     *   `0x10` → 后面是 16 位 UCS-2（两个字节一个字）
     * 直接整段当字符串解会得到 "\bBDMV" 这种前面带控制字符的名字 ——
     * 拿去和 "BDMV" 比较永远不相等，表现就是「盘里有 BDMV 却说没有」。
     * 2026-11-18 模拟器实测：根目录读出来了 3 项，名字分别是 ""、"\u0008BDMV"、"\u0008CERTIFICATE"。
     */
    private fun decodeDstring(buf: ByteArray, start: Int, len: Int): String {
        if (len <= 0) return ""
        val compId = buf[start].toInt() and 0xFF
        return when {
            // 16 位字符：每两个字节一个字
            compId == 16 -> {
                val n = (len - 1) / 2
                val sb = StringBuilder(n)
                for (i in 0 until n) {
                    val p = start + 1 + i * 2
                    if (p + 1 >= buf.size) break
                    sb.append((((buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)).toChar()))
                }
                sb.toString()
            }
            // 8 位字符（压缩标识本身不参与内容）
            compId == 8 -> String(buf, start + 1, len - 1, Charsets.UTF_8)
            // 没有压缩标识（不该出现，防御性处理）
            else -> String(buf, start, len, Charsets.UTF_8)
        }
    }

    /** 把 extent 拼起来读成一段连续数据（目录都很小，几十到几百字节）。 */
    private fun readData(reader: RawReader, fe: Fe): ByteArray? {
        fe.inline?.let { return it }
        val total = minOf(fe.size, 1024L * 1024)
        if (total <= 0) return null
        val buf = ByteArray(total.toInt())
        var written = 0
        for (ext in fe.extents) {
            if (written >= buf.size) break
            val want = minOf(ext.length, (buf.size - written).toLong()).toInt()
            if (want <= 0) continue
            try {
                val data = reader.readAt(ext.start, want)
                val n = minOf(data.size, want)
                System.arraycopy(data, 0, buf, written, n)
                written += n
            } catch (e: IOException) {
                break
            }
        }
        return if (written > 0) buf.copyOfRange(0, written) else null
    }

    /**
     * 判断 m2ts 分包结构。
     *
     * 蓝光 m2ts 每包 192 字节，头 4 字节是 ATS（到达时间戳），之后才是 188 字节 TS
     * （首字节固定 `0x47`）。也有把 m2ts 直接存成 188 的变体，所以两种都认。
     */
    private fun detectPacketSize(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 196) return null
        val sync = 0x47.toByte()
        if (head[4] == sync && head[4 + 192] == sync && head[4 + 384] == sync) return 192 to 4
        if (head[0] == sync && head[188] == sync && head[376] == sync) return 188 to 0
        // 首包不规整（少数盘头部带填充）时按 192 猜一次
        if (head[4] == sync) return 192 to 4
        if (head[0] == sync) return 188 to 0
        return null
    }

    private fun u16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) return 0L
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 8 > b.size) return 0L
        return u32(b, off) or (u32(b, off + 4) shl 32)
    }
}
