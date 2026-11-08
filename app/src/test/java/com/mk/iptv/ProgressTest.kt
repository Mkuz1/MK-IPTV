package com.mk.iptv

import com.mk.iptv.data.LocalStore
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.Progress
import com.mk.iptv.ui.continueWatching
import com.mk.iptv.ui.sessionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProgressTest {
    private fun p(key: String, pos: Long, dur: Long, t: Long, kind: String = "episode", sid: Int? = 7) =
        Progress(key, pos, dur, t, "Show", kind = kind, seriesId = sid, itemId = key.substringAfter(':'), playlistId = "pl", subtitle = "S1:E1")

    private fun newStore(dir: java.io.File) = LocalStore(dir).apply {
        update { it.copy(playlists = listOf(Playlist("pl", "Home", "http://x", "u", "p")), profiles = listOf(Profile("me", "Me", playlistIds = listOf("pl"), activePlaylistId = "pl")), lastProfileId = "me") }
    }

    @Test fun progressSurvivesAppRestart() {
        val dir = Files.createTempDirectory("mk").toFile()
        val a = newStore(dir)
        a.saveProgress("me", p("vod:5", 1_234_567, 7_000_000, 1, "movie", null))
        a.flushBlocking()
        // "Process killed": a brand-new store reading the same directory.
        val b = LocalStore(dir)
        assertEquals(1_234_567L, b.progress("me", "vod:5")!!.position)
        assertEquals("pl", b.data.value.playlists.single().id)
    }

    @Test fun watchedThreshold() {
        assertFalse(p("e", 2_500_000, 3_000_000, 0).finished)  // 8 min left of 50
        assertTrue(p("e", 2_890_000, 3_000_000, 0).finished)   // in the credits
        assertTrue(p("e", 280_000, 300_000, 0).finished)       // 93% of a short clip
        assertFalse(p("e", 200_000, 300_000, 0).finished)
    }

    @Test fun continueWatchingRules() {
        val store = newStore(Files.createTempDirectory("mk").toFile())
        store.saveProgress("me", p("ep:1", 3_000_000, 3_000_000, 10))          // series 7 E1 finished
        store.saveProgress("me", p("ep:2", 600_000, 3_000_000, 20))            // series 7 E2 in progress (latest)
        store.saveProgress("me", p("ep:9", 3_000_000, 3_000_000, 30, sid = 8)) // series 8: last watched is finished -> up next
        store.saveProgress("me", p("vod:1", 900_000, 6_000_000, 40, "movie", null))
        store.saveProgress("me", p("vod:2", 5_900_000, 6_000_000, 50, "movie", null)) // finished movie -> not shown
        val s = sessionOf(store.data.value)
        val series = continueWatching(s, movies = false)
        assertEquals(listOf("ep:9", "ep:2"), series.map { it.key })        // one card per series, newest first
        assertTrue(series[0].finished)                                    // shown as "Up next"
        assertEquals(listOf("vod:1"), continueWatching(s, movies = true).map { it.key })

        store.dismissContinue("me", "series:8")
        assertEquals(listOf("ep:2"), continueWatching(sessionOf(store.data.value), false).map { it.key })
        // Watching again brings it back
        Thread.sleep(5)
        store.saveProgress("me", p("ep:10", 100_000, 3_000_000, System.currentTimeMillis() + 1, sid = 8))
        assertEquals(listOf("ep:10", "ep:2"), continueWatching(sessionOf(store.data.value), false).map { it.key })
    }

    @Test fun markWatchedAndUnwatched() {
        val store = newStore(Files.createTempDirectory("mk").toFile())
        store.setWatched("me", listOf(p("ep:1", 0, 0, 0), p("ep:2", 0, 0, 0)), true)
        assertTrue(store.progress("me", "ep:1")!!.finished && store.progress("me", "ep:2")!!.finished)
        store.setWatched("me", listOf(p("ep:1", 0, 0, 0)), false)
        assertEquals(null, store.progress("me", "ep:1"))
    }
}
