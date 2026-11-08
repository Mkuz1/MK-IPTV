package com.mk.iptv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Loads and caches the Xtream catalog of the active playlist. */
class CatalogRepository(private val api: XtreamApi, private val store: LocalStore) {

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    @Volatile var playlistId: String? = null
        private set
    private val mutex = Mutex()
    private val catalogs = ConcurrentHashMap<String, Catalog>()
    private val vodInfoCache = ConcurrentHashMap<String, VodInfo>()
    private val seriesInfoCache = ConcurrentHashMap<String, SeriesInfo>()
    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<EpgEntry>>>()

    fun creds(p: Playlist) = Creds(p.server, p.username, Crypto.decrypt(p.passwordEnc))
    fun creds(playlistId: String?) = store.playlist(playlistId)?.let { creds(it) }

    val current: Catalog? get() = (state.value as? CatalogState.Ready)?.catalog

    /** Switch to [playlistId] and load its catalog (from memory unless [force]). */
    suspend fun load(playlistId: String, force: Boolean = false) = mutex.withLock {
        val p = store.playlist(playlistId) ?: return@withLock
        this.playlistId = playlistId
        val cached = catalogs[playlistId]
        val maxAge = if (p.autoRefresh) 24 * 3600_000L else Long.MAX_VALUE
        if (!force && cached != null && System.currentTimeMillis() - cached.loadedAt < maxAge) {
            _state.value = CatalogState.Ready(cached); return@withLock
        }
        _state.value = CatalogState.Loading("Connecting to ${p.name}…")
        try {
            val c = creds(p)
            val auth = runCatching { api.authenticate(c) }.getOrNull()
            if (auth != null && !auth.ok) throw IllegalStateException(auth.message ?: "Login failed")
            _state.value = CatalogState.Loading("Loading channels, movies & series…")
            val cat = coroutineScope {
                val lc = async { runCatching { api.liveCategories(c) }.getOrDefault(emptyList()) }
                val l = async { runCatching { api.liveStreams(c) }.getOrDefault(emptyList()) }
                val vc = async { runCatching { api.vodCategories(c) }.getOrDefault(emptyList()) }
                val v = async { runCatching { api.vodStreams(c) }.getOrDefault(emptyList()) }
                val sc = async { runCatching { api.seriesCategories(c) }.getOrDefault(emptyList()) }
                val s = async { runCatching { api.series(c) }.getOrDefault(emptyList()) }
                Catalog(lc.await(), l.await(), vc.await(), v.await(), sc.await(), s.await(), System.currentTimeMillis())
            }
            catalogs[playlistId] = cat
            store.upsertPlaylist(
                (store.playlist(playlistId) ?: p).copy(
                    lastSync = System.currentTimeMillis(),
                    status = auth?.status ?: p.status,
                    expiry = auth?.expiry ?: p.expiry,
                    activeCons = auth?.activeCons ?: p.activeCons,
                    maxCons = auth?.maxCons ?: p.maxCons,
                    liveCount = cat.live.size, movieCount = cat.vod.size, seriesCount = cat.series.size,
                )
            )
            _state.value = CatalogState.Ready(cat)
        } catch (e: Exception) {
            _state.value = CatalogState.Error(e.message ?: "Could not load playlist", cached)
        }
    }

    fun clear(playlistId: String) {
        catalogs.remove(playlistId)
        if (this.playlistId == playlistId) { this.playlistId = null; _state.value = CatalogState.Idle }
    }

    suspend fun vodInfo(id: Int): VodInfo? {
        val pid = playlistId ?: return null
        vodInfoCache["$pid:$id"]?.let { return it }
        val c = creds(pid) ?: return null
        return runCatching { api.vodInfo(c, id) }.getOrNull()?.also { vodInfoCache["$pid:$id"] = it }
    }

    suspend fun seriesInfo(id: Int, playlistId: String? = this.playlistId): SeriesInfo? {
        val pid = playlistId ?: return null
        seriesInfoCache["$pid:$id"]?.let { return it }
        val c = creds(pid) ?: return null
        return runCatching { api.seriesInfo(c, id) }.getOrNull()?.also { seriesInfoCache["$pid:$id"] = it }
    }

    fun cachedEpg(streamId: Int): List<EpgEntry>? {
        val pid = playlistId ?: return null
        val e = epgCache["$pid:$streamId"] ?: return null
        val now = System.currentTimeMillis()
        // invalidate when the current programme has ended
        if (now - e.first > 30 * 60_000 || (e.second.firstOrNull()?.end ?: Long.MAX_VALUE) < now) return null
        return e.second
    }

    suspend fun epg(streamId: Int): List<EpgEntry> {
        cachedEpg(streamId)?.let { return it }
        val pid = playlistId ?: return emptyList()
        val p = store.playlist(pid) ?: return emptyList()
        if (!p.loadEpg) return emptyList()
        val now = System.currentTimeMillis()
        val list = runCatching { api.shortEpg(creds(p), streamId, 4) }.getOrDefault(emptyList())
            .filter { it.end > now }.take(2)
        epgCache["$pid:$streamId"] = now to list
        return list
    }

    companion object {
        private val adultWords = listOf("adult", "xxx", "18+", "+18", "porn", "for adults", "erotic")
        fun isAdult(name: String) = adultWords.any { name.contains(it, ignoreCase = true) }
    }
}
