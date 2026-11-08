package com.mk.iptv.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import com.mk.iptv.data.Creds
import com.mk.iptv.data.XtreamApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.mk.iptv.data.DownloadMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

data class DownloadEntry(
    val id: String,
    val meta: DownloadMeta,
    val state: Int,
    val percent: Float,
    val bytes: Long,
    val failure: Int,
) {
    val completed get() = state == Download.STATE_COMPLETED
    val active get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val paused get() = state == Download.STATE_STOPPED
    val failed get() = state == Download.STATE_FAILED
}

/** One card on the Downloads page: a movie, or a series with all of its downloaded episodes. */
data class DownloadGroup(
    val key: String,
    val isSeries: Boolean,
    val title: String,
    val image: String?,
    val entries: List<DownloadEntry>,
) {
    val bytes get() = entries.sumOf { it.bytes }
    val completedCount get() = entries.count { it.completed }
    val activeCount get() = entries.count { it.active }
    val progress get() = if (entries.isEmpty()) 0f else entries.map { if (it.completed) 100f else it.percent.coerceAtLeast(0f) }.average().toFloat() / 100f
}

/**
 * Wraps Media3's DownloadManager. Files are stored in the app's private storage and can be
 * played fully offline. Each request carries [DownloadMeta] so the Downloads page can show
 * titles, thumbnails and group episodes by series.
 */
