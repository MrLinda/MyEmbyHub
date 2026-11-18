package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/**
 * 极简图片加载器（海报墙用）。
 *
 * ## 为什么不用 Glide / Picasso
 *
 * - 这台电视**应用堆只有约 96MB**，Glide 加上它那一堆模块很占地方；
 * - Glide 最低也支持 API 14，但它的磁盘缓存、生命周期绑定、变换链
 *   在这里全是用不上的复杂度。
 * - 自己写就三件事：**内存 LRU + 磁盘缓存 + 一个线程池**，够了。
 *
 * ## 关键约束
 *
 * - Bitmap 强制 `RGB_565`（不带 alpha），**内存直接减半**。
 * - 服务端就按显示尺寸出图（`maxWidth` / `maxHeight`），
 *   绝不下原图再缩 —— 300MB 内存扛不住一张 4K 原图。
 * - 图片接口在 `tv.example.com` 上，走 HTTPS → **必须挂 TLS1.2 的 socket factory**，
 *   否则和播放一样的老问题。factory 由 [TlsStack] 缓存复用，见下。
 *
 * ## 拉图慢的三处修复（2026-11-04）
 *
 * 1. **复用 [TlsStack] 的 socket factory** —— 之前每张图都新建 `SSLContext`，
 *    等于每次都重新初始化系统 CA 库，且丢掉 TLS 会话缓存 → 每张图完整握手。
 * 2. **不调 `conn.disconnect()`** —— 它会把 socket 从连接池移除。
 *    只关输入流，HttpURLConnection 就会把 TCP+TLS 连接还回池子复用。
 * 3. **磁盘缓存存原始 JPEG 字节** —— 之前是 decode → 再 compress(q85) → 下次再 decode，
 *    白烧一次编码（1GHz ARM 上很贵）还二次有损。
 */
class ImageLoader private constructor(private val ctx: Context) {

    companion object {
        private const val TAG = "ImageLoader"

        @Volatile
        private var instance: ImageLoader? = null

        fun get(ctx: Context): ImageLoader =
            instance ?: synchronized(this) {
                instance ?: ImageLoader(ctx.applicationContext).also { instance = it }
            }

        /** 磁盘缓存目录。 */
        private const val CACHE_DIR = "emby_images"

        /** 磁盘缓存上限。电视内外各约 3.3GB 可用，50MB 不算负担。 */
        private const val DISK_CACHE_BYTES = 50L * 1024 * 1024

        /** 内存缓存上限：可用堆的 1/8，最多 24MB。 */
        private fun memCacheSize(): Int {
            val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            return (max / 8).coerceIn(4 * 1024, 24 * 1024)
        }

        /**
         * 「确认没图」的 key 最多记多少条。
         *
         * 浏览大库会累积到几万条；在 96MB 堆的机器上这是白占。
         * 超了整体清空 —— 最坏只是多打几次 404，可接受。
         */
        private const val FAILED_MAX = 4000
    }

    /** 回调。bitmap 为 null 表示没有图或加载失败。 */
    fun interface Callback {
        fun onBitmap(bitmap: Bitmap?)
    }

    /**
     * 一次取图请求的句柄。
     *
     * 列表滚动时 ViewHolder 会被回收复用，此时旧请求的回调若还照常执行，
     * 就会把**上一条目**的海报贴到**新条目**上（经典错位 bug）。
     * 绑定新内容前先 [cancel] 掉旧请求，回调就不会再被触发。
     */
    class Request internal constructor(private val key: String) {
        @Volatile
        internal var cancelled = false
            private set

        /** 取消。已取消的请求不会再触发回调，也不会占用线程池继续下载。 */
        fun cancel() {
            cancelled = true
        }

        internal fun cancelledFor(key: String): Boolean = cancelled && this.key == key
    }

    /** 一张图的解码结果 + 原始字节（原始字节直接进磁盘缓存）。 */
    private class Fetched(val bitmap: Bitmap, val bytes: ByteArray)

