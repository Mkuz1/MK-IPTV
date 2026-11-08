package com.mk.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Tiny JSON-file backed store for playlists, profiles, favorites and watch progress. */
class LocalStore(dir: File) {
    private val file = File(dir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = Channel<AppData>(Channel.CONFLATED)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val writeLock = Any()
    @Volatile private var lastWritten: AppData? = null

    init {
        scope.launch {
            for (d in saves) write(d)
        }
    }

    /** Atomic, durable write: temp file + fsync + rename, so a crash or kill never corrupts or loses state. */
    private fun write(d: AppData) = synchronized(writeLock) {
        if (d === lastWritten) return@synchronized
        runCatching {
            val tmp = File(file.parentFile, "state.json.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(AppData.serializer(), d).toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            lastWritten = d
        }
    }

    /** Writes the current state to disk right now (used by the player on pause/stop/exit). */
    fun flushBlocking() = write(_data.value)

    private fun load(): AppData = runCatching {
        if (file.exists()) json.decodeFromString(AppData.serializer(), file.readText()) else AppData()
    }.getOrDefault(AppData())

    fun update(block: (AppData) -> AppData) {
        _data.update(block)
        saves.trySend(_data.value)
    }

    /* ---- playlists ---- */
    fun playlist(id: String?) = data.value.playlists.firstOrNull { it.id == id }

    fun upsertPlaylist(p: Playlist) = update { d ->
        val list = d.playlists.filterNot { it.id == p.id } + p
        d.copy(playlists = list.sortedBy { it.createdAt })
    }

    fun deletePlaylist(id: String) = update { d ->
        d.copy(
            playlists = d.playlists.filterNot { it.id == id },
            profiles = d.profiles.map { pr ->
                val ids = pr.playlistIds - id
                pr.copy(playlistIds = ids, activePlaylistId = if (pr.activePlaylistId == id) ids.firstOrNull() else pr.activePlaylistId)
            },
        )
    }

    /* ---- profiles ---- */
    fun profile(id: String?) = data.value.profiles.firstOrNull { it.id == id }

    fun upsertProfile(p: Profile) = update { d ->
        val idx = d.profiles.indexOfFirst { it.id == p.id }
        val fixed = p.copy(activePlaylistId = p.activePlaylistId?.takeIf { it in p.playlistIds } ?: p.playlistIds.firstOrNull())
        d.copy(profiles = if (idx >= 0) d.profiles.toMutableList().also { it[idx] = fixed } else d.profiles + fixed)
    }

    fun deleteProfile(id: String) = update { d ->
        d.copy(
            profiles = d.profiles.filterNot { it.id == id },
            favorites = d.favorites - id,
            progress = d.progress - id,
            lastProfileId = if (d.lastProfileId == id) null else d.lastProfileId,
        )
    }

    fun selectProfile(id: String) = update { it.copy(lastProfileId = id) }

    fun setActivePlaylist(profileId: String, playlistId: String) = update { d ->
        d.copy(profiles = d.profiles.map { if (it.id == profileId) it.copy(activePlaylistId = playlistId) else it })
    }

    /* ---- favorites ---- */
    fun isFavorite(profileId: String, key: String) = data.value.favorites[profileId]?.contains(key) == true

    fun toggleFavorite(profileId: String, key: String) = update { d ->
        val cur = d.favorites[profileId].orEmpty()
        val next = if (key in cur) cur - key else listOf(key) + cur
        d.copy(favorites = d.favorites + (profileId to next))
    }

    /* ---- progress ---- */
    fun saveProgress(profileId: String, p: Progress) = update { d ->
        val cur = d.progress[profileId].orEmpty()
        // keep the 300 most recent entries
        val next = (cur + (p.key to p)).entries.sortedByDescending { it.value.updatedAt }.take(300).associate { it.key to it.value }
        d.copy(progress = d.progress + (profileId to next))
    }

    fun progress(profileId: String, key: String) = data.value.progress[profileId]?.get(key)

    fun updateSettings(block: (AppSettings) -> AppSettings) = update { it.copy(settings = block(it.settings)) }

    fun dismissContinue(profileId: String, key: String) = update { d ->
        d.copy(dismissed = d.dismissed + (profileId to (d.dismissed[profileId].orEmpty() + (key to System.currentTimeMillis()))))
    }

    fun removeProgress(profileId: String, keys: Collection<String>) = update { d ->
        d.copy(progress = d.progress + (profileId to d.progress[profileId].orEmpty().filterKeys { it !in keys }))
    }

    /** Marks items watched (position = duration) or unwatched (progress removed). */
    fun setWatched(profileId: String, entries: List<Progress>, watched: Boolean) {
        if (!watched) return removeProgress(profileId, entries.map { it.key })
        update { d ->
            val cur = d.progress[profileId].orEmpty().toMutableMap()
            val now = System.currentTimeMillis()
            entries.forEachIndexed { i, p ->
                val dur = p.duration.takeIf { it > 0 } ?: cur[p.key]?.duration?.takeIf { it > 0 } ?: 1L
                // older timestamps for earlier items so "last watched" points to the latest one
                cur[p.key] = p.copy(position = dur, duration = dur, updatedAt = now - (entries.size - i))
            }
            d.copy(progress = d.progress + (profileId to cur))
        }
    }

    companion object {
        fun newId() = UUID.randomUUID().toString().substring(0, 8)
    }
}
