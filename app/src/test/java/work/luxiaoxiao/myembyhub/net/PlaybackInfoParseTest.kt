package work.luxiaoxiao.myembyhub.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("DirectStream", plan?.playMethod)
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
    fun parsePlaybackPlan_fallsBackToStandardDirectPlayUrlWhenDirectStreamUrlMissing() {
        val body = """
            {
              "PlaySessionId": "sess2",
              "MediaSources": [
                { "Id": "ms1", "Container": "mkv", "DirectStreamUrl": "" }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "123", "", body)
        assertEquals(
            "https://tv.example.com/Videos/123/stream.mkv?Static=true&mediaSourceId=ms1&PlaySessionId=sess2",
            plan?.url
        )
        assertEquals("ms1", plan?.mediaSourceId)
        assertEquals("DirectPlay", plan?.playMethod)
        assertEquals("mkv", plan?.container)
    }

    @Test
    fun parsePlaybackPlan_usesExternalHttpPathWhenDirectPlayHttpSource() {
        val body = """
            {
              "PlaySessionId": "sess-http",
              "MediaSources": [
                {
                  "Id": "ms-http",
                  "Protocol": "Http",
                  "Path": "https://cdn.example.com/media/movie.mp4",
                  "SupportsDirectPlay": true
                }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "123", "", body)
        assertEquals("https://cdn.example.com/media/movie.mp4", plan?.url)
        assertEquals("DirectPlay", plan?.playMethod)
    }

    @Test
    fun parsePlaybackPlan_usesTranscodingUrlWhenDirectPlayUnsupported() {
        val body = """
            {
              "PlaySessionId": "sess-trans",
              "MediaSources": [
                {
                  "Id": "ms1",
                  "SupportsDirectPlay": false,
                  "SupportsDirectStream": false,
                  "TranscodingUrl": "/videos/123/master.m3u8?PlaySessionId=sess-trans"
                }
              ]
            }
        """.trimIndent()

        val plan = parsePlaybackPlan("https://tv.example.com", "123", "", body)
        assertEquals("https://tv.example.com/videos/123/master.m3u8?PlaySessionId=sess-trans", plan?.url)
        assertEquals("Transcode", plan?.playMethod)
    }

    @Test(expected = PlaybackInfoRejectedException::class)
    fun parsePlaybackPlan_throwsOnErrorCode() {
        val body = """{"ErrorCode":"RateLimitExceeded","MediaSources":[]}"""
        parsePlaybackPlan("https://tv.example.com", "ep1", "", body)
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

    @Test
    fun shouldFallbackFromPlaybackInfo_onlyAllows404Or405() {
        assertTrue(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 404 Not Found")))
        assertTrue(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 405 Method Not Allowed")))
        assertFalse(shouldFallbackFromPlaybackInfo(PlaybackInfoRejectedException("NotAllowed")))
        assertFalse(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 401")))
        assertFalse(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 403 Forbidden")))
        assertFalse(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 429 Too Many Requests")))
        assertFalse(shouldFallbackFromPlaybackInfo(IllegalStateException("HTTP 500 Internal Server Error")))
    }

    @Test
    fun authHeader_includesSanitizedFieldsAndOptionalToken() {
        val noToken = buildEmbyAuthHeader("MyEmbyHub", "Letv \"S50\"\nAir", "dev123", "1.0.20261127.1")
        assertEquals(
            "MediaBrowser Client=\"MyEmbyHub\", Device=\"Letv S50 Air\", DeviceId=\"dev123\", Version=\"1.0.20261127.1\"",
            noToken
        )
        val withToken = buildEmbyAuthHeader("MyEmbyHub", "S50", "dev123", "1.0", "tok999")
        assertTrue(withToken.endsWith(", Token=\"tok999\""))
    }

    @Test
    fun playbackInfoRequest_includesStandardParamsAndSubtitleProfiles() {
        val path = buildPlaybackInfoPath("item1", "u1", "ms/1", 600_000_000L)
        assertTrue(path.contains("UserId=u1"))
        assertTrue(path.contains("IsPlayback=true"))
        assertTrue(path.contains("MaxStreamingBitrate=120000000"))
        assertTrue(path.contains("MediaSourceId=ms%2F1"))
        assertTrue(path.contains("StartTimeTicks=600000000"))

        val json = JSONObject(buildPlaybackInfoPayload("u1", "ms/1", 600_000_000L))
        assertEquals("u1", json.getString("UserId"))
        assertEquals(600_000_000L, json.getLong("StartTimeTicks"))
        assertTrue(json.getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles").length() > 0)
    }

    @Test
    fun playbackReportBody_matchesEmbySpec() {
        val progress = JSONObject(
            buildPlaybackReportBody(
                path = "/Sessions/Playing/Progress",
                itemId = "ep1",
                mediaSourceId = "ms1",
                playSessionId = "ps1",
                positionTicks = 123456L,
                paused = true,
                eventName = "Pause",
                playMethod = "DirectStream"
            )
        )
        assertEquals("Pause", progress.getString("EventName"))
        assertEquals("DirectStream", progress.getString("PlayMethod"))
        assertTrue(progress.getBoolean("IsPaused"))
        assertEquals(123456L, progress.getLong("PositionTicks"))

        val stopped = JSONObject(
            buildPlaybackReportBody(
                path = "/Sessions/Playing/Stopped",
                itemId = "ep1",
                mediaSourceId = "ms1",
                playSessionId = "ps1",
                positionTicks = 999000L,
                paused = true,
                eventName = null,
                playMethod = "DirectStream"
            )
        )
        assertEquals(999000L, stopped.getLong("PositionTicks"))
        assertFalse(stopped.getBoolean("Failed"))
        assertFalse(stopped.has("IsPaused"))
    }

    @Test
    fun shouldAttachEmbyAuth_stripsAuthOnCrossOriginOrDowngrade() {
        val origin = UrlKit.parse("https://emby.example.com/Videos/1/stream?Static=true")
        assertTrue(shouldAttachEmbyAuth(origin, UrlKit.parse("https://emby.example.com/smartstrm?id=1")))
        assertFalse(shouldAttachEmbyAuth(origin, UrlKit.parse("https://cdn.other.net/file.mkv")))
        assertFalse(shouldAttachEmbyAuth(origin, UrlKit.parse("https://emby.example.com:8920/file.mkv")))
        assertFalse(shouldAttachEmbyAuth(origin, UrlKit.parse("http://emby.example.com/file.mkv")))
    }
}