    private val main = Handler(Looper.getMainLooper())

    private val memCache = object : LruCache<String, Bitmap>(memCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val pool = Executors.newFixedThreadPool(3) as ThreadPoolExecutor

    /**
     * 取图失败的 key（该条目确实没有这张图）→ 不重复请求，避免滚动时反复打服务器。
     *
     * ⚠️ **必须有上限**：浏览大库时会累积到几万条，HashSet 本身不大，
     * 但在 96MB 堆的机器上也是白占。超过 [FAILED_MAX] 就整体清空重来
     * （最坏情况只是多打几次 404，可接受）。
     */
    private val failed = HashSet<String>()

    private val diskDir: File by lazy {
        File(ctx.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
    }

    // ---------- 磁盘缓存的状态 ----------
    // 访问顺序的 LinkedHashMap（文件名 → 字节数），命中/写入都挪到队尾，淘汰从队头开始。

    private val diskLock = Any()
    private val diskLru = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var diskBytes = 0L
    private var diskScanned = false

    /**
     * 取一张海报。
     *
     * @param itemId Emby 条目 id
     * @param imageType 图片类型，`Primary` 是海报，`Backdrop` 是横版背景图
     * @param targetWidth 期望宽度（服务端据此出图）
     * @param forceRefresh 忽略缓存
     * @return 句柄，调用方在 ViewHolder 复用时 [Request.cancel] 掉即可
     */
    fun load(
        itemId: String,
        imageType: String = "Primary",
        targetWidth: Int = 320,
        forceRefresh: Boolean = false,
        callback: Callback
    ): Request {
        val key = "$imageType:$itemId:$targetWidth"
        val req = Request(key)
        if (itemId.isEmpty()) {
            callback.onBitmap(null)
            return req
        }

        if (!forceRefresh) {
            memCache.get(key)?.let {
                callback.onBitmap(it)
                return req
            }
            // 已经确认没有这张图 → 直接返回，不再打服务器
            if (synchronized(failed) { failed.contains(key) }) {
                callback.onBitmap(null)
                return req
            }
        }

        pool.execute {
            // 排队期间可能已经被取消（用户已经滚走了），没必要再占网络和 CPU
            if (req.cancelled) return@execute
            // 进线程池再查一次：同一张图可能在排队期间已经被别的线程填好了
            var bmp = if (forceRefresh) null else memCache.get(key)
            if (bmp == null) {
                bmp = loadFromDisk(key)
                if (bmp != null) {
                    memCache.put(key, bmp)
                } else {
                    if (req.cancelled) return@execute
                    val fetched = fetch(itemId, imageType, targetWidth)
                    if (fetched != null) {
                        bmp = fetched.bitmap
                        memCache.put(key, fetched.bitmap)
                        saveToDisk(key, fetched.bytes)
                    }
                }
            }
            val result = bmp
            if (result == null) {
                synchronized(failed) {
                    if (failed.size >= FAILED_MAX) failed.clear()
                    failed.add(key)
                }
            }
            main.post { if (!req.cancelled) callback.onBitmap(result) }
        }
        return req
    }

    /** 取横版背景图（详情页用）。 */
    fun loadBackdrop(itemId: String, targetWidth: Int = 1280, callback: Callback): Request =
        load(itemId, "Backdrop", targetWidth, false, callback)

    // ==================== 内部 ====================

    private fun fetch(itemId: String, imageType: String, targetWidth: Int): Fetched? {
        val server = Session.get(ctx).server
        if (server.isEmpty()) return null
        // 服务端按显示尺寸出图，避免下原图
        val url = "$server/Items/$itemId/Images/$imageType?maxWidth=$targetWidth&quality=90&fillWidth=$targetWidth"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("Accept", "image/*")
                setRequestProperty("X-Emby-Token", Session.get(ctx).token)
                if (this is javax.net.ssl.HttpsURLConnection) {
                    // ⚠️ 同一个坑：必须抬到 TLS1.2，否则连不上（factory 已由 TlsStack 缓存复用）
                    sslSocketFactory = TlsStack.socketFactory(ctx, TrustMode.BUNDLED)
                        ?: return null
                }
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "取图失败 HTTP $code: $url")
                null
            } else {
                val bytes = conn.inputStream.use { readAll(it) }
                decodeScaled(bytes, targetWidth)?.let { Fetched(it, bytes) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "取图异常: ${e.message}")
            null
        }
        // ⚠️ 这里**不要** conn.disconnect()：它会把连接从池子里摘掉，
        //    之后的图又得重新 TCP + TLS 握手。读完流就能复用。
    }

