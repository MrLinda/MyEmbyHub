package work.luxiaoxiao.myembyhub.net

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 最近一次播放的现场快照。
 *
 * 为什么要有它：用户说「这部片播不出来」时，光有设备信息没用 ——
 * 得知道是哪部片、什么容器/编码、解码器有没有建起来、有没有出第一帧。
 * [work.luxiaoxiao.myembyhub.ui.PlayerActivity] 在几个关键回调里往这里写，
 * 反馈上报时整份带走。
 *
 * 全是**内存态**（不落盘）：进程被杀就没了，但那正好 —— 报的是「刚才那次」。
 */
object PlaybackSnapshot {

    @Volatile var lastItemTitle: String = ""
    @Volatile var lastItemId: String = ""
    @Volatile var container: String = ""
    @Volatile var videoCodec: String = ""
    @Volatile var audioCodec: String = ""
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var bitrateKbps: Int = 0
    @Volatile var decoderName: String = ""
    @Volatile var firstFrame: Boolean = false
    @Volatile var errorCode: String = ""
    @Volatile var errorMessage: String = ""

    /** 开始播一个新条目时先清干净，否则会把上一条的报错带过来。 */
    fun reset(title: String, itemId: String) {
        lastItemTitle = title
        lastItemId = itemId
        container = ""
        videoCodec = ""
        audioCodec = ""
        width = 0
        height = 0
        bitrateKbps = 0
        decoderName = ""
        firstFrame = false
        errorCode = ""
        errorMessage = ""
    }
}

/**
 * 最近若干条错误的环形记录。
 *
 * 崩溃、加载失败、播放异常都往这里丢一条，反馈上报时一起带走 ——
 * 电视上没法看 logcat，这是唯一能把现场带回来的路。
 *
 * 不落盘（内存里 20 条），也不做上传队列：上报是用户主动点的。
 */
object ErrorLog {

    private const val CAPACITY = 20
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    data class Entry(val at: String, val where: String, val message: String)

    private val items = ArrayDeque<Entry>()

    @Volatile
    private var installed = false

    /**
     * 装全局未捕获异常钩子（幂等）。
     *
     * 只在**保留原 handler 的前提下**插一层：原 handler 负责把进程干掉并写
     * `/data/anr`、弹「应用已停止」，我们不能把它顶掉。
     */
    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record("crash:${thread.name}", "${e.javaClass.simpleName}: ${e.message}")
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(where: String, message: String?) {
        val msg = (message ?: "").ifEmpty { "（没有消息）" }
        synchronized(items) {
            if (items.size >= CAPACITY) items.pollFirst()
            items.addLast(Entry(stamp.format(Date()), where, msg.take(600)))
        }
    }

    fun snapshot(): List<Entry> = synchronized(items) { items.toList() }

    fun clear() = synchronized(items) { items.clear() }
}
