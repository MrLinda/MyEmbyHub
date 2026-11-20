package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EpisodeRef] 的编解码测试。
 *
 * 为什么要单测：这份 JSON 是**跨 Activity 传递**的（详情页 → 播放器），
 * 编码端和解码端一旦对不上，表现是「切集功能静默失效」——
 * 播放器拿到空表就当「这不是剧集」，用户按上下键毫无反应，
 * 而 logcat 里只有一句「没有剧集列表」。这种 bug 在真机上极难定位，
 * 必须在编译期就钉死。
 */
class EpisodeRefTest {

    private fun ep(
        id: String = "e1",
        name: String = "谢画楼跪求一死",
        index: Int = 3,
        parent: Int = 1,
        msId: String = "ms-1",
        resume: Long = 1_234_000L,
        runtime: Long = 2_700_000L
    ) = EpisodeRef(id, name, index, parent, msId, resume, runtime)

    @Test
    fun roundTripKeepsEveryField() {
        val src = listOf(ep(), ep(id = "e2", index = 4, resume = 0L))
        val back = EpisodeRef.decode(EpisodeRef.encode(src))
        assertEquals(src, back)
    }

    @Test
    fun emptyListEncodesToEmptyArrayAndDecodesBack() {
        assertEquals("[]", EpisodeRef.encode(emptyList()))
        assertTrue(EpisodeRef.decode("[]").isEmpty())
    }

    @Test
    fun nullOrBlankDecodesToEmpty() {
        assertTrue(EpisodeRef.decode(null).isEmpty())
        assertTrue(EpisodeRef.decode("").isEmpty())
    }

    @Test
    fun garbageDecodesToEmptyInsteadOfThrowing() {
        // 用户从 adb 手敲 intent 时很容易传错，绝不能因此崩掉播放页
        assertTrue(EpisodeRef.decode("not json").isEmpty())
        assertTrue(EpisodeRef.decode("{\"a\":1}").isEmpty())
    }

    @Test
    fun oneBadEntryDoesNotKillTheRest() {
        val raw = """[{"i":"a","n":"A","x":1},{"n":"没有id"},{"i":"c","n":"C","x":3}]"""
        val back = EpisodeRef.decode(raw)
        assertEquals(2, back.size)
        assertEquals("a", back[0].id)
        assertEquals("c", back[1].id)
    }

    @Test
    fun negativeResumeIsClampedToZero() {
        val back = EpisodeRef.decode("""[{"i":"a","n":"A","r":-5000,"t":-1}]""")
        assertEquals(1, back.size)
        assertEquals(0L, back[0].resumeMs)
        assertEquals(0L, back[0].runtimeMs)
    }

    @Test
    fun labelPrefixesEpisodeNumber() {
        assertEquals("第 3 集 · 谢画楼跪求一死", ep().label())
        // 名字本身就是「第 3 集」时不要重复成「第 3 集 · 第 3 集」
        assertEquals("第 3 集", ep(name = "第 3 集").label())
        // 没集号的（电影、特别篇）原样显示
        assertEquals("谢画楼跪求一死", ep(index = 0).label())
        assertEquals("第 5 集", ep(index = 5, name = "").label())
    }

    @Test
    fun ofMapsTicksToMilliseconds() {
        val item = MediaItem(
            id = "777",
            name = "第 2 集",
            type = "Episode",
            isFolder = false,
            indexNumber = 2,
            parentIndexNumber = 1,
            positionTicks = 9_876_000_000L, // 987.6 秒
            runtimeTicks = 27_000_000_000L, // 2700 秒
            mediaSourceId = "ms-9"
        )
        val ref = EpisodeRef.of(item)
        assertEquals("777", ref.id)
        assertEquals(2, ref.indexNumber)
        assertEquals(1, ref.parentIndexNumber)
        assertEquals("ms-9", ref.mediaSourceId)
        assertEquals(987_600L, ref.resumeMs)
        assertEquals(2_700_000L, ref.runtimeMs)
    }

    @Test
    fun largeSeriesStaysUnderBinderBudget() {
        // 4.3 的 Binder 事务上限约 500KB；300 集是 Emby 单季的常见上限。
        // 这里留足余量断言 300 集 < 200KB，超了就是字段加多了要重新算账。
        val many = (1..300).map {
            ep(id = "episode-$it", index = it, name = "第 $it 集的名字稍微长一点")
        }
        val size = EpisodeRef.encode(many).toByteArray(Charsets.UTF_8).size
        assertTrue("300 集序列化后 $size 字节，太大了", size < 200_000)
        assertEquals(300, EpisodeRef.decode(EpisodeRef.encode(many)).size)
    }
}
