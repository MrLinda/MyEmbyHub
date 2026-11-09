package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 在线更新。
 *
 * ## 流程
 *
 * 1. 请求一个版本清单 JSON（默认在电脑的静态服务上，Gradle 打包时自动生成）：
 *    ```json
 *    { "versionCode": 20261103, "versionName": "1.0.20261103-201000",
 *      "url": "http://192.168.1.50:8765/myembyhub-debug.apk",
 *      "size": 8430927, "notes": "修复扫码登录" }
 *    ```
 * 2. `versionCode` 比当前 App 大 → 有新版
 * 3. 下载 APK 到 `/sdcard/Download/`
 * 4. 调起系统安装器（这一步仍然需要用户在电视上按「安装」——
 *    shell 之外的 uid 都没有 `INSTALL_PACKAGES` 权限，绕不过去）
 *
 * ## 注意
 *
 * - `versionCode` 用秒级时间戳，永远递增 → 不会因为版本号相同而被系统拒绝覆盖安装。
 * - 更新源地址做成**可配置**的（存在 Session 里），换服务器不用重新打包。
 */
class UpdateManager(private val ctx: Context) {

    companion object {
        private const val TAG = "Update"
        private const val DEFAULT_MANIFEST = "http://192.168.1.50:8765/version.json"
        private const val APK_NAME = "myembyhub-update.apk"
    }

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val size: Long,
        val notes: String
    ) {
        /** 有新版本才返回 true。 */
        fun isNewerThan(current: Long): Boolean = versionCode > current
    }

    private val worker = Executors.newSingleThreadExecutor()

    /** 检查更新。返回 null 表示已是最新。 */
    fun check(manifestUrl: String): Result<Release?> {
        return try {
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

            val url = manifestUrl.trim().ifEmpty { DEFAULT_MANIFEST }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return Result.failure(IllegalStateException("检查更新失败 HTTP $code"))
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = org.json.JSONObject(text)
            val release = Release(
                versionCode = json.optLong("versionCode", 0),
                versionName = json.optString("versionName"),
                url = json.optString("url"),
                size = json.optLong("size", 0),
                notes = json.optString("notes")
            )
            if (release.url.isEmpty()) {
                Result.failure(IllegalStateException("版本清单里没有下载地址"))
            } else {
                Result.success(release.takeIf { it.isNewerThan(current) })
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 下载 APK。
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
            var conn: HttpURLConnection? = null
            try {
                // ⚠️ API 18 上写 /sdcard 需要 WRITE_EXTERNAL_STORAGE，
                //    没声明的话这里会抛 FileNotFoundException: (Permission denied)。
                //    目标目录也可以被外部存储挂载状态影响，所以先探测可写性。
                val dir = File("/sdcard/Download")
                if (!dir.exists() && !dir.mkdirs()) {
                    onError("下载失败：无法创建目录 ${dir.absolutePath}")
                    return@execute
                }
                if (!dir.canWrite()) {
                    onError("下载失败：无写权限（${dir.absolutePath}）—— 检查 WRITE_EXTERNAL_STORAGE")
                    return@execute
                }
                val target = File(dir, APK_NAME)

                Log.i(TAG, "开始下载 ${release.url}")
                conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code != 200) {
                    onError("下载失败 HTTP $code")
                    return@execute
                }
                val total = conn.contentLength.toLong().let { if (it > 0) it else release.size }
                var downloaded = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
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
                // 系统安装器是另一个 uid，必须能读到文件 → 放开读权限
                target.setReadable(true, false)
                Log.i(TAG, "下载完成 ${target.absolutePath} ($downloaded 字节)")
                onDone(target.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "下载失败", e)
                onError("下载失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
            } finally {
                try { conn?.disconnect() } catch (ignored: Throwable) {}
            }
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

    /** 当前版本号（秒级时间戳）。读不到返回 0。 */
    fun currentVersionCode(): Long {
        return try {
            val cls = Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
            val f = cls.getField("VERSION_CODE")
            (f.get(null) as Int).toLong()
        } catch (e: Throwable) {
            // ⚠️ AGP 8.0 起 BuildConfig 不再自动生成，
            //    忘了 `buildFeatures { buildConfig = true }` 就会走到这里。
            Log.e(TAG, "读 BuildConfig.VERSION_CODE 失败（BuildConfig 是否存在？）", e)
            0L
        }
    }

    fun currentVersionName(): String {
        return try {
            val cls = Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
            val f = cls.getField("VERSION_NAME")
            f.get(null) as String
        } catch (e: Throwable) {
            Log.e(TAG, "读 BuildConfig.VERSION_NAME 失败", e)
            ""
        }
    }
}
