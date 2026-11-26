package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TlsHostMemory] 的纯函数部分。
 *
 * 只测编码/解码/归一化 —— 存 SharedPreferences 的那几个函数要 Context，
 * 在 JVM 单测里跑不了（项目里没有 Robolectric，也不想为了这个引进来）。
 */
class TlsHostMemoryTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val map = linkedMapOf("eb.atrct.cn" to "conscrypt", "tt.wedg.top" to "system")
        val raw = TlsHostMemory.encode(map)
        val back = TlsHostMemory.decode(raw)
        assertEquals(2, back.size)
        assertEquals("conscrypt", back["eb.atrct.cn"])
        assertEquals("system", back["tt.wedg.top"])
    }

    @Test
    fun decodeKeepsInsertionOrder() {
        // 淘汰是按写入顺序砍最早的，所以顺序必须保住 —— 这个性质不能靠 HashMap 碰运气
        val back = TlsHostMemory.decode("a.com=system;b.com=conscrypt;c.com=system")
        assertEquals(listOf("a.com", "b.com", "c.com"), back.keys.toList())
    }

    @Test
    fun decodeSkipsGarbageWithoutLosingTheRest() {
        // 手改坏的、版本升级留下的残片，都只该丢自己那一段，不能把整张表带走
        val back = TlsHostMemory.decode("good.com=conscrypt;;=broken;noeq;also=;ok.com=system")
        assertEquals(2, back.size)
        assertEquals("conscrypt", back["good.com"])
        assertEquals("system", back["ok.com"])
        assertNull(back["noeq"])
    }

    @Test
    fun decodeHandlesNullAndEmpty() {
        assertTrue(TlsHostMemory.decode(null).isEmpty())
        assertTrue(TlsHostMemory.decode("").isEmpty())
    }

    @Test
    fun encodeEmptyMap() {
        assertEquals("", TlsHostMemory.encode(emptyMap()))
    }

    @Test
    fun normalizeStripsPortCaseAndSpace() {
        // `https://EB.atrct.cn:8443/` 和 `eb.atrct.cn` 在记录里必须是同一个 key，
        // 否则同一个服务器会攒出一堆重复条目，24 个名额很快就满了
        assertEquals("eb.atrct.cn", TlsHostMemory.normalize("  EB.atrct.cn:8443 "))
        assertEquals("eb.atrct.cn", TlsHostMemory.normalize("eb.atrct.cn"))
        assertEquals("", TlsHostMemory.normalize("   "))
    }

    @Test
    fun roundTripSurvivesNormalizedKeys() {
        val map = linkedMapOf(TlsHostMemory.normalize("EB.atrct.cn:443") to "conscrypt")
        val back = TlsHostMemory.decode(TlsHostMemory.encode(map))
        assertEquals("conscrypt", back["eb.atrct.cn"])
    }

    @Test
    fun upsertSkipsWriteWhenUnchangedAndEvictsOldest() {
        val map = linkedMapOf("a.com" to "system", "b.com" to "conscrypt")
        // 值没变：不该触发写盘
        assertFalse(TlsHostMemory.upsert(map, "a.com", "system"))
        assertEquals(listOf("a.com", "b.com"), map.keys.toList())

        // 值变了：返回 true 且挪到队尾刷新顺序
        assertTrue(TlsHostMemory.upsert(map, "a.com", "conscrypt"))
        assertEquals(listOf("b.com", "a.com"), map.keys.toList())
        assertEquals("conscrypt", map["a.com"])

        // 超过 MAX_HOSTS 时淘汰最早的
        for (i in 1..TlsHostMemory.MAX_HOSTS) {
            assertTrue(TlsHostMemory.upsert(map, "h$i.com", "system"))
        }
        assertEquals(TlsHostMemory.MAX_HOSTS, map.size)
        assertNull(map["b.com"])
        assertNull(map["a.com"])
    }
}
