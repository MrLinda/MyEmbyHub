package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import work.luxiaoxiao.myembyhub.BuildConfig

/**
 * 在线更新（GitHub Release 为主要来源，国内镜像加速）。
 *
 * ## 流程
 *
 * 1. 取版本清单 `version.json`（每次发版的 Release 里固定有一个同名资产）：
 *    ```json
 *    { "versionCode": 1794903737, "versionName": "1.0.20261117-162217",
 *      "url": "https://github.com/OWNER/REPO/releases/latest/download/myembyhub.apk",
 *      "size": 2165418, "notes": "修复扫码登录" }
 *    ```
 * 2. `versionCode` 比当前 App 大 → 有新版
 * 3. 下载 APK 到 `/sdcard/Download/`
 * 4. 调起系统安装器（这一步仍然需要用户在电视上按一次「安装」——
 *    shell 之外的 uid 都没有 `INSTALL_PACKAGES` 权限，绕不过去）
 *
 * ## 为什么用 `releases/latest/download/<资产名>`
 *
 * 这个 URL **不写 tag**：永远指向「最新一个正式 Release」里同名资产。
 * 好处是清单地址和下载地址都成了**固定值** —— 发新版只上传两个同名资产，
 * 客户端一个字都不用改，也不用先调 GitHub API 查 tag（少一次请求、不受速率限制）。
 *
 * ## 镜像加速
 *
 * 电视在国内，`github.com` / `objects.githubusercontent.com` 经常连不上或者只有几 KB/s。
 * 所以请求按 [MIRROR_PREFIXES] 的顺序依次尝试：
 *
 * ```
 * https://ghfast.top/https://github.com/.../version.json
 * https://gh-proxy.com/https://github.com/.../version.json
 * https://github.com/.../version.json            ← 直连兜底
 * ```
 *
 * 这些前缀站只是「反向代理 + 缓存」，把原始 GitHub 地址原样拼在后面即可。
 * **哪个前缀把清单取回来了，下载 APK 就优先用同一个前缀** ——
 * 免得「清单走 ghfast 通了、APK 又回去直连 GitHub 卡死」。
 *
 * ## 注意
 *
 * - `versionCode` 用秒级时间戳，永远递增 → 不会因为版本号相同而被系统拒绝覆盖安装。
 * - 更新源地址做成**可配置**的（存在 Session 里），换域名 / 换镜像不用重新打包。
 * - 自定义地址（不是 github.com 开头的）**不走镜像**，原样请求 ——
 *   用户自己填的内网静态服务（`python -m http.server`）没有镜像可言。
 */
class UpdateManager(private val ctx: Context) {

    companion object {
        private const val TAG = "Update"
        private const val APK_NAME = "myembyhub-update.apk"

        /** 单个候选的等待时间：电视上等太久不如赶紧换下一个。 */
        private const val CONNECT_TIMEOUT = 6000
        private const val READ_TIMEOUT = 12000

        /** 同一个地址最多试几次（镜像站会中途掐断，重试一次往往就好了）。 */
        private const val DOWNLOAD_ATTEMPTS = 3

        /** 单次下载最多续传几轮（见 [tryDownloadOnce] 的 KDoc）。 */
        private const val MAX_RESUME_ROUNDS = 6

        /**
         * GitHub 加速前缀，**按顺序试**，空串 = 直连。
         *
         * 这类站点来来去去（被封 / 改名 / 限速），所以做成常量列表而不是写死一个：
         * 某个失效了，用户在设置里改 `updateManifestUrl` 或等下一个版本更新列表即可。
         */
        val MIRROR_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "", // 直连 GitHub
        )

        /** 默认清单地址（GitHub Release 的固定资产地址）。 */
        val DEFAULT_MANIFEST: String by lazy {
            // 由 Gradle 烤进来（见 app/build.gradle.kts 的 manifestUrl）：
            // 配了 -PupdateRepo 就是 GitHub 的 latest/download 地址，
            // 没配就是 updateHost 指向的本机静态服务。
            val baked = BuildConfig.UPDATE_MANIFEST
            if (baked.isNotBlank()) baked
            else "https://github.com/OWNER/REPO/releases/latest/download/version.json"
        }

