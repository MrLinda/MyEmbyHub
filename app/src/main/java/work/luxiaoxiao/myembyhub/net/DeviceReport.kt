package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * 设备反馈：采集 + 上报。
 *
 * ## 为什么采集这些
 *
 * 这台电视上没有 logcat 可看（见 `AGENTS.md`：`screencap` 输出 0 字节、
 * shell 没有 DUMP 权限），用户说「播不出来 / 黑屏 / 连不上」时，
 * 光靠文字描述基本无从下手。真正能定位的只有三样东西：
 *
 * 1. **TLS 现场** —— 「接口能通、取流全挂」在这台机器上有两种成因
 *    （缺 SNI / 证书链不在 CA 库里），不实测握手分不出来；
 * 2. **解码器清单** —— 判断「黑屏但进度条在走」是没解码器还是解码慢；
 * 3. **最近一次播放的编码参数** —— 4K HEVC 起不来 vs 1080p H.264 起不来，
 *    是完全两回事。
 *
 * 所以 [collect] 会**真的去握手、真的去建解码器**，不是读几个系统字段交差。
 * 代价是慢（2~6 秒），所以必须在后台线程跑，且只在用户点「上报」时才跑。
 *
 * ## 与服务端的契约
 *
 * JSON 结构和 `MyEmbyHub-feedback/report.go` 里的 `Report` 一一对应，
 * 改一处必须改两处。服务端**只校验类型、不校验枚举值** ——
 * 老设备什么奇怪的值都可能冒出来，存下来比拦下来有用。
 */
object DeviceReport {

    /** 上报接口路径（拼在用户配的服务器地址后面）。 */
    const val PATH_REPORT = "/api/v1/report"

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val TLS_PROBE_TIMEOUT = 8_000

    /**
     * 探测用的 mime 列表。
     *
     * 挑的是「这台电视上真会碰到」的：库主体是 H.264 / HEVC，少量 AV1；
     * 音频是 AAC / AC3 / EAC3 / MP3 / FLAC / Opus。
     * 每个都会真的 `createDecoderByType`，建得起来才算有。
     */
    private val VIDEO_MIMES = listOf(
        "video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9",
        "video/mp4v-es", "video/av01", "video/mpeg2", "video/3gpp"
    )
    private val AUDIO_MIMES = listOf(
        "audio/mp4a-latm", "audio/aac", "audio/ac3", "audio/eac3",
        "audio/mpeg", "audio/flac", "audio/opus", "audio/vorbis", "audio/raw"
    )

    // ==================================================================
    // 采集
    // ==================================================================

    /**
     * 采集整份报告。**必须在后台线程调用**（会做网络握手 + 建解码器）。
     *
     * @param note 用户填的备注
     * @param serverHost 用来做 TLS 握手探测的主机（一般是 Emby 服务器域名）
     */
    fun collect(ctx: Context, note: String, serverHost: String): Report {
        val report = Report(
            client = collectClient(ctx),
            device = collectDevice(ctx),
            screen = collectScreen(ctx),
            memory = collectMemory(ctx),
            network = collectNetwork(ctx),
            tls = collectTls(ctx, serverHost),
            codec = collectCodec(),
            player = collectPlayer(),
            errors = ErrorLog.snapshot().map { Err(it.at, it.where, it.message) },
            note = note.take(2000)
        )
        return report
    }