class DownloadsRepository(
    private val context: Context,
    http: OkHttpClient,
    /** Looks up a playlist's current credentials; used to resolve [internalUrl]s at request time. */
    private val credsFor: (playlistId: String) -> Creds?,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dbProvider = StandaloneDatabaseProvider(context)
    val downloadDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
    val cache = SimpleCache(downloadDir, NoOpCacheEvictor(), dbProvider)
    private val network: DataSource.Factory = OkHttpDataSource.Factory(http).setUserAgent("MK IPTV/1.0 (Android)")

    /**
     * Network source that turns credential-free "mkiptv://" URLs into real Xtream URLs only at request
     * time, so usernames/passwords are never written to the download database.
     */
    val upstream: DataSource.Factory = ResolvingDataSource.Factory(network) { spec ->
        if (spec.uri.scheme == SCHEME) spec.withUri(android.net.Uri.parse(resolve(spec.uri.toString()))) else spec
    }

    /** Converts an internal download URL to the real stream URL (real URLs pass through unchanged). */
    fun resolve(url: String): String {
        if (!url.startsWith("$SCHEME://")) return url
        val rest = url.removePrefix("$SCHEME://")
        val pid = rest.substringBefore('/')
        val kind = rest.substringAfter('/').substringBefore('/')
        val file = rest.substringAfterLast('/')
        val c = credsFor(pid) ?: throw java.io.IOException("Playlist for this download was removed")
        val id = file.substringBeforeLast('.')
        val ext = file.substringAfterLast('.', "mp4")
        return if (kind == "series") XtreamApi.episodeUrl(c, id, ext) else XtreamApi.movieUrl(c, id.toIntOrNull() ?: 0, ext)
    }

    val manager = DownloadManager(context, dbProvider, cache, upstream, Executors.newFixedThreadPool(3)).apply {
        maxParallelDownloads = 2
    }

    /** Read-only cache source used to play downloaded files offline (falls back to network for partial files). */
    val offlineFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
        })
        // Progress isn't pushed by DownloadManager, so poll while something is downloading.
        scope.launch {
            while (true) {
                if (_entries.value.any { it.active }) refresh()
                delay(1000)
            }
        }
        refresh()
    }

    fun refresh() {
        val out = mutableListOf<DownloadEntry>()
        runCatching {
            manager.downloadIndex.getDownloads().use { c ->
                while (c.moveToNext()) {
                    val d = c.download
                    val meta = runCatching { json.decodeFromString(DownloadMeta.serializer(), String(d.request.data)) }.getOrNull() ?: continue
                    out += DownloadEntry(d.request.id, meta, d.state, d.percentDownloaded, d.bytesDownloaded, d.failureReason)
                }
            }
        }
        // also include in-memory progress for currently running downloads
        val live = manager.currentDownloads.associateBy { it.request.id }
        _entries.value = out.map { e -> live[e.id]?.let { d -> e.copy(state = d.state, percent = d.percentDownloaded, bytes = d.bytesDownloaded) } ?: e }
            .sortedByDescending { it.meta.addedAt }
    }

    fun groups(list: List<DownloadEntry>): List<DownloadGroup> {
        val groups = linkedMapOf<String, MutableList<DownloadEntry>>()
        list.forEach { e ->
            val k = if (e.meta.kind == "episode") "series:${e.meta.playlistId}:${e.meta.seriesId}" else e.id
            groups.getOrPut(k) { mutableListOf() } += e
        }
        return groups.map { (k, es) ->
            val first = es.first()
            if (first.meta.kind == "episode") DownloadGroup(
                key = k, isSeries = true,
                title = first.meta.seriesTitle ?: first.meta.title,
                image = first.meta.seriesImage ?: first.meta.image,
                entries = es.sortedWith(compareBy({ it.meta.season ?: 0 }, { it.meta.episode ?: 0 })),
            ) else DownloadGroup(k, false, first.meta.title, first.meta.image, es)
        }
    }

    fun entry(id: String) = _entries.value.firstOrNull { it.id == id }
    fun isCompleted(id: String?) = id != null && entry(id)?.completed == true

    fun requestFor(id: String, meta: DownloadMeta): DownloadRequest =
        DownloadRequest.Builder(id, android.net.Uri.parse(meta.url))
            .setCustomCacheKey(id)
            .setData(json.encodeToString(DownloadMeta.serializer(), meta).toByteArray())
            .build()

    fun start(id: String, meta: DownloadMeta) {
        val req = requestFor(id, meta)
        DownloadService.sendAddDownload(context, MkDownloadService::class.java, req, false)
        // reflect immediately in the UI
        _entries.value = listOf(DownloadEntry(id, meta, Download.STATE_QUEUED, 0f, 0, 0)) + _entries.value.filterNot { it.id == id }
    }

    fun remove(id: String) {
        DownloadService.sendRemoveDownload(context, MkDownloadService::class.java, id, false)
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    fun pause(id: String) = DownloadService.sendSetStopReason(context, MkDownloadService::class.java, id, 1, false)
    fun resume(id: String) = DownloadService.sendSetStopReason(context, MkDownloadService::class.java, id, Download.STOP_REASON_NONE, false)
    fun retry(id: String) { entry(id)?.let { e -> manager.removeDownload(id); start(id, e.meta) } }
    fun pauseAll() = DownloadService.sendPauseDownloads(context, MkDownloadService::class.java, false)
    fun resumeAll() = DownloadService.sendResumeDownloads(context, MkDownloadService::class.java, false)

    fun mediaItemFor(id: String, url: String): MediaItem = MediaItem.Builder().setUri(url).setCustomCacheKey(id).build()

    fun freeSpace(): Long = runCatching { downloadDir.usableSpace }.getOrDefault(0)

    companion object {
        const val SCHEME = "mkiptv"
        fun movieUrl(playlistId: String, vodId: Int, ext: String) = "$SCHEME://$playlistId/movie/$vodId.${ext.ifBlank { "mp4" }}"
        fun episodeUrl(playlistId: String, episodeId: String, ext: String) = "$SCHEME://$playlistId/series/$episodeId.${ext.ifBlank { "mp4" }}"
        fun movieId(playlistId: String, vodId: Int) = "movie:$playlistId:$vodId"
        fun episodeId(playlistId: String, episodeId: String) = "ep:$playlistId:$episodeId"
    }
}
