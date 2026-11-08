package com.mk.iptv

import com.mk.iptv.airplay.AirPlayDevice
import com.mk.iptv.airplay.AirPlaySession
import com.mk.iptv.data.Creds
import com.mk.iptv.data.XtreamApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the Xtream client against payload shapes seen on real panels (strings for numbers, nulls, object-keyed seasons…). */
class XtreamApiTest {
    private lateinit var server: MockWebServer
    private val api = XtreamApi(OkHttpClient())
    private lateinit var creds: Creds

    private val responses = mapOf(
        null to """{"user_info":{"username":"mk","auth":1,"status":"Active","exp_date":"1804550400","active_cons":"1","max_connections":"3"},"server_info":{"url":"x"}}""",
        "get_live_categories" to """[{"category_id":"1","category_name":"Sports","parent_id":0},{"category_id":"9","category_name":"XXX Adults"}]""",
        "get_live_streams" to """[{"num":1,"name":"MK Sports 1","stream_id":"101","stream_icon":"http://img/x.png","epg_channel_id":"sp1","category_id":"1"},{"num":"2","name":"News","stream_id":102,"stream_icon":"","category_id":null},{"name":"broken"}]""",
        "get_vod_categories" to """[{"category_id":"5","category_name":"Action"}]""",
        "get_vod_streams" to """[{"num":1,"name":"Neon Tide","stream_id":555,"stream_icon":"http://img/p.jpg","rating":"8.1","added":"1730000000","category_id":"5","container_extension":"mkv"},{"name":"No Rating","stream_id":"556","rating":"","rating_5based":4,"added":null,"category_id":"5"}]""",
        "get_series_categories" to """[{"category_id":"7","category_name":"Drama"}]""",
        "get_series" to """[{"num":1,"name":"Overcast","series_id":77,"cover":"http://img/c.jpg","plot":"Storms.","cast":"A, B","genre":"Mystery","releaseDate":"2024-03-01","last_modified":"1731000000","rating":"8.3","backdrop_path":["http://img/b.jpg"],"category_id":"7"}]""",
        "get_vod_info" to """{"info":{"plot":"A botanist returns.","cast":"Lena","director":"Kim","duration":"01:58:00","releasedate":"2025-05-01","rating":"7.9","backdrop_path":["http://img/bd.jpg"],"youtube_trailer":"abc123","mpaa_rating":"16+"},"movie_data":{"stream_id":555,"container_extension":"mp4"}}""",
        "get_series_info" to """{"info":{"name":"Overcast","plot":"Storms.","cover":"http://img/c.jpg","backdrop_path":[]},"episodes":{"2":[{"id":"9002","episode_num":"1","title":"S2E1","container_extension":"mkv","info":{"plot":"p","movie_image":"http://img/e.jpg","duration":"00:52:00"},"season":2}],"1":[{"id":"9001b","episode_num":2,"title":"Two","container_extension":"mp4","info":[]},{"id":"9001","episode_num":1,"title":"One","container_extension":"mp4","info":{"duration_secs":2880}}]}}""",
        "get_short_epg" to """{"epg_listings":[{"title":"TWF0Y2ggRGF5","description":"TGl2ZQ==","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]}""",
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.queryParameter("password") != "secret") return MockResponse().setBody("""{"user_info":{"auth":0}}""")
                val body = responses[url.queryParameter("action")] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(body)
            }
        }
        server.start()
        creds = Creds(XtreamApi.normalizeServer(server.url("/").toString()), "mk", "secret")
    }

    @After fun tearDown() = server.shutdown()

    @Test fun normalizesServerUrls() {
        assertEquals("http://line.tv:8080", XtreamApi.normalizeServer("line.tv:8080"))
        assertEquals("http://line.tv:8080", XtreamApi.normalizeServer(" http://line.tv:8080/ "))
        assertEquals("http://line.tv:8080", XtreamApi.normalizeServer("http://line.tv:8080/player_api.php"))
        assertEquals("https://line.tv", XtreamApi.normalizeServer("https://line.tv:443/get.php"))
        assertEquals("http://line.tv/panel", XtreamApi.normalizeServer("http://line.tv/panel/"))
    }

    @Test fun authenticates() = runTest {
        val ok = api.authenticate(creds)
        assertTrue(ok.ok)
        assertEquals("Active", ok.status)
        assertEquals(1804550400000L, ok.expiry)
        assertEquals(3, ok.maxCons)
        val bad = api.authenticate(creds.copy(password = "nope"))
        assertFalse(bad.ok)
        assertEquals("Wrong username or password", bad.message)
    }

    @Test fun parsesCatalog() = runTest {
        val live = api.liveStreams(creds)
        assertEquals(2, live.size)
        assertEquals(101, live[0].id)
        assertNull(live[1].logo)
        assertEquals(2, live[1].num)

        val vod = api.vodStreams(creds)
        assertEquals(2, vod.size)
        assertEquals("mkv", vod[0].ext)
        assertEquals(8.1, vod[0].rating!!, 0.001)
        assertEquals(8.0, vod[1].rating!!, 0.001) // 5-based fallback
        assertEquals("mp4", vod[1].ext)

        val series = api.series(creds)
        assertEquals("http://img/b.jpg", series[0].backdrop)
        assertEquals(2, api.liveCategories(creds).size)
    }

    @Test fun parsesVodAndSeriesInfo() = runTest {
        val vi = api.vodInfo(creds, 555)
        assertEquals("A botanist returns.", vi.plot)
        assertEquals("abc123", vi.trailer)
        assertEquals("mp4", vi.ext)

        val si = api.seriesInfo(creds, 77)
        assertEquals(listOf(1, 2), si.seasons)
        assertEquals(listOf("9001", "9001b"), si.episodes[1]!!.map { it.id }) // sorted by episode number
        assertEquals("48m", si.episodes[1]!![0].duration)
        assertEquals("mkv", si.episodes[2]!![0].ext)
        assertEquals("${creds.server}/series/mk/secret/9002.mkv", XtreamApi.episodeUrl(creds, "9002", "mkv"))
    }

    @Test fun decodesBase64Epg() = runTest {
        val epg = api.shortEpg(creds, 101)
        assertEquals("Match Day", epg[0].title)
        assertEquals("Live", epg[0].description)
        assertEquals(1700000000000L, epg[0].start)
    }

    @Test fun airPlaySendsPlayRequest() = runTest {
        val tv = MockWebServer()
        tv.enqueue(MockResponse().setResponseCode(200))
        tv.enqueue(MockResponse().setBody("duration: 7200.000000\nposition: 61.5\n"))
        tv.enqueue(MockResponse().setResponseCode(403))
        tv.start()
        val s = AirPlaySession(AirPlayDevice("Living Room", tv.hostName, tv.port), OkHttpClient())
        s.play("http://line.tv/movie/mk/secret/555.mp4", 0.25)
        val req = tv.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/play", req.path)
        assertEquals("MediaControl/1.0", req.getHeader("User-Agent"))
        assertNotNull(req.getHeader("X-Apple-Session-ID"))
        assertEquals("Content-Location: http://line.tv/movie/mk/secret/555.mp4\nStart-Position: 0.250000\n", req.body.readUtf8())
        assertEquals(61.5 to 7200.0, s.position())
        val err = runCatching { s.setRate(false) }.exceptionOrNull()
        assertTrue(err?.message!!.contains("pairing"))
        tv.shutdown()
    }
}
