package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Udf] 容器快路径探测与 ISO 魔数判定的纯函数测试。
 */
class UdfProbeTest {

    @Test
    fun knownNonIsoContainersMatchFastPath() {
        // MKV / WebM (EBML)
        assertTrue(Udf.looksLikeKnownNonIso(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        // MP4 (ftyp at offset 4)
        assertTrue(
            Udf.looksLikeKnownNonIso(
                byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
            )
        )
        // AVI (RIFF)
        assertTrue(Udf.looksLikeKnownNonIso("RIFF\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)))
        // FLV
        assertTrue(Udf.looksLikeKnownNonIso(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 0x01)))
        // Ogg
        assertTrue(Udf.looksLikeKnownNonIso("OggS".toByteArray(Charsets.US_ASCII)))
        // ASF / WMV
        assertTrue(Udf.looksLikeKnownNonIso(byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75)))
    }

    @Test
    fun isoHeaderAndShortBuffersDoNotMatchKnownNonIso() {
        assertFalse(Udf.looksLikeKnownNonIso(ByteArray(16)))
        assertFalse(Udf.looksLikeKnownNonIso(byteArrayOf(0x1A, 0x45, 0xDF.toByte())))
        assertFalse(Udf.looksLikeKnownNonIso(byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte()), 7))
    }

    @Test
    fun looksLikeIsoChecksBea01AtOffset() {
        val vrs = ByteArray(Udf.VRS_OFFSET + Udf.VRS_BYTES)
        val bea = "BEA01".toByteArray(Charsets.US_ASCII)
        System.arraycopy(bea, 0, vrs, Udf.VRS_OFFSET + 1, bea.size)

        assertTrue(Udf.looksLikeIso(vrs, Udf.VRS_OFFSET))
        assertFalse(Udf.looksLikeIso(ByteArray(16), Udf.VRS_OFFSET))
        assertFalse(Udf.looksLikeIso(ByteArray(Udf.VRS_OFFSET + Udf.VRS_BYTES), Udf.VRS_OFFSET))
    }
}