    private fun collectClient(ctx: Context): Client {
        var installedAt = ""
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            installedAt = formatTime(pi.lastUpdateTime)
        } catch (ignored: Throwable) {
        }
        // 版本号走 UpdateManager 的那两级来源（BuildConfig 反射 + PackageManager 兜底），
        // 别再自己写一遍 —— 那个兜底是踩过「BuildConfig 被 R8 裁掉」才补上的。
        val um = UpdateManager(ctx)
        return Client(
            packageName = ctx.packageName,
            versionName = um.currentVersionName(),
            versionCode = um.currentVersionCode().toInt(),
            builtAt = installedAt,
            uptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun collectDevice(ctx: Context): Device {
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        return Device(
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            product = Build.PRODUCT ?: "",
            hardware = Build.HARDWARE ?: "",
            board = Build.BOARD ?: "",
            release = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.CPU_ABI ?: "",
            abiList = listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
                .filter { it.isNotEmpty() }.joinToString(","),
            fingerprint = Build.FINGERPRINT ?: "",
            buildTags = Build.TAGS ?: "",
            buildType = Build.TYPE ?: "",
            buildTime = Build.TIME,
            // SECURITY_PATCH 是 API 23 的字段，4.3 上直接引用会 NoSuchFieldError → 反射
            securityPatch = reflectString(Build.VERSION::class.java, "SECURITY_PATCH"),
            rooted = looksRooted(),
            heapLimitMb = maxHeapMb,
            locale = Locale.getDefault().toString(),
            timeZone = TimeZone.getDefault().id
        )
    }

    private fun collectScreen(ctx: Context): Screen {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        var refreshHz = 0.0
        try {
            @Suppress("DEPRECATION")
            val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            refreshHz = d.refreshRate.toDouble()
        } catch (ignored: Throwable) {
        }
        return Screen(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            densityDpi = dm.densityDpi,
            density = dm.density.toDouble(),
            widthDp = (dm.widthPixels / dm.density).toInt(),
            heightDp = (dm.heightPixels / dm.density).toInt(),
            refreshHz = refreshHz
        )
    }

    private fun collectMemory(ctx: Context): Memory {
        var totalKb = 0L
        var availKb = 0L
        try {
            for (line in File("/proc/meminfo").readLines()) {
                when {
                    line.startsWith("MemTotal:") -> totalKb = firstNumber(line)
                    // MemAvailable 是 3.14 才有的；这台是 3.4 内核，多半只有 MemFree。
                    // 两个都读，有哪个用哪个 —— 这数据只是给个量级，不用精确。
                    line.startsWith("MemAvailable:") -> availKb = firstNumber(line)
                    line.startsWith("MemFree:") && availKb == 0L -> availKb = firstNumber(line)
                }
            }
        } catch (ignored: Throwable) {
        }
        var low = false
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            low = mi.lowMemory
        } catch (ignored: Throwable) {
        }
        return Memory(
            totalMb = totalKb / 1024,
            availMb = availKb / 1024,
            lowMemory = low,
            maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        )
    }

