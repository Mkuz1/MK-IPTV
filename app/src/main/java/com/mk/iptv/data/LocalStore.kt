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

    init {
        scope.launch {
            for (d in saves) {
                runCatching {
                    val tmp = File(file.parentFile, "state.json.tmp")
                    tmp.writeText(json.encodeToString(AppData.serializer(), d))
                    tmp.renameTo(file)
                }
            }
        }
    }

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

    companion object {
        fun newId() = UUID.randomUUID().toString().substring(0, 8)
    }
}
