package com.mk.iptv.sync

import com.mk.iptv.data.LocalStore
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.Progress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000_000L

    private inner class Device(name: String) {
        val dir = tmp.newFolder(name)
        val store = LocalStore(dir)
        // "enc:" prefix stands in for the Android Keystore encryption
        val engine = SyncEngine(store, dir.resolve("meta.json"), name, now = { clock++ }, encrypt = { "enc:$it" }, decrypt = { it.removePrefix("enc:") })
        fun push(b: MemoryBackend) = runBlocking { engine.push(b) }
        fun pull(b: MemoryBackend) = engine.applyRemote(b.all)
        val d get() = store.data.value
    }

    private fun progress(key: String, pos: Long, at: Long) = Progress(key, pos, 100_000, at, "Title $key", kind = "movie", itemId = key, playlistId = "pl1")

    @Test fun everythingReachesTheSecondDevice() {
        val b = MemoryBackend()
        val a = Device("a"); val c = Device("c")
        a.store.upsertPlaylist(Playlist("pl1", "Home", "http://srv", "user", "enc:secret"))
        a.store.upsertProfile(Profile("p1", "Mo", playlistIds = listOf("pl1")))
        a.store.toggleFavorite("p1", "vod:1")
        a.store.saveProgress("p1", progress("vod:1", 50_000, 5))
        a.store.dismissContinue("p1", "vod:9")
        a.store.updateSettings { it.copy(liveFormat = "hls") }
        a.push(b)

        c.pull(b)
        assertEquals("secret", c.d.playlists.single().passwordEnc.removePrefix("enc:"))
        assertEquals(listOf("pl1"), c.d.profiles.single().playlistIds)
        assertEquals("pl1", c.d.profiles.single().activePlaylistId)
        assertEquals(listOf("vod:1"), c.d.favorites["p1"])
        assertEquals(50_000, c.d.progress["p1"]!!["vod:1"]!!.position)
        assertTrue("vod:9" in c.d.dismissed["p1"]!!)
        assertEquals("hls", c.d.settings.liveFormat)
        // applying remote data does not bounce back
        assertEquals(0, c.push(b))
        assertEquals(0, a.push(b))
    }

    @Test fun removalsAndNewestProgressWin() {
        val b = MemoryBackend()
        val a = Device("a"); val c = Device("c")
        a.store.upsertProfile(Profile("p1", "Mo"))
        a.store.toggleFavorite("p1", "vod:1"); a.store.toggleFavorite("p1", "vod:2")
        a.store.saveProgress("p1", progress("vod:1", 10_000, 100))
        a.push(b); c.pull(b)

        c.store.toggleFavorite("p1", "vod:1")                       // removed on c
        c.store.saveProgress("p1", progress("vod:1", 70_000, 300))  // watched further on c
        c.push(b)
        a.store.saveProgress("p1", progress("vod:1", 20_000, 200))  // older position saved on a meanwhile
        a.pull(b)
        assertEquals(listOf("vod:2"), a.d.favorites["p1"])
        assertEquals(70_000, a.d.progress["p1"]!!["vod:1"]!!.position)
        a.push(b); c.pull(b)
        assertEquals(70_000, c.d.progress["p1"]!!["vod:1"]!!.position)

        a.store.removeProgress("p1", listOf("vod:1"))
        a.push(b); c.pull(b)
        assertNull(c.d.progress["p1"]!!["vod:1"])
    }

    @Test fun joiningDeviceMergesMatchingPlaylistsAndProfiles() {
        val b = MemoryBackend()
        val a = Device("a"); val c = Device("c")
        a.store.upsertPlaylist(Playlist("plA", "Home", "http://srv/", "user", "enc:x"))
        a.store.upsertProfile(Profile("pA", "Mo", playlistIds = listOf("plA")))
        a.push(b)
        c.store.upsertPlaylist(Playlist("plC", "My server", "http://SRV", "user", "enc:x"))
        c.store.upsertProfile(Profile("pC", "mo", playlistIds = listOf("plC")))
        c.store.toggleFavorite("pC", "series:7")
        c.store.selectProfile("pC")

        c.pull(b); c.push(b); a.pull(b)
        for (d in listOf(a.d, c.d)) {
            assertEquals(listOf("plA"), d.playlists.map { it.id })
            assertEquals(listOf("pA"), d.profiles.map { it.id })
            assertEquals(listOf("series:7"), d.favorites["pA"])
        }
        assertEquals("pA", c.d.lastProfileId)
        assertEquals("plA", c.store.playlist("plC")?.id)   // old id still resolves (downloads)
    }
}
