package com.mk.iptv

import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.mk.iptv.data.LiveChannel
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Playlist
import com.mk.iptv.player.PlayerActivity
import com.mk.iptv.ui.Playback
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opening a channel from a big playlist must not exceed Android's ~1 MB binder limit (TransactionTooLargeException). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IntentSizeTest {
    @Test fun playerIntentStaysSmallForHugeChannelLists() {
        val pl = Playlist("p", "Big", "http://line.provider-example.com:8080", "username123", "p:" + android.util.Base64.encodeToString("password123".toByteArray(), 2))
        val channels = (1..8000).map { LiveChannel(it, it, "Channel number $it HD", "http://logos.provider-example.com/img/$it.png", "12", "ch$it.example") }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = PlayerActivity.intent(ctx, PlayQueue(Playback.liveItems(pl, channels), 4000))
        val p = Parcel.obtain()
        intent.writeToParcel(p, 0)
        val bytes = p.dataSize()
        p.recycle()
        println("player intent size: $bytes bytes")
        assertTrue("intent is $bytes bytes", bytes < 50_000)
    }

    /** If Android restarts the player after process death, the queue comes back from disk with fresh URLs. */
    @Test fun queueRestoresFromDiskWithoutStoringPasswords() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pl = Playlist("p", "Big", "http://line.provider-example.com:8080", "username123", com.mk.iptv.data.Crypto.encrypt("password123"))
        MkApp.graph.store.upsertPlaylist(pl)
        val channels = (1..50).map { LiveChannel(it, it, "Ch $it", null, "1", null) }
        com.mk.iptv.player.NowPlaying.put(ctx, PlayQueue(Playback.liveItems(pl, channels), 7))
        val onDisk = java.io.File(ctx.filesDir, "now_playing.json").readText()
        assertTrue("password stored on disk", !onDisk.contains("password123"))
        val restored = com.mk.iptv.player.NowPlaying.take(ctx, token = -42)!!   // memory lost -> disk
        org.junit.Assert.assertEquals(7, restored.index)
        org.junit.Assert.assertEquals("http://line.provider-example.com:8080/live/username123/password123/8.m3u8", restored.items[7].url)
        org.junit.Assert.assertEquals("http://line.provider-example.com:8080/live/username123/password123/8.ts", restored.items[7].altUrl)
    }

    @Test fun crashReportsAreRedacted() {
        val r = CrashReporter.redact("java.io.IOException: http://h:8080/live/bob/s3cret/12.ts failed; url=http://h/player_api.php?username=bob&password=s3cret")
        assertTrue(r, !r.contains("s3cret") && !r.contains("bob"))
    }
}
