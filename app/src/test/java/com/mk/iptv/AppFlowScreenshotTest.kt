package com.mk.iptv

import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.mk.iptv.data.CatalogState
import com.mk.iptv.data.Crypto
import com.mk.iptv.data.DownloadMeta
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.XtreamApi
import com.mk.iptv.download.DownloadsRepository
import com.mk.iptv.player.PlayerActivity
import com.mk.iptv.ui.Playback
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * Drives the real app UI (Robolectric, native graphics) against [FakeXtream] and records screenshots
 * into app/build/shots. Also verifies the download pipeline end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AppFlowScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: MockWebServer
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val graph get() = MkApp.graph

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = FakeXtream { server.url("/").toString().trimEnd('/') }
        server.start()
        val srv = XtreamApi.normalizeServer(server.url("/").toString())
        val pl = Playlist(id = "home", name = "Home Server", server = srv, username = "mk", passwordEnc = Crypto.encrypt("secret"), createdAt = 1)
        val backup = Playlist(id = "backup", name = "Backup Line", server = srv, username = "mk_backup", passwordEnc = Crypto.encrypt("secret"), createdAt = 2,
            status = "Active", expiry = 1798761600000, activeCons = 0, maxCons = 1, liveCount = 2960, movieCount = 9410, seriesCount = 1722, lastSync = System.currentTimeMillis() - 86_400_000)
        graph.store.update {
            it.copy(
                playlists = listOf(pl, backup),
                profiles = listOf(
                    Profile("mk", "MK", 0, playlistIds = listOf("home", "backup"), activePlaylistId = "home"),
                    Profile("sara", "Sara", 1, playlistIds = listOf("home")),
                    Profile("kids", "Kids", 2, kids = true, playlistIds = listOf("home")),
                    Profile("guest", "Guest", 3, playlistIds = listOf("backup")),
                ),
                lastProfileId = "mk",
                favorites = mapOf("mk" to listOf("vod:501", "series:71", "vod:506", "live:101", "live:104", "series:73")),
            )
        }
        // Watch history: a movie half watched, Overcast E1 watched + E2 in progress, Lantern Society E1 finished (-> Up next).
        val now = System.currentTimeMillis()
        fun pr(key: String, pos: Long, dur: Long, ago: Long, kind: String, title: String, img: String, sid: Int? = null, sub: String? = null) =
            com.mk.iptv.data.Progress(key, pos, dur, now - ago, title, server.url("/img/$img.jpg").toString(), kind, sid, sub, key.substringAfter(':'), "mp4", "home")
        graph.store.saveProgress("mk", pr("vod:501", 4_200_000, 7_080_000, 60_000, "movie", "The Last Orchard", "orchard_wide"))
        graph.store.saveProgress("mk", pr("vod:503", 1_100_000, 6_500_000, 90_000, "movie", "Ember Protocol", "ember_wide"))
        graph.store.saveProgress("mk", pr("ep:1101", 2_640_000, 2_640_000, 300_000, "episode", "Overcast", "overcast_wide", 71, "S1:E1 · The Low Front"))
        graph.store.saveProgress("mk", pr("ep:1102", 1_150_000, 2_820_000, 30_000, "episode", "Overcast", "salt_wide", 71, "S1:E2 · Pressure"))
        graph.store.saveProgress("mk", pr("ep:0101", 2_640_000, 2_640_000, 120_000, "episode", "The Lantern Society", "lantern_wide", 70, "S1:E1 · The Low Front"))
    }

    @After fun tearDown() { server.shutdown() }

    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out; catalog=${graph.catalog.state.value}; route profile=${graph.store.data.value.lastProfileId}" }
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
        }
    }

    private var prefix = ""
    private fun shot(name: String) = captureScreenRoboImage(File(out, "$prefix$name.png").path)
    private fun click(text: String) = compose.onAllNodesWithText(text, substring = false).onFirst().performClick()
    private fun clickDesc(desc: String) = compose.onAllNodesWithContentDescription(desc).onFirst().performClick()

    @Test fun fullFlow() = runFlow("")

    /** Unfolded foldable inner display (e.g. Galaxy Z Fold / Pixel Fold): two-pane & grid layouts. */
    @Test @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun unfoldedFlow() = runFlow("fold-")

    private fun runFlow(prefix: String) {
        this.prefix = prefix
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        settle(800)
        shot("01-profiles")

        click("MK")
        waitFor { graph.catalog.state.value is CatalogState.Ready }
        settle(14000)
        shot("02-movies")

        compose.onRoot().performTouchInput { swipeUp(startY = bottom * .8f, endY = bottom * .35f, durationMillis = 900) }
        settle(2500)
        shot("03-movies-rows")

        clickDesc("The Last Orchard")
        settle(2500)
        shot("04-preview")
        Espresso.pressBack()
        settle(800)

        click("Series")
        settle(12000)
        shot("05-series")
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * .8f, endY = bottom * .35f, durationMillis = 900) }
        settle(3000)
        shot("05b-series-continue")

        clickDesc("Overcast")
        settle(1500)
        click("Episodes & Play")
        settle(10000)
        shot("06-series-detail")

        // ---- real downloads through Media3 DownloadManager ----
        val pl = graph.store.playlist("home")!!
        val dl = graph.downloads
        val overcast = graph.catalog.current!!.series.first { it.name == "Overcast" }
        listOf("1101" to 1, "1102" to 2, "1103" to 3, "1201" to 1).forEachIndexed { i, (epId, n) ->
            val s = if (i < 3) 1 else 2
            val id = DownloadsRepository.episodeId("home", epId)
            dl.manager.addDownload(dl.requestFor(id, DownloadMeta("episode", "home", listOf("The Low Front", "Pressure", "The Drowned Bell", "The Low Front")[i], "${server.url("/img/")}${listOf("overcast", "salt", "north", "overcast")[i]}_wide.jpg", null, overcast.id, "Overcast", overcast.cover, s, n, url = DownloadsRepository.episodeUrl("home", epId, "mp4"))))
        }
        graph.catalog.current!!.vod.take(3).forEachIndexed { i, v ->
            val id = DownloadsRepository.movieId("home", v.id)
            val req = dl.requestFor(id, DownloadMeta("movie", "home", v.name, v.poster, url = DownloadsRepository.movieUrl("home", v.id, "mp4")))
            if (i == 2) dl.manager.addDownload(req, 1) else dl.manager.addDownload(req)
        }
        dl.manager.requirements = androidx.media3.exoplayer.scheduler.Requirements(0) // Robolectric has no active network
        dl.manager.resumeDownloads()
        try {
            waitFor(60_000) { dl.refresh(); dl.entries.value.count { it.completed } >= 6 }
        } catch (e: IllegalStateException) {
            throw AssertionError("downloads: " + dl.entries.value.joinToString { "${it.id} state=${it.state} ${it.percent}% fail=${it.failure}" } + " notMet=${dl.manager.notMetRequirements} init=${dl.manager.isInitialized} paused=${dl.manager.downloadsPaused}")
        }
        settle(1500)
        assertEquals(6, dl.entries.value.count { it.completed })
        assertTrue(dl.entries.value.first { it.completed && it.meta.kind == "movie" }.bytes >= 3_000_000)
        // Security: credentials must never be persisted in the download database.
        dl.manager.downloadIndex.getDownloads().use { cur ->
            while (cur.moveToNext()) {
                val r = cur.download.request
                assertTrue("password leaked in ${r.uri}", !r.uri.toString().contains("secret") && !r.uri.toString().contains("/mk/"))
                assertTrue("password leaked in metadata", !String(r.data).contains("secret"))
            }
        }
        // ...but playback of a download still resolves to the real, authenticated URL.
        assertTrue(Playback.downloadItems(dl.entries.value).first().url.contains("/mk/secret/"))
        shot("07-series-detail-offline")

        Espresso.pressBack()
        settle(800)
        click("Downloads")
        settle(2500)
        shot("08-downloads")

        click("Overcast")
        settle(2500)
        shot("09-downloaded-episodes")
        Espresso.pressBack()
        settle(800)

        click("Live TV")
        waitFor { true }
        settle(6000)
        shot("10-live-tv")

        compose.onAllNodesWithText("Favorites").onLast().performClick() // the tab (drawn last), not the Live TV chip
        settle(4000)
        shot("15-favorites")

        // Playlists moved out of the tab bar: header playlist button -> Manage playlists…
        click("Home Server")
        settle(600)
        click("Manage playlists…")
        settle(1500)
        shot("11-playlists")

        clickDesc("Add playlist")
        settle(1200)
        shot("12-add-playlist")
        scenario.close()
    }

    /** Foldable half-open on a table (horizontal hinge): video above the fold, controls below. */
    @Test @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun tabletopPlayer() {
        kotlinx.coroutines.runBlocking { graph.catalog.load("home") }
        val pl = graph.store.playlist("home")!!
        val vods = graph.catalog.current!!.vod
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, PlayQueue(listOf(Playback.movieItem(pl, vods[0]), Playback.movieItem(pl, vods[2])), 0)))
        settle(1200)
        scenario.onActivity { a ->
            val h = a.window.decorView.height
            a.tabletopHinge.value = (h / 2 - 8) to (h / 2 + 8)
            a.error.value = null; a.buffering.value = false
            a.duration.longValue = 7_440_000; a.position.longValue = 4_360_000
            // Foldables are never locked to landscape.
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, a.requestedOrientation)
        }
        settle(500)
        prefix = "fold-"
        shot("14-tabletop-player")
        scenario.close()
    }

    @Test @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun player() {
        graph.store.update { it }
        waitFor { true }
        kotlinx.coroutines.runBlocking { graph.catalog.load("home") }
        val pl = graph.store.playlist("home")!!
        val vod = graph.catalog.current!!.vod.first()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // Saved position from a previous session must be restored exactly.
        graph.store.saveProgress("mk", com.mk.iptv.data.Progress("vod:${vod.id}", 4_360_000, 7_440_000, System.currentTimeMillis(), vod.name, kind = "movie", itemId = vod.id.toString(), playlistId = "home"))
        val intent = PlayerActivity.intent(ctx, PlayQueue(listOf(Playback.movieItem(pl, vod), Playback.movieItem(pl, graph.catalog.current!!.vod[2])), 0))
        val scenario = ActivityScenario.launch<PlayerActivity>(intent)
        scenario.onActivity { a -> assertEquals(4_360_000L, a.exo.currentPosition) }
        settle(1200)
        scenario.onActivity { a -> a.error.value = null; a.buffering.value = false; a.duration.longValue = 7_440_000; a.position.longValue = 4_360_000 }
        settle(600)
        shot("13-player")
        scenario.close()
    }
}
