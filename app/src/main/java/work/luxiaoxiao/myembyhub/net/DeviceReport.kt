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
 * ## 脱敏
 *
 * 采集出来的东西要离开这台机器，所以 Wi-Fi 名称 / 局域网 IP / DNS 在
 * [collectNetwork] 里就地打码（见那三个 mask 函数）。判断力保留，定位能力去掉。
 * （自动打点 [PlayStats] 根本不带这几样，它只发机型 + 播放数字。）
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
     * 第一次探测的超时。
     *
     * 8 秒在模拟器上实测**不够**：第 1 次握手超时失败、第 2 次同参数 1 秒成功，
     * 差的就是「Conscrypt 原生库还没加载 + 热点代码没 JIT」这一笔。
     * 真机（1GHz ARM、8MB 的 .so）只会更慢，所以第一次给到 20 秒。
     * 代价只落在「真的连不上」这一种情况下，正常时第一次也就 1~4 秒。
     */
    private const val FIRST_PROBE_TIMEOUT = 20_000

    /** 上报里套件清单最多带几条。系统 JSSE 在 4.3 上有一百多条，全带上没意义。 */
    private const val MAX_CIPHER_LIST = 80

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
     * @param serverPort 服务器实际端口。**别写死 443** —— Emby 跑在 1234 / 8096 这类
     *        非标端口上很常见，打 443 打到的是同机另一个服务（甚至别人的站），
     *        握出来的结果跟用户真正的连接毫无关系。
     * @param onProgress 阶段进度回调 `(0~100, 一句人话)`。
     *        ⚠️ 回调跑在**采集线程**上，调用方自己往主线程 post。
     *        采集要 2~6 秒，光转圈用户会以为卡死 —— 有百分比才叫「在干活」。
     */
    fun collect(
        ctx: Context,
        note: String,
        serverHost: String,
        serverPort: Int = 443,
        onProgress: ((Int, String) -> Unit)? = null
    ): Report {
        // 进度条不是按时间估的，是按**实际阶段**：读设备信息（快）→ 三次握手（最慢）
        // → 17 个解码器探测（中等）→ 上传。权重按实测耗时分的，别当成精确时间。
        val p = Progress(onProgress)
        p.emit(4, "正在读取设备信息…")
        val client = collectClient(ctx)
        val device = collectDevice(ctx)
        val screen = collectScreen(ctx)
        val memory = collectMemory(ctx)
        p.emit(20, "正在读取网络状态…")
        val network = collectNetwork(ctx)
        // 打一行脱敏后的结果：排查「上报里为什么看不到 Wi-Fi 名」时，
        // 不用猜是哪一步抹的。打的是**已经脱敏**的值，日志本身不泄露。
        android.util.Log.d(
            "DeviceReport",
            "network 上报值：type=${network.type} ssid=${network.wifiSsid} " +
                "rssi=${network.wifiRssi} ip=${network.ip} dns=${network.dns}"
        )
        val tls = collectTls(ctx, serverHost, serverPort, p)
        // 握手结论打一行：真机上排查「为什么连不上」时，这是唯一能当场看到的东西。
        // 每次握手的完整错误文案在报告里，这里只报成败，免得日志太长。
        android.util.Log.d(
            "DeviceReport",
            "tls 上报值：impl=${tls.impl} actual=${tls.implActual} socket=${tls.socketClass} " +
                "gcm=${tls.cipherSuites.any { it.contains("_GCM_") }} " +
                "chaCha20=${tls.cipherSuites.any { it.contains("CHACHA20", true) }} " +
                "suites=${tls.supportedCipherCount} " +
                "handshakes=" + tls.handshakes.joinToString(" | ") {
                    "${it.host}${if (it.withSni) "(SNI)" else ""}${if (it.impl.isNotEmpty()) "[${it.impl}]" else ""}=" +
                        (if (it.ok) "${it.protocol}/${it.cipherSuite}" else "失败:${it.error}")
                } +
                " hostMemory=" + tls.hostMemory.joinToString(",")
        )
        val codec = collectCodec(p)
        p.emit(88, "正在整理播放记录…")
        val player = collectPlayer()
        val errors = ErrorLog.snapshot().map { Err(it.at, it.where, it.message) }
        p.emit(92, "正在打包上传…")
        return Report(
            client = client,
            device = device,
            screen = screen,
            memory = memory,
            network = network,
            tls = tls,
            codec = codec,
            player = player,
            errors = errors,
            note = note.take(2000)
        )
    }

    /**
     * 进度回调的小包装。
     *
     * 两件事：① 线程内可能被多个阶段调用，百分比只允许往前走（倒退看着像出错了）；
     * ② 回调本身出错绝不能影响采集 —— 记录失败比进度条重要得多。
     */
    private class Progress(private val cb: ((Int, String) -> Unit)?) {
        private var last = -1

        fun emit(percent: Int, label: String) {
            val f = cb ?: return
            val v = if (percent < last) last else percent
            last = v
            try {
                f(v, label)
            } catch (ignored: Throwable) {
            }
        }
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
                // ⚠️ SSID 在这里就脱敏，别等到 toJson 再处理 ——
                // 这一层之后没有任何地方需要原文（见 PiiMask.ssid 注释）。
                ssid = PiiMask.ssid(info.ssid ?: "")
                rssi = info.rssi
                // 4.3 拿不到 LinkProperties（API 21 才有），DHCP 里的 dns1 是唯一入口。
                // 字段名在新 SDK 里被藏了 → 反射读（编译期引用不到）。
                val d1 = try {
                    info.javaClass.getField("dns1").getInt(info)
                } catch (ignored: Throwable) {
                    0
                }
                dns = PiiMask.dns(intToIp(d1))
            }
        } catch (ignored: Throwable) {
        }
        return Network(
            type = type,
            wifiSsid = ssid,
            wifiRssi = rssi,
            ip = PiiMask.ip(localIp()),
            dns = dns
        )
    }

    // 脱敏函数在 [PiiMask]（纯函数，有单元测试兜着）。

    /**
     * TLS 现场：**真的握手四次**（对服务器三次 + 内置对照一次）。
     *
     * 对服务器的三次分别回答三个问题：
     * - 带 SNI + 不校验证书 → 协议/套件能不能协商（排除证书因素）
     * - 不带 SNI + 不校验证书 → 服务端是不是「没 SNI 就拒」（CDN 常见）
     * - 带 SNI + 内置锚点校验 → 这台 2013 年的 CA 库能不能验过服务端证书
     *
     * 只做一次的话，两种失败会混在一起 —— 那正是历史上最难查的那个坑
     * （见 `AGENTS.md` 的「SNI 缺失」条目）。
     *
     * 第四次是**内置对照**：对自己的反馈服务器（`Session.feedbackEndpoint`）握手。
     * 它把「连不上」拆成互斥的两种：
     * - 对照也失败 → 这台电视连**任何** HTTPS 都费劲，问题在客户端/网络侧
     *   （典型：4.3 的系统 JSSE 没有 GCM，而这台机器上 Conscrypt 没加载起来）
     * - 对照成功、Emby 那三次失败 → 问题在**那台服务器**的配置（套件/证书/端口）
     *
     * 用户问过「能不能换个稳定的站来测」—— 不用换，反馈服务器本身就是
     * 一个「已知一定配好」的 HTTPS 端点，还不用多维护一个域名。
     * 用 `TrustMode.ALL`：对照只回答「谈不谈得拢」，证书因素归第三次管。
     */
    private fun collectTls(ctx: Context, host: String, port: Int, p: Progress): Tls {
        // 本机这一侧的「能力清单」：启用了哪些协议、哪些套件、实际是哪个实现。
        //
        // 光记一个套件**数量**没用。真正要回答的问题是
        // 「这台机器的 TLS 栈里到底有没有 GCM」—— 服务器只开 GCM 时，
        // 有 GCM 是服务器配置问题，没 GCM 是客户端问题，结论完全相反。
        //
        // ⚠️ 这一段取的是「自动模式下优先的那条栈」（有 Conscrypt 就是 Conscrypt），
        //    回答的是**具备**什么能力，不等于这个域名**用了**什么 —— 后者看
        //    `implActual`。两个都要，缺一个就会误判：只开 GCM 的服务器上，
        //    「能力有 GCM 但实际栈是系统栈」说明问题在「这个域名被记到了系统栈」，
        //    而不是「客户端不支持 GCM」。
        var defaultProtocols: List<String> = emptyList()
        var cipherSuites: List<String> = emptyList()
        var socketClass = ""
        try {
            val f = TlsStack.socketFactory(ctx, TrustMode.ALL)
            val s = f?.createSocket() as? SSLSocket
            if (s != null) {
                defaultProtocols = s.enabledProtocols?.toList() ?: emptyList()
                cipherSuites = s.enabledCipherSuites?.toList() ?: emptyList()
                // 类名是最诚实的证据：Conscrypt 和系统 JSSE 的 socket 类名完全不同，
                // 「设置里选了自动，实际走的哪条」只有这里说得清。
                socketClass = s.javaClass.name
            }
            try { s?.close() } catch (ignored: Throwable) {}
        } catch (ignored: Throwable) {
        }
        val cipherCount = cipherSuites.size

        val handshakes = ArrayList<Handshake>()
        if (host.isNotEmpty()) {
            // 实测每次握手 1.1~3.6 秒，是整段采集里最慢的一环 ——
            // 所以它占的进度区间最大（22 → 62），而且分次报，别让人盯着不动的条。
            //
            // ⚠️ **第一次必须给更长的超时**（2026-11-19 模拟器实测）：
            //    第 1 次握了 8 秒整超时失败，紧接着的第 2、3 次各 1 秒就成功 ——
            //    原因是第一次要付 Conscrypt 原生库加载 + 一堆 JIT 的钱，
            //    8 秒在 4.3 这种 1GHz ARM 上不够。这不是「连不上」，
            //    报成失败会让整份报告得出反向结论（「服务器不通」），比没数据更糟。
            p.emit(22, "正在和服务器握手（1/3）…")
            handshakes.add(
                handshake(
                    ctx, host, port, withSni = true, trust = TrustMode.ALL,
                    tag = "不校验证书", timeoutMs = FIRST_PROBE_TIMEOUT
                )
            )
            p.emit(32, "正在和服务器握手（2/3）…")
            handshakes.add(handshake(ctx, host, port, withSni = false, trust = TrustMode.ALL, tag = "不校验证书"))
            p.emit(42, "正在和服务器握手（3/3）…")
            handshakes.add(handshake(ctx, host, port, withSni = true, trust = TrustMode.BUNDLED, tag = "内置锚点校验"))
        } else {
            p.emit(42, "没有可探测的服务器，跳过握手…")
        }

        // 内置对照：只在「有得对照」的时候做。
        val control = controlTarget(ctx)
        val controlSkipped = when {
            host.isEmpty() -> "服务器未知，没做对照"
            control == null -> "反馈地址不是 https，没做对照"
            control.first == host -> "反馈服务器与 Emby 同一主机，对照无意义"
            else -> ""
        }
        if (controlSkipped.isEmpty() && control != null) {
            p.emit(52, "正在做内置对照握手…")
            handshakes.add(
                handshake(
                    ctx, control.first, control.second,
                    withSni = true, trust = TrustMode.ALL,
                    tag = "内置对照（反馈服务器）"
                )
            )
        }
        p.emit(62, "握手完成，正在测试解码能力…")

        val note = buildString {
            if (host.isEmpty()) append("没有可探测的主机（未登录？）") else append("对 $host:$port 实测")
            append("；")
            if (controlSkipped.isEmpty() && control != null) {
                append("内置对照 $control.first:${control.second}")
            } else {
                append(controlSkipped)
            }
        }

        return Tls(
            defaultProtocols = defaultProtocols,
            supportedCipherCount = cipherCount,
            cipherSuites = cipherSuites,
            impl = tlsImplName(ctx),
            implActual = tlsImplActualName(ctx, host),
            socketClass = socketClass,
            handshakes = handshakes,
            bundledAnchors = bundledAnchors(ctx),
            hostMemory = hostMemoryLines(ctx),
            note = note
        )
    }

    /**
     * 按域名记下的 TLS 结论（人话版）。
     *
     * 探针自己**不写**这张表：它用 `TrustMode.ALL` 握手，成功不代表正式链路
     * （内置锚点验签）也能成功。表里的条目只来自真实请求的成功路径。
     */
    private fun hostMemoryLines(ctx: Context): List<String> = try {
        TlsHostMemory.snapshot(ctx).map { (h, impl) ->
            val tag = when (impl) {
                TlsStack.TlsImpl.CONSCRYPT -> "conscrypt"
                TlsStack.TlsImpl.SYSTEM -> "system"
                else -> "?"
            }
            "$h=$tag"
        }
    } catch (ignored: Throwable) {
        emptyList()
    }

    /**
     * 内置对照的目标主机 —— 反馈服务器。
     *
     * 取的是 `Session.feedbackEndpoint`（用户填的优先，没填走打包烤进来的
     * `BuildConfig.FEEDBACK_URL`），不是直接读 `BuildConfig` —— 否则
     * 自己改了反馈地址的人，对照打的还是老地址。
     *
     * 只认 https；http 或空串一律返回 null（对照必须是一次真 TLS 握手才有意义）。
     */
    private fun controlTarget(ctx: Context): Pair<String, Int>? = try {
        val endpoint = Session.get(ctx).feedbackEndpoint
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            null
        } else {
            val parts = UrlKit.parse(endpoint)
            if (parts.host.isEmpty()) null else parts.host to parts.port
        }
    } catch (ignored: Throwable) {
        null
    }

    /**
     * 当前生效的 TLS 实现，人话版。
     *
     * 三种状态要分清：用户锁死 Conscrypt / 用户锁死系统栈 / 自动（再分
     * Conscrypt 到底加载起来没有）。报告里只写「自动」等于什么都没说 ——
     * 4.3 上这两个实现的差别就是「能不能连只开 GCM 的服务器」。
     */
    private fun tlsImplName(ctx: Context): String = try {
        when (TlsStack.pref()) {
            TlsStack.TlsImpl.CONSCRYPT -> "Conscrypt（手动锁定）"
            TlsStack.TlsImpl.SYSTEM -> "系统 JSSE（手动锁定）"
            TlsStack.TlsImpl.AUTO -> when (TlsStack.conscryptAvailable()) {
                true -> "Conscrypt（自动）"
                false -> "系统 JSSE（自动，Conscrypt 不可用）"
                null -> "自动（尚未探测）"
            }
        }
    } catch (ignored: Throwable) {
        "未知"
    }

    /**
     * **这个域名实际用的那条栈**（人话版）。
     *
     * 「自动」模式下两个域名可能各走一条栈，所以这个值必须按 host 查，
     * 不能拿全局偏好顶替 —— 见 [Tls.implActual] 的注释。
     */
    private fun tlsImplActualName(ctx: Context, host: String): String = try {
        if (host.isEmpty()) {
            "未知（没有可探测的服务器）"
        } else {
            val actual = TlsStack.chooseFor(ctx, TrustMode.ALL, host)?.impl
            val remembered = TlsHostMemory.recall(ctx, host) != null
            when (actual) {
                TlsStack.TlsImpl.CONSCRYPT ->
                    "Conscrypt" + if (remembered) "（按域名记录）" else ""
                TlsStack.TlsImpl.SYSTEM ->
                    "系统 JSSE" + if (remembered) "（按域名记录）" else "（首试系统栈）"
                else -> "未知"
            }
        }
    } catch (ignored: Throwable) {
        "未知"
    }

    /**
     * 一次真握手，**走的是正式请求用的那条栈**，失败时按 `TlsConn` 的规矩换栈重试一次。
     *
     * 为什么必须走 `chooseFor` 而不是 `socketFactory(pref)`：
     * 现在「自动」模式下，同一个域名可能用系统栈、另一个域名用 Conscrypt
     * （见 `TlsHostMemory`）。探针要是固定用偏好栈，量出来的就不是用户实际遇到的那条路 ——
     * 报告上会出现「impl=Conscrypt、hostMemory=system」这种自相矛盾的组合，
     * 排查的人反而被误导。
     *
     * 换栈重试也要在这里复现：新装 + `probeSystemFirst` 默认开 + 服务器只开 GCM 时，
     * 系统栈必然失败一次，而真实请求会自动换到 Conscrypt 成功。
     * 探针若不复现这一步，就会给一台**用得好好的**电视报「握手失败」。
     */
    private fun handshake(
        ctx: Context,
        host: String,
        port: Int,
        withSni: Boolean,
        trust: TrustMode,
        tag: String,
        /** 单次握手的连接/读超时。第一次探测要付原生库加载的钱，单独放宽。 */
        timeoutMs: Int = TLS_PROBE_TIMEOUT
    ): Handshake {
        val started = SystemClock.elapsedRealtime()
        val first = TlsStack.chooseFor(ctx, trust, host)
            ?: return Handshake(
                host, withSni, false, "", "", "", "", 0, tag,
                SystemClock.elapsedRealtime() - started, "拿不到 socket factory",
                implName(null)
            )
        try {
            return tryHandshake(first, host, port, withSni, tag, timeoutMs, started)
        } catch (e: Throwable) {
            // 只有「谈不拢」才换栈；证书 / DNS / 超时换也救不回来（同 TlsConn 的判据）。
            if (TlsStack.pref() != TlsStack.TlsImpl.AUTO || !TlsStack.isHandshakeFailure(e)) {
                return failedHandshake(host, withSni, tag, started, first.impl, brief(e))
            }
            val second = TlsStack.escalate(ctx, trust, first.impl)
                ?: return failedHandshake(host, withSni, tag, started, first.impl, brief(e))
            try {
                return tryHandshake(second, host, port, withSni, tag, timeoutMs, started)
            } catch (e2: Throwable) {
                // 两条栈都失败：把**两条**的错都带上，否则只看到最后一条，
                // 会漏掉「系统栈因为没 GCM 失败、Conscrypt 因为证书失败」这种关键组合。
                val merged = "${implName(first.impl)} 失败：${brief(e)}；" +
                    "${implName(second.impl)} 失败：${brief(e2)}"
                return failedHandshake(host, withSni, tag, started, second.impl, merged)
            }
        }
    }

    /** 用指定的一条栈真握一次；失败直接抛，由 [handshake] 决定换不换栈。 */
    private fun tryHandshake(
        choice: TlsStack.Choice,
        host: String,
        port: Int,
        withSni: Boolean,
        tag: String,
        timeoutMs: Int,
        started: Long
    ): Handshake {
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        try {
            raw = Socket()
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            ssl = choice.factory.createSocket(raw, host, port, true) as SSLSocket
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
                error = "",
                impl = implName(choice.impl)
            )
        } finally {
            try { ssl?.close() } catch (ignored: Throwable) {}
            try { raw?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun failedHandshake(
        host: String, withSni: Boolean, tag: String, started: Long,
        impl: TlsStack.TlsImpl, error: String
    ) = Handshake(
        host = host, withSni = withSni, ok = false,
        protocol = "", cipherSuite = "", peerSubject = "", peerIssuer = "", chainLen = 0,
        bundledVerify = tag, millis = SystemClock.elapsedRealtime() - started,
        error = error, impl = implName(impl)
    )

    /** 4.3 的报错文案极具误导性（"wrong version number" 其实是没 SNI），原样带回去。 */
    private fun brief(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300)

    private fun implName(impl: TlsStack.TlsImpl?): String = when (impl) {
        TlsStack.TlsImpl.CONSCRYPT -> "Conscrypt"
        TlsStack.TlsImpl.SYSTEM -> "系统 JSSE"
        else -> ""
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
    private fun collectCodec(p: Progress): Codec {
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
        val mimes = VIDEO_MIMES + AUDIO_MIMES
        for ((index, mime) in mimes.withIndex()) {
            // 每个探测要建一次解码器，17 个加起来通常 200~600ms，
            // 但在老机器上最坏能到 2 秒 —— 逐个报进度，条子才一直动。
            p.emit(
                62 + (index * 24) / mimes.size,
                "正在测试解码能力（${index + 1}/${mimes.size}）…"
            )
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
    fun upload(
        ctx: Context,
        endpoint: String,
        report: Report,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result {
        val url = endpoint.trimEnd('/') + PATH_REPORT
        // 上传阶段自己只需要两档进度：发出去、等回执。
        // 采集阶段的百分比已经走到 88~92，这里接在 94 → 99，最后 100 由调用方收尾。
        val progress = Progress(onProgress)
        val body = report.toJson().toString().toByteArray(Charsets.UTF_8)
        return try {
            // 反馈地址是用户自己填的：可能是另一台 nginx、可能只开 GCM。
            // 走 TlsConn 就自动「谈不拢换栈再试一次」，并把结论按域名记下来。
            val host = try { UrlKit.parse(url).host } catch (e: Throwable) { "" }
            if (url.startsWith("https", ignoreCase = true) && host.isNotEmpty()) {
                TlsConn.withRetry(ctx, TrustMode.ALL, host) { factory ->
                    post(ctx, url, body, factory, progress)
                }
            } else {
                post(ctx, url, body, null, progress)
            }
        } catch (e: Throwable) {
            Result.Fail(friendlyError(e))
        }
    }

    /** 真发一次。HTTP 层面的失败（非 2xx / `ok:false`）走返回值，不抛。 */
    private fun post(
        ctx: Context,
        url: String,
        body: ByteArray,
        factory: javax.net.ssl.SSLSocketFactory?,
        progress: Progress
    ): Result {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as java.net.HttpURLConnection
            // 生产环境一定是 https；本地联调（http://10.0.2.2:端口）也得能跑，
            // 所以这里按协议分流，不硬转 HttpsURLConnection。
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = factory
                    ?: TlsStack.socketFactory(ctx, TrustMode.ALL)
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
            conn.setFixedLengthStreamingMode(body.size)
            progress.emit(94, "正在上传（${body.size / 1024} KB）…")
            conn.outputStream.use { it.write(body) }
            progress.emit(97, "正在等待服务器确认…")

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
            // ⚠️ 握手谈不拢必须往外抛，否则 [TlsConn.withRetry] 看不到失败、
            //    换栈重试就成了死代码（第一版就是这么写的，线上等于没换过栈）。
            //    其余失败（HTTP 状态、超时、解析）照旧翻成人话返回。
            if (TlsStack.isHandshakeFailure(e)) throw e
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
        /**
         * 本机**启用**的套件清单（不是「支持」的 —— 启用了才算数）。
         *
         * 判定「服务器只开 GCM 而客户端没有 GCM」时，这一列是直接证据：
         * 里面一条 `*_GCM_*` 都没有，就说明是客户端侧的问题。
         */
        val cipherSuites: List<String>,
        /** 用户设的偏好（自动 / 锁 Conscrypt / 锁系统栈），见 [tlsImplName]。 */
        val impl: String,
        /**
         * **这个域名实际用的那条栈**（`TlsStack.chooseFor` 的结果）。
         *
         * 和 [impl] 分开记，因为「自动」模式下不同域名可能走不同栈：
         * `probeSystemFirst` 默认开 → 系统栈谈得拢的域名就用系统栈，
         * 只开 GCM 的域名会被记成 Conscrypt。只写一个 `impl` 的话，
         * 报告上会出现「impl=Conscrypt 但 hostMemory=system」这种自相矛盾的说法。
         */
        val implActual: String,
        /**
         * 上面那份套件清单是**哪条栈**报出来的（socket 类名）。
         *
         * 清单取自「自动模式下优先的那条栈」（有 Conscrypt 就是 Conscrypt），
         * 回答的是「这台机器**具备**什么能力」；而 [implActual] 回答的是
         * 「这次连接**用了**什么」。两个都要看：只开 GCM 的服务器上，
         * 能力有 GCM 但实际栈没有 → 结论是「客户端有能力，只是这个域名被记到了系统栈」。
         */
        val socketClass: String,
        val handshakes: List<Handshake>,
        val bundledAnchors: List<String>,
        /**
         * 按域名记下来的「哪条栈能通」，形如 `eb.atrct.cn=conscrypt`。
         *
         * 这条信息比 `impl` 更有用：`impl` 只说全局策略，
         * 这里说的是**这台机器上、对这些服务器、实际验证过的结论**。
         * 排查「某个域名老是握手失败」时，先看它在不在这张表里。
         */
        val hostMemory: List<String>,
        val note: String
    ) {
        fun toJson() = JSONObject().apply {
            put("defaultProtocols", JSONArray().apply { defaultProtocols.forEach { put(it) } })
            put("supportedCipherCount", supportedCipherCount)
            // 清单可能几十条，整份报告已经近百 KB 了，截一下；
            // 判 GCM/ChaCha20 只需要看有没有，不需要看全。
            put("cipherSuites", JSONArray().apply { cipherSuites.take(MAX_CIPHER_LIST).forEach { put(it) } })
            put("hasGcm", cipherSuites.any { it.contains("_GCM_") })
            put("hasChaCha20", cipherSuites.any { it.contains("CHACHA20", ignoreCase = true) })
            put("impl", impl)
            put("implActual", implActual)
            put("socketClass", socketClass)
            put("handshakes", JSONArray().apply { handshakes.forEach { put(it.toJson()) } })
            put("bundledAnchors", JSONArray().apply { bundledAnchors.forEach { put(it) } })
            put("hostMemory", JSONArray().apply { hostMemory.forEach { put(it) } })
            put("note", note)
        }
    }

    data class Handshake(
        val host: String, val withSni: Boolean, val ok: Boolean,
        val protocol: String, val cipherSuite: String,
        val peerSubject: String, val peerIssuer: String, val chainLen: Int,
        val bundledVerify: String, val millis: Long, val error: String,
        /** 这次握手实际用的栈（`Conscrypt` / `系统 JSSE`）；换过栈就记换后的那条。 */
        val impl: String = ""
    ) {
        fun toJson() = JSONObject().apply {
            put("host", host); put("withSni", withSni); put("ok", ok)
            put("protocol", protocol); put("cipherSuite", cipherSuite)
            put("peerSubject", peerSubject); put("peerIssuer", peerIssuer)
            put("chainLen", chainLen); put("bundledVerify", bundledVerify)
            put("millis", millis); put("error", error); put("impl", impl)
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
