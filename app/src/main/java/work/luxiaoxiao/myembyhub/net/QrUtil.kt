package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.EnumMap

/**
 * 二维码生成 + 电视局域网 IP 探测。
 *
 * 登录页用二维码把「在手机上输入账号密码」这件事搬到手机上做 ——
 * 这台电视没有软键盘，用遥控器敲账号密码极其痛苦。
 */
object QrUtil {

    private const val TAG = "QrUtil"

    /** 生成二维码位图。失败返回 null。 */
    fun encode(text: String, sizePx: Int): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            // 容错等级调高一点：电视屏幕反光/远距离时更耐扫
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1

            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: WriterException) {
            Log.e(TAG, "二维码生成失败", e)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "二维码生成异常", e)
            null
        }
    }

    /**
     * 探测电视在局域网里的 IP。
     *
     * 优先用 WiFi 的 DHCP 信息（电视基本都走 WiFi），
     * 拿不到再枚举网络接口。
     */
    fun localIp(ctx: Context): String {
        // 1) WiFi DHCP
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo
            val ip = dhcp?.ipAddress
            if (ip != null && ip != 0) {
                val s = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff,
                    (ip shr 24) and 0xff
                )
                if (!s.startsWith("0.")) return s
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读 WiFi DHCP 失败", e)
        }

        // 2) 枚举网卡
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val s = addr.hostAddress ?: continue
                        if (s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("172.")) {
                            return s
                        }
                    }
                }
            }
            ""
        } catch (e: Throwable) {
            Log.w(TAG, "枚举网卡失败", e)
            ""
        }
    }

    /** 局域网内访问自己的地址。 */
    fun selfUrl(ctx: Context, port: Int, path: String = "/"): String {
        val ip = localIp(ctx)
        if (ip.isEmpty()) return ""
        return "http://$ip:$port$path"
    }
}