        /**
         * 把一个 GitHub 地址展开成「镜像 + 直连」的候选列表。
         *
         * 非 GitHub 地址原样返回（只有它自己一个候选）。
         */
        fun candidates(url: String): List<String> {
            val trimmed = url.trim()
            if (!isGitHub(trimmed)) return listOf(trimmed)
            return MIRROR_PREFIXES.map { if (it.isEmpty()) trimmed else it + trimmed }
        }

        /** 是不是能套镜像的 GitHub 地址。 */
        private fun isGitHub(url: String): Boolean =
            url.startsWith("https://github.com/") ||
                url.startsWith("https://raw.githubusercontent.com/") ||
                url.startsWith("https://objects.githubusercontent.com/")
    }

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val size: Long,
        val notes: String,
        /** 实际取到清单的地址（镜像是哪个 / 还是直连），用来在界面上说清「从哪儿更新的」。 */
        val from: String = ""
    ) {
        /** 有新版本才返回 true。 */
        fun isNewerThan(current: Long): Boolean = versionCode > current
    }

    private val worker = Executors.newSingleThreadExecutor()

    /**
     * 上一次取清单成功的那个前缀（含末尾斜杠），下载 APK 时优先用它。
     *
     * 只放内存：重启后重新探一遍，反正清单请求本来就要发。
     */
    @Volatile
    private var goodPrefix: String? = null

    /** 检查更新。返回 null 表示已是最新。 */
    fun check(manifestUrl: String): Result<Release?> {
        // ⚠️ 读不到自己的版本号时**不能**当成「有更新」——
        //    否则会永远提示有新版（这正是 BuildConfig 缺失时的症状）。
        val current = currentVersionCode()
        if (current <= 0L) {
            return Result.failure(
                IllegalStateException(
                    "读不到当前版本号（BuildConfig 缺失？）—— 请在 build.gradle.kts 里开启 " +
                        "buildFeatures { buildConfig = true }"
                )
            )
        }

        val list = candidates(manifestUrl.trim().ifEmpty { DEFAULT_MANIFEST })
        var lastError: Throwable? = null
        for (candidate in list) {
            try {
                val text = fetchText(candidate)
                val json = org.json.JSONObject(text)
                val release = Release(
                    versionCode = json.optLong("versionCode", 0),
                    versionName = json.optString("versionName"),
                    url = json.optString("url"),
                    size = json.optLong("size", 0),
                    notes = json.optString("notes"),
                    from = candidate
                )
                if (release.url.isEmpty()) {
                    lastError = IllegalStateException("版本清单里没有下载地址")
                    continue
                }
                // 记住这次成功的镜像，下载 APK 时优先复用
                goodPrefix = prefixOf(candidate, list)
                Log.i(TAG, "清单取自 $candidate")
                return Result.success(release.takeIf { it.isNewerThan(current) })
            } catch (e: Throwable) {
                Log.w(TAG, "取清单失败 $candidate: ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
            }
        }
        return Result.failure(
            lastError ?: IllegalStateException("检查更新失败：所有地址都试过了")
        )
    }

    /** 候选地址里，[candidate] 用的是哪个前缀（直连返回空串）。 */
    private fun prefixOf(candidate: String, candidates: List<String>): String {
        // candidates 与 MIRROR_PREFIXES 一一对应（顺序也一致）
        val idx = candidates.indexOf(candidate)
        return if (idx >= 0 && idx < MIRROR_PREFIXES.size) MIRROR_PREFIXES[idx] else ""
    }

    /**
     * 下载 APK。
     *
     * 依次尝试：上次成功的镜像 → 其余镜像 → 直连。
     *
     * @param onProgress (已下载字节, 总字节)
     * @param onDone 下载完成，参数是 APK 的绝对路径
     */
    fun download(
        release: Release,
        onProgress: (Long, Long) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        worker.execute {
            // ⚠️ API 18 上写外置存储需要 WRITE_EXTERNAL_STORAGE，
            //    没声明的话会抛 FileNotFoundException: (Permission denied)。
            //    而且外置存储本身可能没挂载（模拟器 AVD 上 `/storage/sdcard`
            //    就是 `dr-xr-xr-x` 只读）→ 目录必须**按可用性挑**，不能写死。
            val dir = resolveDownloadDir()
            if (dir == null) {
                onError(
                    "下载失败：没有可写目录。\n" +
                        "外置存储不可用（模拟器常见），应用私有目录也建不出来。"
                )
                return@execute
            }
            Log.i(TAG, "下载目录 ${dir.file.absolutePath}（${dir.source}）")

            val all = candidates(release.url)
            // 上次清单走得通的那个前缀排到最前面
            val ordered = (all.filter { it != release.url && prefixOf(it, all) == goodPrefix } +
                all.filterNot { it != release.url && prefixOf(it, all) == goodPrefix })
                .distinct()

            var lastError: String? = null
            for (url in ordered) {
                val path = tryDownload(url, release, dir.file, onProgress)
                if (path != null) {
                    goodPrefix = prefixOf(url, all)
                    Log.i(TAG, "下载完成 $path")
                    // 落在应用私有目录时，安装器（另一个 uid）默认进不去 → 放开权限
                    if (!dir.publiclyReadable) makeReadableForInstaller(File(path))
                    onDone(path)
                    return@execute
                }
                lastError = "这个地址没成功：$url"
            }
            onError("下载失败：所有地址都试过了\n$lastError")
        }
    }

    /**
     * 下载目录的候选与最终选择。
     *
     * 顺序就是「装得上」的优先级：
     *
     * 1. **外置存储的 Download**（`/sdcard/Download`）——
     *    电视上唯一能让系统安装器读到的地方，也是历史路径。
     * 2. **应用的外部私有目录**（`/sdcard/Android/data/<pkg>/files`）——
     *    外置存储挂载了但 Download 写不进去时用（权限被 ROM 收走的机型）。
     *    安装器仍然读得到，因为它在同一个分区上。
     * 3. **应用内部目录**（`cacheDir`）—— 只在模拟器这种「外置存储整个没挂载」
     *    的环境下兜底，用来验证下载链路本身通不通。
     *    真机上走不到这条路；走到了也装不上，所以 [DownloadDir.publiclyReadable] = false。
     */
    private class DownloadDir(val file: File, val publiclyReadable: Boolean, val source: String)

    private fun resolveDownloadDir(): DownloadDir? {
        // ① /sdcard/Download
        try {
            // 用 Environment API 而不是硬编码 "/sdcard"：
            // 4.3 上外置存储挂载点可能是 /mnt/sdcard 或 /storage/sdcard0
            val ext = android.os.Environment.getExternalStorageDirectory()
            if (ext != null && ext.canWrite()) {
                val dir = File(ext, "Download")
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                    return DownloadDir(dir, true, "外置存储 Download")
                }
            }
        } catch (ignored: Throwable) {
        }

        // ② /sdcard/Android/data/<pkg>/files
        try {
            val ext = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (ext != null && (ext.exists() || ext.mkdirs()) && ext.canWrite()) {
                return DownloadDir(ext, true, "应用外部私有目录")
            }
        } catch (ignored: Throwable) {
        }

        // ③ 应用内部目录（模拟器兜底）
        //    用 filesDir 而不是 cacheDir：cache 会被系统在低存储时清掉，
        //    而这份 APK 是要交给安装器的，不能半路消失。
        try {
            val dir = File(ctx.filesDir, "update")
            if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                return DownloadDir(dir, false, "应用内部目录")
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /**
     * 把内部目录里的 APK 放开给别的 uid 读。
     *
     * `/data/data/<pkg>/` 默认是 700（只有本 app 的 uid 进得去），
     * 而系统安装器是另一个 uid —— 不放开就报「解析包时出现问题」。
     *
     * 4.3 上可以这样干：目录 chmod 0755、文件 chmod 0644，
     * 安装器（uid system）就能顺着路径读到文件。
     * 这也正是「内部目录兜底」能真正装上包的原因。
     *
     * ⚠️ 只对 [DownloadDir.publiclyReadable] = false 的情况调用；
     *    外置存储上的文件本来就是全局可读的。
     */
    private fun makeReadableForInstaller(file: File) {
        try {
            file.setReadable(true, false)
            // 目录要能被「穿过」，光设文件权限不够
            file.parentFile?.setExecutable(true, false)
            file.parentFile?.setReadable(true, false)
            // 上层 filesDir 也要能穿过（/data/data/<pkg> 本身还是 700，
            // 但 filesDir 可以单独放开）
            file.parentFile?.parentFile?.setExecutable(true, false)
            file.parentFile?.parentFile?.setReadable(true, false)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 从单个地址下载；成功返回文件路径，失败返回 null（接着试下一个）。
     *
     * 内部会重试 [DOWNLOAD_ATTEMPTS] 次。镜像站限速时会把连接掐断，
     * 重来一次往往就好了。
     */
    private fun tryDownload(
        url: String,
        release: Release,
        dir: File,
        onProgress: (Long, Long) -> Unit
    ): String? {
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            val path = tryDownloadOnce(url, release, dir, onProgress)
            if (path != null) return path
            if (attempt < DOWNLOAD_ATTEMPTS - 1) {
                Log.w(TAG, "第 ${attempt + 1} 次没成功，重试：$url")
                try { Thread.sleep(400) } catch (ignored: InterruptedException) {}
            }
        }
        return null
    }

    /**
     * 从单个地址下载，**支持断点续传**。
     *
     * 为什么必须续传（两条实测证据）：
     *
     * ① **镜像站限速**：ghfast / gh-proxy 这类站传到一半会把连接掐掉，
     *    重头下 2MB 是纯浪费。
     * ② **模拟器的用户态网络会丢尾巴**：QEMU 的 SLIRP 在服务端 `close()` 时，
     *    如果接收缓冲里还有没被读走的数据，会直接发 RST 把它扔掉。
     *    实测日志：`读流中断：已读 2173770 / 2173772 字节` —— **只差 2 个字节**，
     *    但 Android 的 `FixedLengthInputStream` 认为这是
     *    `IOException: unexpected end of stream`，整个下载被判失败。
     *    换成 `Range: bytes=2173770-` 再要一次，2 个字节就到了。
     *
     * 服务器不认 `Range`（回 200 而不是 206）时退化成重头下载 ——
     * GitHub 的 `objects.githubusercontent.com` 和各家镜像都支持 206，走不到那条路。
     */
    private fun tryDownloadOnce(
        url: String,
        release: Release,
        dir: File,
        onProgress: (Long, Long) -> Unit
    ): String? {
        val target = File(dir, APK_NAME)
        var downloaded = 0L
        var total = release.size
        var round = 0

        while (round < MAX_RESUME_ROUNDS) {
            round++
            var conn: HttpURLConnection? = null
            try {
                Log.i(TAG, "开始下载 $url（第 $round 轮，已有 $downloaded 字节）")
                conn = openHttp(url)
                if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                val code = conn.responseCode
                if (code != 200 && code != 206) {
                    Log.w(TAG, "下载 HTTP $code: $url")
                    return null
                }
                if (code == 200 && downloaded > 0) {
                    // 服务器不认 Range → 只能从头来
                    Log.i(TAG, "服务器不支持续传（回了 200），从头下载")
                    downloaded = 0L
                }
                val len = conn.contentLength.toLong()
                if (len > 0) total = if (code == 206) downloaded + len else len
                if (total > 0 && downloaded >= total) {
                    target.setReadable(true, false)
                    return target.absolutePath
                }

                var broken = false
                try {
                    conn.inputStream.use { input ->
                        // append = true 时接着上次的位置写
                        java.io.FileOutputStream(target, downloaded > 0).use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                onProgress(downloaded, total)
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    // 断在半路是「正常」的（见 KDoc），交给下一轮续传
                    broken = true
                    Log.w(TAG, "读流中断：已读 $downloaded / $total 字节（${e.message}）")
                }
                if (!broken) Log.i(TAG, "读流结束：共 $downloaded / $total 字节")

                // 长度已知就比字节数；长度未知时「没断过」即视为完整
                val complete = if (total > 0) downloaded >= total else !broken
                if (complete) {
                    target.setReadable(true, false)
                    return target.absolutePath
                }
                // 一个字节都没拿到，说明这个地址根本不通，别在这儿耗
                if (downloaded == 0L) return null
            } catch (e: Throwable) {
                Log.w(TAG, "下载异常 $url: ${e.javaClass.simpleName}: ${e.message}")
                if (downloaded == 0L) return null
            } finally {
                try { conn?.disconnect() } catch (ignored: Throwable) {}
            }
        }
        Log.w(TAG, "续传 $MAX_RESUME_ROUNDS 轮仍未拿到完整文件：$url")
        return null
    }

    /**
     * 打开一个 HTTP(S) 连接，**TLS 走本 App 的栈**。
     *
     * ⚠️ 不能用裸 `URL.openConnection()`：Android 4.3 默认只启用 TLS 1.0，
     *    而 GitHub / 各家镜像站都只收 TLS 1.2+，表现是
     *    `SSL handshake aborted` 或者直接 `handshake_failure`。
     *
     * 信任模式用 [TrustMode.ALL]：镜像站的证书是五花八门的 Let's Encrypt /
     * ZeroSSL / 自签，内置锚点覆盖不全，硬校验只会让更新链路时通时不通。
     * 这条链路**不传任何凭据**（只有一个公开的 JSON 和 APK），
     * 而下载下来的 APK 还要过系统安装器的签名校验 ——
     * 签名对不上的包**装不上**，所以这里放宽证书不会让人顶掉本 App。
     */
    private fun openHttp(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = TlsStack.socketFactory(ctx, false, TrustMode.ALL)
                ?: throw java.io.IOException("无法构造 TLS socket factory")
        }
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        // ⚠️ 下载大文件必须显式关掉连接复用。
        //    Android 4.3 的连接池会把「上一条请求的 socket」交给下一个请求，
        //    而清单和 APK 往往打的是同一个 host —— 池子里那条已经半关，
        //    读起来就是 `IOException: unexpected end of stream`（实测踩过）。
        //    一次性下载不需要复用，直接 close 最省事。
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty("User-Agent", "MyEmbyHub/${currentVersionName()}")
        return conn
    }

    /** 取一段文本（清单用）。 */
    private fun fetchText(url: String): String {
        val conn = openHttp(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            // 一次性的小请求，读完就断，别把连接池占着
            try { conn.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /** 调起系统安装器装 APK。 */
    fun install(apkPath: String): Boolean {
        return try {
            val file = File(apkPath)
            if (!file.exists()) return false
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "调起安装器失败", e)
            false
        }
    }

    /**
     * 当前版本号（秒级时间戳）。
     *
     * 两级来源：
     *
     * 1. `BuildConfig.VERSION_CODE`（编译期常量，最快）；
     * 2. **装到系统里的那个 APK 的 versionCode**（`PackageManager`）。
     *
     * ② 是兜底，也是**更可信**的那个 —— 它读的就是系统记录的「当前装的到底是什么」。
     * ① 一旦失效（AGP 8.0 起 BuildConfig 不再自动生成、或 R8 把字段优化掉），
     * 以前会兜底返回 0，于是**任何版本都比 0 大 → 永远提示「有新版本」**，
     * 而且装完再检查还是提示，看起来像更新坏了。
     */
    fun currentVersionCode(): Long {
        try {
            val cls = Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
            val f = cls.getField("VERSION_CODE")
            return (f.get(null) as Int).toLong()
        } catch (e: Throwable) {
            // ⚠️ AGP 8.0 起 BuildConfig 不再自动生成，
            //    忘了 `buildFeatures { buildConfig = true }` 就会走到这里。
            Log.w(TAG, "读 BuildConfig.VERSION_CODE 失败，改用 PackageManager", e)
        }
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong()
        } catch (e: Throwable) {
            Log.e(TAG, "读已安装 APK 的 versionCode 也失败", e)
            0L
        }
    }

    fun currentVersionName(): String {
        try {
            val cls = Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
            val f = cls.getField("VERSION_NAME")
            return f.get(null) as String
        } catch (e: Throwable) {
            Log.w(TAG, "读 BuildConfig.VERSION_NAME 失败，改用 PackageManager", e)
        }
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Throwable) {
            ""
        }
    }
}