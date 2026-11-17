package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateManager.candidates] 的回归测试。
 *
 * 这段逻辑决定「电视上到底去哪儿取更新」，写错了不会有编译错误，
 * 只会在真机上表现为「检查更新一直转圈然后失败」，所以单测盯住它。
 */
class UpdateCandidatesTest {

    private val githubManifest =
        "https://github.com/someone/myembyhub/releases/latest/download/version.json"

    @Test
    fun githubUrl_expandsToAllMirrorsPlusDirect() {
        val list = UpdateManager.candidates(githubManifest)
        // 前缀数 + 直连，一个都不能少
        assertEquals(UpdateManager.MIRROR_PREFIXES.size, list.size)
        // 每个候选都是「前缀 + 原始地址」，直连那条就是原始地址本身
        UpdateManager.MIRROR_PREFIXES.forEachIndexed { i, prefix ->
            assertEquals(prefix + githubManifest, list[i])
        }
        // 最后一条必须是直连，镜像全挂时还有兜底
        assertEquals(githubManifest, list.last())
    }

    @Test
    fun githubUrl_directCandidateEqualsOriginal() {
        assertTrue(UpdateManager.candidates(githubManifest).contains(githubManifest))
    }

    @Test
    fun rawGithubUrl_alsoMirrored() {
        val raw = "https://raw.githubusercontent.com/someone/repo/main/version.json"
        assertEquals(UpdateManager.MIRROR_PREFIXES.size, UpdateManager.candidates(raw).size)
    }

    @Test
    fun nonGithubUrl_isNotMirrored() {
        // 自建静态服务 / 内网地址套镜像没有意义，只会把请求发到不相干的站点
        val lan = "http://192.168.1.50:8765/version.json"
        assertEquals(listOf(lan), UpdateManager.candidates(lan))

        val own = "https://update.example.com/version.json"
        assertEquals(listOf(own), UpdateManager.candidates(own))
    }

    @Test
    fun surroundingWhitespace_isTrimmed() {
        // 用户手填地址时很容易多带一个空格/换行
        val padded = "  $githubManifest\n"
        assertEquals(UpdateManager.candidates(githubManifest), UpdateManager.candidates(padded))
    }

    @Test
    fun release_isNewerThanComparesStrictly() {
        val r = UpdateManager.Release(
            versionCode = 1794903737L,
            versionName = "1.0.20261117-162217",
            url = "https://example.com/a.apk",
            size = 100,
            notes = ""
        )
        assertTrue(r.isNewerThan(1794903736L))
        // 相等不算新版本 —— 否则每次检查都会提示「有新版」
        assertTrue(!r.isNewerThan(1794903737L))
        assertTrue(!r.isNewerThan(1794903738L))
    }
}