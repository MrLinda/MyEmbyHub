package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏的回归测试。
 *
 * 这几条不写测试很容易被「顺手改回去」——比如为了排查方便把 SSID 全量带上，
 * 改的时候没人会注意到，但它一旦发出去就收不回来了。
 */
class PiiMaskTest {

    @Test
    fun ssid_keepsTwoCharsAndMasksRest() {
        // 前两个字符留着能认出「CMCC- / TP-LINK_」这类运营商 / 路由器的默认前缀，
        // 星号固定压到 6 个以内，长度不随原名增长
        assertEquals("CM******", PiiMask.ssid("CMCC-Home"))
        assertEquals("TP******", PiiMask.ssid("TP-LINK_5G"))
    }

    @Test
    fun ssid_shortAndEmpty() {
        assertEquals("", PiiMask.ssid(""))
        assertEquals("**", PiiMask.ssid("AB"))
        assertEquals("AB*", PiiMask.ssid("ABC"))
    }

    @Test
    fun ssid_stripsQuotes() {
        // WifiInfo.getSSID() 在 4.3 上带引号，别把引号当名字的一部分
        assertEquals("Ho*****", PiiMask.ssid("\"HomeNet\""))
    }

    @Test
    fun ssid_unknownPermissionIsDropped() {
        assertEquals("", PiiMask.ssid("<unknown ssid>"))
        assertEquals("", PiiMask.ssid("unknown"))
    }

    @Test
    fun ssid_starCountIsCapped() {
        // 长名字不能靠星号个数反推出原长度
        val masked = PiiMask.ssid("a-very-long-wifi-network-name")
        assertEquals("a-" + "*".repeat(6), masked)
    }

    @Test
    fun ip_keepsFirstTwoOctets() {
        assertEquals("192.168.x.x", PiiMask.ip("192.168.31.142"))
        assertEquals("10.0.x.x", PiiMask.ip("10.0.2.15"))
        assertEquals("", PiiMask.ip(""))
        assertEquals("x.x.x.x", PiiMask.ip("not-an-ip"))
    }

    @Test
    fun dns_reportsShapeOnly() {
        assertEquals("", PiiMask.dns(""))
        assertTrue(PiiMask.dns("198.18.0.2").contains("fake-ip"))
        assertTrue(PiiMask.dns("198.19.1.1").contains("fake-ip"))
        assertEquals("已配置（非 fake-ip 段）", PiiMask.dns("223.5.5.5"))
    }

    @Test
    fun maskedOutputNeverContainsOriginalHost() {
        // 端到端的断言：脱敏后的三件套里不该还能拼回原来的定位信息
        val ssid = PiiMask.ssid("ZhangJia-Home-2.4G")
        val ip = PiiMask.ip("192.168.31.142")
        val dns = PiiMask.dns("192.168.31.1")
        val all = "$ssid|$ip|$dns"
        assertTrue(!all.contains("ZhangJia"))
        assertTrue(!all.contains("31.142"))
        assertTrue(!all.contains("192.168.31.1"))
    }
}
