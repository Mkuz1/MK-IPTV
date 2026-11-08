package com.mk.iptv.player

import android.content.Context
import com.mk.iptv.MkApp
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.XtreamApi
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Hands the play queue to [PlayerActivity] without putting it in the Intent.
 *
 * A live category can hold thousands of channels; serialising them into Intent extras exceeds
 * Android's ~1 MB binder limit and crashes with TransactionTooLargeException. The queue is kept
 * in memory, plus a credential-free copy on disk (URLs are rebuilt from the saved playlist) so the
 * player can restore itself if Android recreates it after process death.
 */
object NowPlaying {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var memory: Pair<Long, PlayQueue>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "now_playing.json")

    fun put(ctx: Context, queue: PlayQueue): Long {
        val token = System.nanoTime()
        memory = token to queue
        runCatching {
            val stripped = queue.copy(items = queue.items.map { it.copy(url = "", altUrl = null) })
            file(ctx).writeText(json.encodeToString(PlayQueue.serializer(), stripped))
        }
        return token
    }

    fun take(ctx: Context, token: Long): PlayQueue? {
        memory?.let { (t, q) -> if (t == token) return q }
        val disk = runCatching { json.decodeFromString(PlayQueue.serializer(), file(ctx).readText()) }.getOrNull() ?: return null
        return rebuildUrls(disk)
    }

    /** Recreates stream URLs from the stored (encrypted) playlist credentials. */
    fun rebuildUrls(q: PlayQueue): PlayQueue {
        val g = MkApp.graph
        return q.copy(items = q.items.mapNotNull { it ->
            if (it.url.isNotEmpty()) return@mapNotNull it
            val c = g.catalog.creds(it.playlistId) ?: return@mapNotNull null
            when (it.kind) {
                "live" -> it.copy(url = XtreamApi.liveUrl(c, it.itemId.toIntOrNull() ?: return@mapNotNull null, "m3u8"), altUrl = XtreamApi.liveUrl(c, it.itemId.toInt(), "ts"))
                "movie" -> it.copy(url = XtreamApi.movieUrl(c, it.itemId.toIntOrNull() ?: return@mapNotNull null, it.ext))
                "episode" -> it.copy(url = XtreamApi.episodeUrl(c, it.itemId, it.ext))
                else -> null
            }
        })
    }
}