    /** 解码成缩放后的 RGB_565 位图。 */
    private fun decodeScaled(bytes: ByteArray, targetWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            // ⚠️ 判据是 `> targetWidth`（不是 `> targetWidth * 2`）。
            //    inSampleSize 只能取 2 的幂，停在前一档意味着实际解码宽度
            //    最多是 targetWidth 的 2 倍；按 `*2` 判会再多解一档，
            //    内存直接翻 4 倍 —— 海报墙上几十张图叠加，很容易触发 GC 抖动。
            //    解码出来的宽度仍然 ≥ 目标宽度，肉眼看不出差别。
            var sample = 1
            while (opts.outWidth / sample > targetWidth && sample < 16) {
                sample *= 2
            }
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
        } catch (e: Throwable) {
            Log.w(TAG, "解码图片失败", e)
            null
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(32 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---------- 磁盘缓存 ----------

    private fun diskFile(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(diskDir, safe + ".jpg")
    }

    /** 首次读写时扫一遍目录，把已有文件登记进 LRU（老的排前面，优先被淘汰）。 */
    private fun scanDiskOnce() {
        synchronized(diskLock) {
            if (diskScanned) return
            diskScanned = true
            val files = diskDir.listFiles() ?: return
            files.filter { it.isFile }
                .sortedBy { it.lastModified() }
                .forEach {
                    diskLru[it.name] = it.length()
                    diskBytes += it.length()
                }
            trimLocked(null)
        }
    }

    private fun loadFromDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists() || f.length() == 0L) return null
        scanDiskOnce()
        val bmp = try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: Throwable) {
            null
        }
        if (bmp == null) {
            // 坏文件（下载中断/写了一半）→ 删掉，下次重新下
            f.delete()
            synchronized(diskLock) { diskLru.remove(f.name)?.let { diskBytes -= it } }
            return null
        }
        synchronized(diskLock) { if (diskLru.containsKey(f.name)) diskLru[f.name] }
        return bmp
    }

    private fun saveToDisk(key: String, bytes: ByteArray) {
        scanDiskOnce()
        val f = diskFile(key)
        try {
            FileOutputStream(f).use { it.write(bytes) }
        } catch (e: Throwable) {
            // 磁盘缓存失败无所谓
            return
        }
        synchronized(diskLock) {
            diskLru.remove(f.name)?.let { diskBytes -= it }
            diskLru[f.name] = bytes.size.toLong()
            diskBytes += bytes.size
            trimLocked(f.name)
        }
    }

    /** 超出上限就从最久没用的开始删。调用方必须持有 [diskLock]。 */
    private fun trimLocked(keep: String?) {
        if (diskBytes <= DISK_CACHE_BYTES) return
        val it = diskLru.entries.iterator()
        while (diskBytes > DISK_CACHE_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key == keep) continue
            try { File(diskDir, e.key).delete() } catch (ignored: Throwable) {}
            diskBytes -= e.value
            it.remove()
        }
    }

    /** 清空缓存（菜单里可以加一项）。 */
    fun clearCache() {
        memCache.evictAll()
        synchronized(diskLock) {
            diskLru.clear()
            diskBytes = 0
            diskScanned = true
        }
        try {
            diskDir.listFiles()?.forEach { it.delete() }
        } catch (e: Throwable) {
        }
    }
}
