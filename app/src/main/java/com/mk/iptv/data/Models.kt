package com.mk.iptv.data

import kotlinx.serialization.Serializable

/* ---------------- Persisted app state ---------------- */

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    /** Password encrypted with the Android Keystore (see [Crypto]). */
    val passwordEnc: String,
    val loadEpg: Boolean = true,
    val autoRefresh: Boolean = true,
    val hideAdult: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSync: Long = 0,
    val status: String? = null,
    val expiry: Long? = null,
    val activeCons: Int? = null,
    val maxCons: Int? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val kids: Boolean = false,
    val playlistIds: List<String> = emptyList(),
    val activePlaylistId: String? = null,
)

@Serializable
data class Progress(
    val key: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val title: String,
    val image: String? = null,
    val kind: String, // movie | episode
    val seriesId: Int? = null,
    val subtitle: String? = null,
    val itemId: String = "",
    val ext: String = "mp4",
    val playlistId: String = "",
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = duration > 0 && position > duration * 0.95
}

@Serializable
data class AppData(
    val playlists: List<Playlist> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val lastProfileId: String? = null,
    /** profileId -> set of keys like "vod:12", "series:4", "live:99" */
    val favorites: Map<String, List<String>> = emptyMap(),
    /** profileId -> (key -> progress) */
    val progress: Map<String, Map<String, Progress>> = emptyMap(),
)

/* ---------------- Catalog (from Xtream) ---------------- */

data class Category(val id: String, val name: String)

data class VodItem(
    val id: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: Double?,
    val added: Long,
    val ext: String,
)

data class SeriesItem(
    val id: Int,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val rating: Double?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val lastModified: Long,
    val backdrop: String?,
    val cast: String?,
)

data class LiveChannel(
    val id: Int,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgId: String?,
)

data class VodInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val duration: String?,
    val releaseDate: String?,
    val rating: Double?,
    val backdrop: String?,
    val trailer: String?,
    val ageRating: String?,
    val ext: String?,
    val cover: String?,
)

data class Episode(
    val id: String,
    val num: Int,
    val season: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
    val ext: String,
)

data class SeriesInfo(
    val name: String,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val cover: String?,
    val backdrop: String?,
    val trailer: String?,
    val seasons: List<Int>,
    val episodes: Map<Int, List<Episode>>,
)

data class EpgEntry(val title: String, val description: String?, val start: Long, val end: Long)

data class Catalog(
    val liveCategories: List<Category> = emptyList(),
    val live: List<LiveChannel> = emptyList(),
    val vodCategories: List<Category> = emptyList(),
    val vod: List<VodItem> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val loadedAt: Long = 0,
) {
    val vodById: Map<Int, VodItem> by lazy { vod.associateBy { it.id } }
    val seriesById: Map<Int, SeriesItem> by lazy { series.associateBy { it.id } }
    val liveById: Map<Int, LiveChannel> by lazy { live.associateBy { it.id } }
    fun vodCategoryName(id: String?) = vodCategories.firstOrNull { it.id == id }?.name
    fun seriesCategoryName(id: String?) = seriesCategories.firstOrNull { it.id == id }?.name
}

sealed interface CatalogState {
    data object Idle : CatalogState
    data class Loading(val message: String) : CatalogState
    data class Ready(val catalog: Catalog) : CatalogState
    data class Error(val message: String, val stale: Catalog? = null) : CatalogState
}

data class Creds(val server: String, val username: String, val password: String)

data class AuthResult(
    val ok: Boolean,
    val message: String?,
    val status: String?,
    val expiry: Long?,
    val activeCons: Int?,
    val maxCons: Int?,
)

/* ---------------- Playback ---------------- */

@Serializable
data class PlayItem(
    val key: String,
    val kind: String, // live | movie | episode
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val altUrl: String? = null,
    val image: String? = null,
    val downloadId: String? = null,
    val seriesId: Int? = null,
    val itemId: String = "",
    val ext: String = "mp4",
    val playlistId: String = "",
    val mime: String? = null,
)

@Serializable
data class PlayQueue(val items: List<PlayItem>, val index: Int)

/* ---------------- Downloads ---------------- */

@Serializable
data class DownloadMeta(
    val kind: String, // movie | episode
    val playlistId: String,
    val title: String,
    val image: String? = null,
    val plot: String? = null,
    val seriesId: Int? = null,
    val seriesTitle: String? = null,
    val seriesImage: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val url: String,
)
