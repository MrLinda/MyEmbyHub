package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
 *    { "versionCode": 1794918005, "versionName": "1.0.20261117.2",
 *      "url": "https://github.com/OWNER/REPO/releases/latest/download/myembyhub.apk",
 *      "size": 2174365, "notes": "修复扫码登录" }
 *    ```
 * 2. `versionCode` 比当前 App 大 → 有新版
 * 3. 下载 APK 到「安装器读得到、本 App 写得进」的目录（见 [resolveDownloadDir]：
 *    外部私有目录 → `/sdcard/Download` → `filesDir/update` 三选一）
 * 4. 调起系统安装器：API 24+ 用 FileProvider 的 `content://`（`file://` 会抛
 *    FileUriExposedException）；API 18~23 只能用 `file://`
 *    （4.3 的安装器不接 `content://`），所以老系统上要求 APK 落在共享存储上
 *    （这一步仍然需要用户在电视上按一次「安装」——
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

        /**
         * 安装器认的 MIME。
         *
         * ⚠️ 这个字符串必须和 AndroidManifest.xml 里 `<queries>` 那段的
         *    `<data android:mimeType="…" />` **逐字一致** —— Android 11+ 收紧包可见性后，
         *    这里换个写法（比如 `application/vnd.android.package-archive` 写成
         *    `application/octet-stream`）系统就找不到安装器，`startActivity` 抛
         *    ActivityNotFoundException。
         */
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** 没拿到「安装未知应用」授权时的提示（跳设置页以后本 App 就不在前台了，只能靠 Toast）。 */
        private const val UNKNOWN_SOURCES_HINT =
            "需要在「安装未知应用」里允许本应用安装，允许后返回再点一次更新"

        /**
         * 老系统 + 外置存储不可用时的提示。
         *
         * Android 4.4 及以下的 `PackageInstallerActivity` 只声明了 `scheme="file"`，
         * 拿不到共享存储上的路径就没法把包交给它 —— 换 `content://` 会直接
         * `ActivityNotFoundException`（实测）。这种情况只会出现在外置存储没挂载的机器上。
         */
        private const val OLD_SYSTEM_NO_STORAGE_HINT =
            "这个安装包在应用私有目录里，Android 4.4 及以下的系统安装器读不到。请检查 SD 卡 / 外置存储后重试"

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
            // 目录只要求「本 App 能写」—— 能不能装上取决于 Uri 怎么给（见 install），
            // 不取决于落在哪个分区。所以这里不再挑 /sdcard/Download。
            val dir = resolveDownloadDir()
            if (dir == null) {
                onError(
                    "下载失败：没有可写目录。\n" +
                        "外置存储不可用，应用私有目录也建不出来。"
                )
                return@execute
            }
            Log.i(TAG, "下载目录 ${dir.absolutePath}")

            // ⚠️ API < 24 的安装器只吃 `file://`（见 [apkUri]），而它是另一个 uid，
            //    顺着路径读不到应用私有目录（0700）。落到那儿的结果是
            //    「下载完整、安装器弹一句 Parse error」—— 用户完全看不出原因。
            //    与其让他拿到一个装不上的包，不如现在就停下来说清楚。
            if (android.os.Build.VERSION.SDK_INT < 24 && !isOnSharedStorage(dir)) {
                Log.e(TAG, "外置存储不可用，老系统的安装器读不到应用私有目录：${dir.absolutePath}")
                onError(
                    "更新需要外置存储。\n" +
                        "这台机器上没有可写的外部存储（SD 卡 / 内置共享分区），" +
                        "安装包只能放在应用私有目录里，" +
                        "而 Android 4.4 及以下的系统安装器读不到那个位置。"
                )
                return@execute
            }

            val all = candidates(release.url)
            // 上次清单走得通的那个前缀排到最前面
            val ordered = (all.filter { it != release.url && prefixOf(it, all) == goodPrefix } +
                all.filterNot { it != release.url && prefixOf(it, all) == goodPrefix })
                .distinct()

            var lastError: String? = null
            for (url in ordered) {
                val path = tryDownload(url, release, dir, onProgress)
                if (path != null) {
                    goodPrefix = prefixOf(url, all)
                    Log.i(TAG, "下载完成 $path")
                    onDone(path)
                    return@execute
                }
                lastError = "这个地址没成功：$url"
            }
            onError("下载失败：所有地址都试过了\n$lastError")
        }
    }

    /**
     * 下载目录：三段，按「安装器能不能读到」排序 —— 不是按「本 App 能不能写」。
     *
     * ⚠️ 这是实测踩出来的，两条都成立才算装得上：
     *
     * 1. **安装器是另一个 uid**（`com.android.packageinstaller`），它要自己
     *    `openInputStream` 那个 APK。放在 `/data/data/<pkg>/` 里的文件它根本
     *    进不去那个目录（0700），chmod 单个文件也没用。
     * 2. 所以「换个 Uri」救不了「文件放错分区」—— 两者是**并列**条件。
     *
     * 实测（API 18 模拟器，外置存储未挂载 → 落到内部目录）：安装器弹
     * `Parse error / There was a problem parsing the package`，
     * 而文件字节数、签名全部正确 —— **从界面上完全看不出是权限问题**。
     *
     * - ① `getExternalFilesDir(DIRECTORY_DOWNLOADS)`：新系统首选。
     *   API 19 起免任何权限；Android 11+ 虽然用 FUSE 隔离了
     *   `/sdcard/Android/data/<pkg>/`，但那条路交给安装器的是 `content://`
     *   （见 [apkUri]），字节由本 App 自己的 FileProvider 供，不需要路径权限。
     * - ② `/sdcard/Download`：**老系统**的历史路径，只在 API < 29 上试。
     *   4.3 真机（乐视 S50 Air）的在线更新一直这么走、验证过无数次；留着它，
     *   某个 OEM 把 `getExternalFilesDir()` 弄坏时老电视还有退路。
     *   分区存储那套规则从 API 29 才生效，targetSdk 升到 34 不影响 4.3 上的这条老路。
     * - ③ `filesDir/update`：外置存储整个没挂载时的最后兜底，此时只能走
     *   `content://`。用 filesDir 而不是 cacheDir —— cache 会被系统在低存储时
     *   清掉，而这份 APK 要交给安装器，不能半路消失。
     *
     * 三段都失败才返回 null（[download] 会直接报「没有可写目录」）。
     */
    private fun resolveDownloadDir(): File? {
        // ① 外部私有目录
        try {
            val ext = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (ext != null && (ext.exists() || ext.mkdirs()) && ext.canWrite()) return ext
        } catch (ignored: Throwable) {
        }

        // ② 老的 /sdcard/Download（分区存储时代写不进去，所以只对老系统试）
        if (android.os.Build.VERSION.SDK_INT < 29) {
            try {
                @Suppress("DEPRECATION")
                val root = android.os.Environment.getExternalStorageDirectory()
                val dir = File(root, "Download")
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) return dir
            } catch (ignored: Throwable) {
            }
        }

        // ③ 内部私有目录：只有外置存储整个不可用时才落到这里（API 18 模拟器就是这种）
        try {
            val dir = File(ctx.filesDir, "update")
            if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) return dir
        } catch (ignored: Throwable) {
        }
        return null
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
        // 大文件动辄几十 MB，每轮续传都重新握手，所以先把栈选好复用；
        // 万一这一轮握手谈不拢，就换栈接着下（已下载的字节不用重来）。
        val host = hostOf(url)
        var choice = if (host.isNotEmpty()) TlsStack.chooseFor(ctx, TrustMode.ALL, host) else null
        var escalated = false

        while (round < MAX_RESUME_ROUNDS) {
            round++
            var conn: HttpURLConnection? = null
            try {
                Log.i(TAG, "开始下载 $url（第 $round 轮，已有 $downloaded 字节）")
                conn = openHttp(url, choice?.factory)
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
                    if (choice != null && host.isNotEmpty()) {
                        TlsStack.noteSuccess(ctx, host, choice!!.impl)
                    }
                    // 系统安装器是**另一个 uid**，`FileOutputStream` 建出来的文件默认
                    // 0600（实测 API 18 模拟器：`-rw------- u0_a46 u0_a46`）→ 安装器
                    // 打不开，只报一句 "Parse error"，看起来像下载坏了。
                    // 老版本靠 chmod 0644 解决；这里用公开 API 做同一件事，
                    // 代价为零，留着能省掉一类「下载完成但装不上」的玄学故障。
                    try { target.setReadable(true, false) } catch (ignored: Throwable) {}
                    return target.absolutePath
                }
                // 一个字节都没拿到，说明这个地址根本不通，别在这儿耗
                if (downloaded == 0L) return null
            } catch (e: Throwable) {
                Log.w(TAG, "下载异常 $url: ${e.javaClass.simpleName}: ${e.message}")
                // 握手谈不拢（只开 GCM 的镜像站）+ 还没换过栈 → 换一个栈接着下。
                // ⚠️ 只换握手失败，证书/超时/DNS 换了也没用（见 TlsStack.isHandshakeFailure）。
                if (!escalated && TlsStack.isHandshakeFailure(e)) {
                    val failed = choice?.impl ?: TlsStack.choose(ctx, TrustMode.ALL)?.impl
                    val alt = failed?.let { TlsStack.escalate(ctx, TrustMode.ALL, it) }
                    if (alt != null) {
                        escalated = true
                        choice = alt
                        Log.w(TAG, "握手失败，换 TLS 栈重试：$failed → ${alt.impl}")
                    }
                }
                if (downloaded == 0L) {
                    // 一个字节都没拿到通常是握手/地址问题，换栈这一次机会已经给了，
                    // 再空转一轮没意义 —— 但若刚换过栈，得让下一轮真的试一次。
                    if (!escalated) return null
                }
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
    private fun openHttp(url: String, factory: javax.net.ssl.SSLSocketFactory? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = factory
                ?: TlsStack.socketFactory(ctx, TrustMode.ALL)
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

    /** 取一段文本（清单用）。握手谈不拢就换栈再试一次（见 [TlsConn]）。 */
    private fun fetchText(url: String): String {
        val host = hostOf(url)
        if (!url.startsWith("https", ignoreCase = true) || host.isEmpty()) {
            return fetchTextOnce(url, null)
        }
        return TlsConn.withRetry(ctx, TrustMode.ALL, host) { factory -> fetchTextOnce(url, factory) }
    }

    private fun fetchTextOnce(url: String, factory: javax.net.ssl.SSLSocketFactory?): String {
        val conn = openHttp(url, factory)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            // 一次性的小请求，读完就断，别把连接池占着
            try { conn.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /** 从 URL 里取主机名；解析不出来返回空串（调用方据此跳过换栈逻辑）。 */
    private fun hostOf(url: String): String = try {
        UrlKit.parse(url).host
    } catch (ignored: Throwable) {
        ""
    }

    /**
     * 调起系统安装器装 APK。
     *
     * ## 两条 URI 路径，按 SDK_INT 分
     *
     * - **API 24+**：`file://` 一旦跨进程传给安装器，系统直接抛
     *   `FileUriExposedException`（这是 targetSdk ≥ 24 的行为，跟设备版本无关）。
     *   而且 Android 11+ 还把 `/sdcard/Android/data/<pkg>/` 用 FUSE 隔离了 ——
     *   安装器（另一个 uid）顺着 `file://` 路径也读不到文件。
     *   → 换成 FileProvider 的 `content://` + 临时读授权，两个问题一起解决。
     * - **API 18~23**：`file://`。这条路在乐视 4.3 上**已经真机跑通过**，
     *   而且 4.3 的安装器在 intent-filter 里只声明了 `scheme="file"` ——
     *   给它 `content://` 会 `ActivityNotFoundException`（实测）。
     *   代价：文件必须在共享存储上，否则安装器读不到（只回一句 `Parse error`）。
     *   这种组合只在外置存储整个不可用时出现，[download] 会提前拦下并说明原因。
     *
     * ## 「安装未知应用」授权（API 26+）
     *
     * Android 8.0 起，**调用方**（也就是本 App）自己要拿到
     * `REQUEST_INSTALL_PACKAGES` 并让用户在「安装未知应用」里放行。
     * 没放行时 `startActivity` 不报错、安装器也不弹 —— 用户看到的就是
     * 「下载完成」之后什么都没发生，非常难排查。所以先查一次，没放行就
     * 把用户送到设置页（Toast 说明原因，因为设置页跳过去以后本 App 就不在前台了）。
     *
     * @return true = 已处理好（安装器已拉起，或已把用户送去授权页）
     */
    fun install(apkPath: String): Boolean {
        return try {
            val file = File(apkPath)
            if (!file.exists()) {
                Log.e(TAG, "APK 不存在：$apkPath")
                return false
            }
            if (needsUnknownSourcesGrant()) {
                Log.w(TAG, "还没拿到「安装未知应用」授权，跳设置页")
                openUnknownSourcesSettings()
                return true
            }
            // 老系统 + 包在应用私有目录 → 安装器必然只回一句 Parse error（它读不到）。
            // 这种组合只在外置存储整个不可用时才会出现，说清楚比让用户猜「包坏了」好。
            if (android.os.Build.VERSION.SDK_INT < 24 && !isOnSharedStorage(file)) {
                Log.e(TAG, "安装包在应用私有目录，老系统的安装器读不到：${file.absolutePath}")
                Toast.makeText(ctx, OLD_SYSTEM_NO_STORAGE_HINT, Toast.LENGTH_LONG).show()
                return false
            }
            val uri = apkUri(file)
            Log.i(TAG, "调起安装器 ${uri.scheme}://… ${file.absolutePath}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // content:// 必须显式授权，否则安装器 openInputStream 时 SecurityException
                if (uri.scheme == "content") addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "调起安装器失败", e)
            false
        }
    }

    /**
     * 把本地 APK 包成能跨进程递出去的 Uri。
     *
     * ⚠️ authorities 必须和 AndroidManifest 里 FileProvider 的 `${applicationId}.fileprovider`
     *    完全一致，路径白名单（res/xml/file_paths.xml）必须覆盖 [resolveDownloadDir]
     *    返回的两个目录。对不上时 `getUriForFile` 抛
     *    `IllegalArgumentException: Failed to find configured root that contains ...`，
     *    只在真机上才炸。
     */
    private fun apkUri(file: File): Uri {
        // API < 24：**只能** `file://`。乐视 4.3 上这条路真机验证过无数次，
        // 而且 4.3 的 `PackageInstallerActivity` 在 intent-filter 里只声明了
        // `scheme="file"` —— 给它 `content://` 会直接
        // `ActivityNotFoundException: No Activity found to handle Intent{… dat=content://…}`，
        // 安装器压根不会出现（实测，哪怕 Uri 授权标志都带上）。
        // 代价是：文件必须落在共享存储上（见 [isOnSharedStorage] 与 [install] 的检查）。
        if (android.os.Build.VERSION.SDK_INT < 24) return Uri.fromFile(file)
        return androidx.core.content.FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file
        )
    }

    /**
     * 文件是否在「另一个进程顺着路径就能读」的共享存储上。
     *
     * 只用来判断 4.3 / 4.4 上 `file://` 能不能把包交给安装器，**不是**权限判断。
     *
     * 为什么需要这个判断：安装器是另一个 uid，跟着 `file://` 去
     * `/data/data/<pkg>/files/…` 是进不去的（目录 0700），它只会回一句
     * `Parse error when parsing manifest` —— 界面上看不出是权限问题。
     * 而 4.3 又不接 `content://`（见 [apkUri]），所以在那种机器上唯一的出路
     * 就是「别把 APK 放在内部目录」，也就是在 [download] 阶段就停下来说明原因。
     *
     * 认 `/storage/`（`emulated/0`、`sdcard0` 两种挂法都在这儿）和
     * `/sdcard/`（符号链接）两个前缀；内部私有目录 `/data/data/…` 与
     * `/data/user/0/…` 都不匹配。
     */
    private fun isOnSharedStorage(file: File): Boolean {
        val path = try {
            file.canonicalPath
        } catch (ignored: Throwable) {
            file.absolutePath
        }
        if (path.startsWith("/storage/") || path.startsWith("/sdcard/")) return true
        return try {
            @Suppress("DEPRECATION")
            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
            path.startsWith("$root/")
        } catch (ignored: Throwable) {
            false
        }
    }

    /** API 26+ 且用户还没放行「安装未知应用」时返回 true。查不动一律当已放行（让它试着装）。 */
    private fun needsUnknownSourcesGrant(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        return try {
            !ctx.packageManager.canRequestPackageInstalls()
        } catch (e: Throwable) {
            Log.w(TAG, "查 canRequestPackageInstalls 失败，当作已授权", e)
            false
        }
    }

    private fun openUnknownSourcesSettings() {
        try {
            Toast.makeText(ctx, UNKNOWN_SOURCES_HINT, Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {
        }
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "跳转「安装未知应用」设置页失败", e)
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