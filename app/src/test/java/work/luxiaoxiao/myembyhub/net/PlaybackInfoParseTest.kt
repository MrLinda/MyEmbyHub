package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackInfoParseTest {

    @Test
    fun parsePlaybackPlan_prefersSmartstrmDirectStreamUrl() {
        val body = """
            {
              "PlaySessionId": "sess123",
              "MediaSources": [
                {
                  "Id": "ms1",
                  "DirectStreamUrl": "/smartstrm?item_id=ep1&media_id=ms1"
                }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "ep1", "", body)
        assertEquals("https://tv.example.com/smartstrm?item_id=ep1&media_id=ms1", plan?.url)
        assertEquals("ms1", plan?.mediaSourceId)
        assertEquals("sess123", plan?.playSessionId)
    }

    @Test
    fun resolveServerPath_handlesSubpathAndAbsoluteUrls() {
        assertEquals(
            "https://example.com/emby/videos/1/stream.mkv?Static=true",
            resolveServerPath("https://example.com/emby", "/videos/1/stream.mkv?Static=true")
        )
        assertEquals(
            "https://example.com/emby/videos/1/stream.mkv?Static=true",
            resolveServerPath("https://example.com/emby", "/emby/videos/1/stream.mkv?Static=true")
        )
        assertEquals(
            "https://cdn.example.com/a.mkv",
            resolveServerPath("https://tv.example.com", "https://cdn.example.com/a.mkv")
        )
    }

    @Test
    fun parsePlaybackPlan_fallsBackWhenDirectStreamUrlMissing() {
        val body = """
            {
              "PlaySessionId": "sess2",
              "MediaSources": [
                { "Id": "ms1", "DirectStreamUrl": "" }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "123", "", body)
        assertEquals("https://tv.example.com/Videos/123/stream?static=true&mediaSourceId=ms1", plan?.url)
        assertEquals("ms1", plan?.mediaSourceId)
    }

    @Test
    fun parsePlaybackPlan_matchesPreferredMediaSourceId() {
        val body = """
            {
              "PlaySessionId": "sess3",
              "MediaSources": [
                { "Id": "ms1", "DirectStreamUrl": "/smartstrm?m=1" },
                { "Id": "ms2", "DirectStreamUrl": "/smartstrm?m=2" }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "ep1", "ms2", body)
        assertEquals("https://tv.example.com/smartstrm?m=2", plan?.url)
        assertEquals("ms2", plan?.mediaSourceId)
    }

    @Test
    fun parsePlaybackPlan_returnsNullWhenMediaSourcesEmpty() {
        val body = """{"PlaySessionId":"s","MediaSources":[]}"""
        assertNull(parsePlaybackPlan("https://tv.example.com", "ep1", "", body))
    }
}