    private fun collectNetwork(ctx: Context): Network {
        var type = ""
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            if (ni != null) type = ni.typeName + "/" + ni.subtypeName
        } catch (ignored: Throwable) {
        }
        var ssid = ""
        var rssi = 0
        var dns = ""
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                ssid = info.ssid ?: ""
                rssi = info.rssi
                // 4.3 拿不到 LinkProperties（API 21 才有），DHCP 里的 dns1 是唯一入口。
                // 字段名在新 SDK 里被藏了 → 反射读（编译期引用不到）。
                val d1 = try {
                    info.javaClass.getField("dns1").getInt(info)
                } catch (ignored: Throwable) {
                    0
                }
                dns = intToIp(d1)
            }
        } catch (ignored: Throwable) {
        }
        return Network(type = type, wifiSsid = ssid, wifiRssi = rssi, ip = localIp(), dns = dns)
    }

    /**
     * TLS 现场：**真的握手三次**。
     *
     * 三次分别回答三个问题：
     * - 带 SNI + 不校验证书 → 协议/套件能不能协商（排除证书因素）
     * - 不带 SNI + 不校验证书 → 服务端是不是「没 SNI 就拒」（CDN 常见）
     * - 带 SNI + 内置锚点校验 → 这台 2013 年的 CA 库能不能验过服务端证书
     *
     * 只做一次的话，两种失败会混在一起 —— 那正是历史上最难查的那个坑
     * （见 `AGENTS.md` 的「SNI 缺失」条目）。
     */
    private fun collectTls(ctx: Context, host: String): Tls {
        val defaultProtocols = try {
            val f = TlsStack.socketFactory(ctx, false, TrustMode.ALL)
            val s = f?.createSocket() as? SSLSocket
            val p = s?.enabledProtocols?.toList() ?: emptyList()
            try { s?.close() } catch (ignored: Throwable) {}
            p
        } catch (ignored: Throwable) {
            emptyList()
        }
        val cipherCount = try {
            TlsStack.socketFactory(ctx, false, TrustMode.ALL)?.supportedCipherSuites?.size ?: 0
        } catch (ignored: Throwable) {
            0
        }

        val handshakes = ArrayList<Handshake>()
        if (host.isNotEmpty()) {
            handshakes.add(handshake(ctx, host, withSni = true, trust = TrustMode.ALL, tag = "不校验证书"))
            handshakes.add(handshake(ctx, host, withSni = false, trust = TrustMode.ALL, tag = "不校验证书"))
            handshakes.add(handshake(ctx, host, withSni = true, trust = TrustMode.BUNDLED, tag = "内置锚点校验"))
        }

        return Tls(
            defaultProtocols = defaultProtocols,
            supportedCipherCount = cipherCount,
            handshakes = handshakes,
            bundledAnchors = bundledAnchors(ctx),
            note = if (host.isEmpty()) "没有可探测的主机（未登录？）" else "对 $host:443 实测"
        )
    }

    private fun handshake(
        ctx: Context,
        host: String,
        withSni: Boolean,
        trust: TrustMode,
        tag: String
    ): Handshake {
        val started = SystemClock.elapsedRealtime()
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        try {
            val factory = TlsStack.socketFactory(ctx, false, trust)
                ?: return Handshake(host, withSni, false, "", "", "", "", 0, tag, 0, "拿不到 socket factory")
            raw = Socket()
            raw.connect(InetSocketAddress(host, 443), TLS_PROBE_TIMEOUT)
            raw.soTimeout = TLS_PROBE_TIMEOUT
            ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
            if (withSni) TlsStack.applyServerName(ssl, host)
            ssl.startHandshake()

            val session = ssl.session
            var subject = ""
            var issuer = ""
            var chainLen = 0
            try {
                val chain = session.peerCertificates
                chainLen = chain.size
                val leaf = chain.firstOrNull() as? X509Certificate
                subject = leaf?.subjectDN?.name ?: ""
                issuer = leaf?.issuerDN?.name ?: ""
            } catch (ignored: Throwable) {
            }
            return Handshake(
                host = host,
                withSni = withSni,
                ok = true,
                protocol = session.protocol ?: "",
                cipherSuite = session.cipherSuite ?: "",
                peerSubject = subject,
                peerIssuer = issuer,
                chainLen = chainLen,
                bundledVerify = tag,
                millis = SystemClock.elapsedRealtime() - started,
                error = ""
            )
        } catch (e: Throwable) {
            // 失败信息就是这次反馈最值钱的部分：4.3 的报错文案极具误导性
            // （"wrong version number" 其实是没 SNI），原样带回去。
            val msg = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            return Handshake(
                host = host,
                withSni = withSni,
                ok = false,
                protocol = "",
                cipherSuite = "",
                peerSubject = "",
                peerIssuer = "",
                chainLen = 0,
                bundledVerify = tag,
                millis = SystemClock.elapsedRealtime() - started,
                error = msg.take(300)
            )
        } finally {
            try { ssl?.close() } catch (ignored: Throwable) {}
            try { raw?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun bundledAnchors(ctx: Context): List<String> = try {
        (ctx.assets.list("") ?: arrayOf())
            .filter { it.endsWith(".pem") }
            .sorted()
    } catch (ignored: Throwable) {
        emptyList()
    }

    /**
     * 解码器清单 + 实测探测。
     *
     * ⚠️ **只探到「能不能建起解码器」为止**：`MediaCodecInfo.isFormatSupported`
     * 和 `VideoCapabilities`（能问 profile/level）都是 API 21 才有的，
     * 4.3 上问不到。所以 `probe.profile/level` 一律 0，
     * 靠 `ok` + `decoder` 两个字段说话。
     *
     * 也**不 configure**：真去 configure 一个 4K HEVC 会在 1GB 内存的机器上
     * 直接 OOM，而反馈功能自己不能是崩溃来源。
     */
    private fun collectCodec(): Codec {
        val video = ArrayList<Decoder>()
        val audio = ArrayList<Decoder>()
        try {
            // ⚠️ 这里是**静态方法**，不是实例方法：
            //   - API 18：`MediaCodecList.getCodecCount()` / `getCodecInfoAt(i)` 都是 public static，
            //     但现代 SDK 里被标成 @hide → 编译期引用不到，只能反射。
            //   - `new MediaCodecList(ALL_CODECS)` 的**实例**方法 `getCodecCount()` 是 API 21 才加的，
            //     4.3 上根本没有（第一版就栽在这儿：清单永远是 0 条，但探测却能跑）。
            val cls = android.media.MediaCodecList::class.java
            val count = cls.getMethod("getCodecCount").invoke(null) as Int
            val getInfo = cls.getMethod("getCodecInfoAt", Int::class.javaPrimitiveType)
            for (i in 0 until count) {
                val info = getInfo.invoke(null, i) as? android.media.MediaCodecInfo ?: continue
                if (info.isEncoder) continue
                val types = info.supportedTypes ?: continue
                val isVideo = types.any { it.startsWith("video/") }
                val isAudio = types.any { it.startsWith("audio/") }
                if (!isVideo && !isAudio) continue
                val name = info.name ?: ""
                val d = Decoder(
                    name = name,
                    type = types.joinToString(","),
                    // isHardwareAccelerated / isSoftwareOnly 都是 API 29+，4.3 上反射必失败。
                    // 名字前缀是这个年代唯一可靠的判据（OMX.google.* = 软解）。
                    hardware = if (reflectBool(info, "isHardwareAccelerated")) true
                    else !name.startsWith("OMX.google.") && name.startsWith("OMX."),
                    software = if (reflectBool(info, "isSoftwareOnly")) true
                    else name.startsWith("OMX.google.") || name.startsWith("c2.android."),
                    mimeTypes = types.joinToString(",")
                )
                if (isVideo) video.add(d) else audio.add(d)
            }
        } catch (ignored: Throwable) {
        }

        val probes = ArrayList<CodecProbe>()
        for (mime in VIDEO_MIMES + AUDIO_MIMES) {
            val started = SystemClock.elapsedRealtime()
            var name = ""
            var ok = false
            var note = ""
            try {
                val codec = android.media.MediaCodec.createDecoderByType(mime)
                name = codec.name ?: ""
                codec.release()
                ok = true
            } catch (e: Throwable) {
                note = (e.message ?: e.javaClass.simpleName).take(120)
            }
            probes.add(
                CodecProbe(
                    mime = mime,
                    profile = 0,
                    level = 0,
                    width = 0,
                    height = 0,
                    ok = ok,
                    decoder = name,
                    note = if (note.isEmpty()) "${SystemClock.elapsedRealtime() - started}ms" else note
                )
            )
        }

        return Codec(
            videoDecoders = video,
            audioDecoders = audio,
            probes = probes,
            note = "只探到「解码器能建起来」；profile/level 要 API 21+，4.3 问不到"
        )
    }

    private fun collectPlayer(): Player = Player(
        lastItemTitle = PlaybackSnapshot.lastItemTitle,
        lastItemId = PlaybackSnapshot.lastItemId,
        container = PlaybackSnapshot.container,
        videoCodec = PlaybackSnapshot.videoCodec,
        audioCodec = PlaybackSnapshot.audioCodec,
        width = PlaybackSnapshot.width,
        height = PlaybackSnapshot.height,
        bitrateKbps = PlaybackSnapshot.bitrateKbps,
        decoderName = PlaybackSnapshot.decoderName,
        firstFrame = PlaybackSnapshot.firstFrame,
        errorCode = PlaybackSnapshot.errorCode,
        errorMessage = PlaybackSnapshot.errorMessage,
        note = if (PlaybackSnapshot.lastItemTitle.isEmpty()) "这次开机还没播过东西" else ""
    )

    // ==================================================================
    // 上报
    // ==================================================================

    sealed class Result {
        data class Ok(val id: Long) : Result()
        data class Fail(val message: String) : Result()
    }

    /**
     * POST 一份报告。
     *
     * 信任模式用 [TrustMode.ALL]：上报服务器是用户自建的（甲骨文 / 群晖 / 什么都可能），
     * 证书从 Let's Encrypt 到自签都有可能，内置锚点覆盖不全。
     * 这条链路传的是「用户主动要交给自己的设备信息」，放宽校验不会泄露给别人；
     * 真正该防的是「报错发不出去、反馈功能等于摆设」。
     */
    fun upload(ctx: Context, endpoint: String, report: Report): Result {
        val url = endpoint.trimEnd('/') + PATH_REPORT
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as java.net.HttpURLConnection
            // 生产环境一定是 https；本地联调（http://10.0.2.2:端口）也得能跑，
            // 所以这里按协议分流，不硬转 HttpsURLConnection。
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = TlsStack.socketFactory(ctx, false, TrustMode.ALL)
                    ?: throw IOException("无法构造 TLS socket factory")
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent", "MyEmbyHub/" + UpdateManager(ctx).currentVersionName())
            val body = report.toJson().toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                return Result.Fail("HTTP $code ${text.take(120)}")
            }
            val obj = try { JSONObject(text) } catch (e: Throwable) { null }
            if (obj?.optBoolean("ok") == true) {
                Result.Ok(obj.optLong("id"))
            } else {
                Result.Fail(obj?.optString("error")?.ifEmpty { null } ?: "服务器没说是哪里不对（HTTP $code）")
            }
        } catch (e: Throwable) {
            Result.Fail(friendlyError(e))
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private fun reflectString(cls: Class<*>, field: String): String = try {
        cls.getField(field).get(null) as? String ?: ""
    } catch (ignored: Throwable) {
        ""
    }

    private fun reflectBool(obj: Any, method: String): Boolean = try {
        obj.javaClass.getMethod(method).invoke(obj) as? Boolean ?: false
    } catch (ignored: Throwable) {
        false
    }

    private fun looksRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun firstNumber(line: String): Long {
        val digits = line.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    private fun intToIp(v: Int): String {
        if (v == 0) return ""
        return "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
    }

    /** 本机局域网 IPv4（挑第一个非回环、非 link-local 的）。 */
    private fun localIp(): String {
        return try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val s = addr.hostAddress ?: continue
                        if (s.startsWith("169.254.")) continue
                        return s
                    }
                }
            }
            ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    // ==================================================================
    // 数据结构（字段名 = 服务端 report.go 的 json tag）
    // ==================================================================

    data class Report(
        val client: Client,
        val device: Device,
        val screen: Screen,
        val memory: Memory,
        val network: Network,
        val tls: Tls,
        val codec: Codec,
        val player: Player,
        val errors: List<Err>,
        val note: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("client", client.toJson())
            put("device", device.toJson())
            put("screen", screen.toJson())
            put("memory", memory.toJson())
            put("network", network.toJson())
            put("tls", tls.toJson())
            put("codec", codec.toJson())
            put("player", player.toJson())
            put("errors", JSONArray().apply { errors.forEach { put(it.toJson()) } })
            put("note", note)
        }
    }

    data class Client(
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val builtAt: String,
        val uptimeMs: Long
    ) {
        fun toJson() = JSONObject().apply {
            put("packageName", packageName)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("builtAt", builtAt)
            put("uptimeMs", uptimeMs)
        }
    }

    data class Device(
        val manufacturer: String, val brand: String, val model: String,
        val device: String, val product: String, val hardware: String, val board: String,
        val release: String, val sdkInt: Int, val abi: String, val abiList: String,
        val fingerprint: String, val buildTags: String, val buildType: String,
        val buildTime: Long, val securityPatch: String, val rooted: Boolean,
        val heapLimitMb: Int, val locale: String, val timeZone: String
    ) {
        fun toJson() = JSONObject().apply {
            put("manufacturer", manufacturer); put("brand", brand); put("model", model)
            put("device", device); put("product", product); put("hardware", hardware)
            put("board", board); put("release", release); put("sdkInt", sdkInt)
            put("abi", abi); put("abiList", abiList); put("fingerprint", fingerprint)
            put("buildTags", buildTags); put("buildType", buildType)
            put("buildTime", buildTime); put("securityPatch", securityPatch)
            put("rooted", rooted); put("heapLimitMb", heapLimitMb)
            put("locale", locale); put("timeZone", timeZone)
        }
    }

    data class Screen(
        val widthPx: Int, val heightPx: Int, val densityDpi: Int,
        val density: Double, val widthDp: Int, val heightDp: Int, val refreshHz: Double
    ) {
        fun toJson() = JSONObject().apply {
            put("widthPx", widthPx); put("heightPx", heightPx)
            put("densityDpi", densityDpi); put("density", density)
            put("widthDp", widthDp); put("heightDp", heightDp)
            put("refreshHz", refreshHz)
        }
    }

    data class Memory(val totalMb: Long, val availMb: Long, val lowMemory: Boolean, val maxHeapMb: Long) {
        fun toJson() = JSONObject().apply {
            put("totalMb", totalMb); put("availMb", availMb)
            put("lowMemory", lowMemory); put("maxHeapMb", maxHeapMb)
        }
    }

    data class Network(
        val type: String, val wifiSsid: String, val wifiRssi: Int, val ip: String, val dns: String
    ) {
        fun toJson() = JSONObject().apply {
            put("type", type); put("wifiSsid", wifiSsid)
            put("wifiRssi", wifiRssi); put("ip", ip); put("dns", dns)
        }
    }

    data class Tls(
        val defaultProtocols: List<String>,
        val supportedCipherCount: Int,
        val handshakes: List<Handshake>,
        val bundledAnchors: List<String>,
        val note: String
    ) {
        fun toJson() = JSONObject().apply {
            put("defaultProtocols", JSONArray().apply { defaultProtocols.forEach { put(it) } })
            put("supportedCipherCount", supportedCipherCount)
            put("handshakes", JSONArray().apply { handshakes.forEach { put(it.toJson()) } })
            put("bundledAnchors", JSONArray().apply { bundledAnchors.forEach { put(it) } })
            put("note", note)
        }
    }

    data class Handshake(
        val host: String, val withSni: Boolean, val ok: Boolean,
        val protocol: String, val cipherSuite: String,
        val peerSubject: String, val peerIssuer: String, val chainLen: Int,
        val bundledVerify: String, val millis: Long, val error: String
    ) {
        fun toJson() = JSONObject().apply {
            put("host", host); put("withSni", withSni); put("ok", ok)
            put("protocol", protocol); put("cipherSuite", cipherSuite)
            put("peerSubject", peerSubject); put("peerIssuer", peerIssuer)
            put("chainLen", chainLen); put("bundledVerify", bundledVerify)
            put("millis", millis); put("error", error)
        }
    }

    data class Codec(
        val videoDecoders: List<Decoder>,
        val audioDecoders: List<Decoder>,
        val probes: List<CodecProbe>,
        val note: String
    ) {
        fun toJson() = JSONObject().apply {
            put("videoDecoders", JSONArray().apply { videoDecoders.forEach { put(it.toJson()) } })
            put("audioDecoders", JSONArray().apply { audioDecoders.forEach { put(it.toJson()) } })
            put("probes", JSONArray().apply { probes.forEach { put(it.toJson()) } })
            put("note", note)
        }
    }

    data class Decoder(
        val name: String, val type: String, val hardware: Boolean,
        val software: Boolean, val mimeTypes: String
    ) {
        fun toJson() = JSONObject().apply {
            put("name", name); put("type", type); put("hardware", hardware)
            put("software", software); put("mimeTypes", mimeTypes)
        }
    }

    data class CodecProbe(
        val mime: String, val profile: Int, val level: Int, val width: Int,
        val height: Int, val ok: Boolean, val decoder: String, val note: String
    ) {
        fun toJson() = JSONObject().apply {
            put("mime", mime); put("profile", profile); put("level", level)
            put("width", width); put("height", height); put("ok", ok)
            put("decoder", decoder); put("note", note)
        }
    }

    data class Player(
        val lastItemTitle: String, val lastItemId: String, val container: String,
        val videoCodec: String, val audioCodec: String, val width: Int, val height: Int,
        val bitrateKbps: Int, val decoderName: String, val firstFrame: Boolean,
        val errorCode: String, val errorMessage: String, val note: String
    ) {
        fun toJson() = JSONObject().apply {
            put("lastItemTitle", lastItemTitle); put("lastItemId", lastItemId)
            put("container", container); put("videoCodec", videoCodec)
            put("audioCodec", audioCodec); put("width", width); put("height", height)
            put("bitrateKbps", bitrateKbps); put("decoderName", decoderName)
            put("firstFrame", firstFrame); put("errorCode", errorCode)
            put("errorMessage", errorMessage); put("note", note)
        }
    }

    data class Err(val at: String, val where: String, val message: String) {
        fun toJson() = JSONObject().apply {
            put("at", at); put("where", where); put("message", message)
        }
    }
}
