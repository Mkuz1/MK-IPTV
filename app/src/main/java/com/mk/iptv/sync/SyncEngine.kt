package com.mk.iptv.sync

import com.mk.iptv.data.AppData
import com.mk.iptv.data.Crypto
import com.mk.iptv.data.LocalStore
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.Progress
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * One synced record. The same format is written by the Android, Google TV and iPhone apps.
 * id = "<type>~<profileId>~<key>"; json = the record's fields; newest [updatedAt] wins.
 */
@Serializable
data class SyncItem(
    val id: String,
    val type: String,          // playlist | profile | favorite | progress | dismissed | setting
    val profileId: String = "",
    val key: String,
    val json: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val device: String = "",
)

/** Where items are stored remotely (Firestore in the app, an in-memory fake in tests). */
interface SyncBackend {
    suspend fun upload(items: List<SyncItem>)
    /** Delivers added/changed items (the first call contains everything). Returns an unsubscribe function. */
    fun listen(onChange: (List<SyncItem>) -> Unit): () -> Unit
    /** Everything currently in the account (used by "Sync now"). */
    suspend fun fetchAll(): List<SyncItem>
}

/**
 * Pure merge logic between the [LocalStore] and a [SyncBackend].
 * - Local -> remote: compares the current data with what was last synced and uploads only differences
 *   (removals are uploaded as tombstones).
 * - Remote -> local: applies items that are newer than what this device knows.
 * Applying remote data never echoes back, because the rebuilt local item equals the known one.
 */
class SyncEngine(
    private val store: LocalStore,
    private val metaFile: File,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val encrypt: (String) -> String = Crypto::encrypt,
    private val decrypt: (String) -> String = Crypto::decrypt,
) {
    @Serializable
    private data class Known(val json: String, val updatedAt: Long, val deleted: Boolean)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val known: MutableMap<String, Known> = load()
    private val lock = Any()

    private fun load(): MutableMap<String, Known> = runCatching {
        json.decodeFromString<Map<String, Known>>(metaFile.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun persist() = runCatching {
        val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
        tmp.writeText(json.encodeToString(MapSerializer(String.serializer(), Known.serializer()), known))
        tmp.renameTo(metaFile)
    }

    fun reset() = synchronized(lock) { known.clear(); metaFile.delete() }

    // ---------------------------------------------------------------- local -> items

    private data class Local(val type: String, val profileId: String, val key: String, val body: JsonObject, val ts: Long?)

    private fun id(type: String, profileId: String, key: String) = "$type~$profileId~$key"

    private fun localItems(d: AppData): Map<String, Local> {
        val out = LinkedHashMap<String, Local>()
        fun add(l: Local) { out[id(l.type, l.profileId, l.key)] = l }
        d.playlists.forEach { p ->
            add(Local("playlist", "", p.id, buildJsonObject {
                put("id", p.id); put("name", p.name); put("server", p.server); put("username", p.username)
                put("password", decrypt(p.passwordEnc)); put("loadEpg", p.loadEpg); put("hideAdult", p.hideAdult); put("autoRefresh", p.autoRefresh)
            }, null))
        }
        d.profiles.forEach { p ->
            add(Local("profile", "", p.id, buildJsonObject {
                put("id", p.id); put("name", p.name); put("colorIndex", p.colorIndex); put("kids", p.kids)
                put("playlistIds", buildJsonArray { p.playlistIds.forEach { add(JsonPrimitive(it)) } })
            }, null))
        }
        d.favorites.forEach { (pid, keys) -> keys.forEach { k -> add(Local("favorite", pid, k, buildJsonObject { put("key", k) }, null)) } }
        d.progress.forEach { (pid, map) ->
            map.values.forEach { p ->
                add(Local("progress", pid, p.key, buildJsonObject {
                    put("key", p.key); put("position", p.position); put("duration", p.duration); put("updatedAt", p.updatedAt)
                    put("title", p.title); p.image?.let { put("image", it) }; put("kind", p.kind); p.seriesId?.let { put("seriesId", it) }
                    p.subtitle?.let { put("subtitle", it) }; put("itemId", p.itemId); put("ext", p.ext); put("playlistId", p.playlistId)
                }, p.updatedAt))
            }
        }
        d.dismissed.forEach { (pid, map) -> map.forEach { (k, t) -> add(Local("dismissed", pid, k, buildJsonObject { put("at", t) }, t)) } }
        add(Local("setting", "", "liveFormat", buildJsonObject { put("value", d.settings.liveFormat) }, null))
        return out
    }

    private fun same(a: String, b: JsonObject) = runCatching { json.parseToJsonElement(a) == b }.getOrDefault(false)

    /** Items to upload so the remote side matches this device. Marks them as known. */
    fun pendingUploads(): List<SyncItem> = synchronized(lock) {
        val local = localItems(store.data.value)
        val out = mutableListOf<SyncItem>()
        val t = now()
        local.forEach { (id, l) ->
            val k = known[id]
            if (k == null || k.deleted || !same(k.json, l.body)) {
                // A newer remote version wins over a stale local value that has its own timestamp (progress).
                if (k != null && !k.deleted && l.ts != null && l.ts < k.updatedAt) return@forEach
                val ts = maxOf(l.ts ?: t, (k?.updatedAt ?: 0) + 1)
                val body = l.body.toString()
                out += SyncItem(id, l.type, l.profileId, l.key, body, ts, false, deviceId)
                known[id] = Known(body, ts, false)
            }
        }
        known.entries.filter { (id, k) -> !k.deleted && id !in local }.forEach { (id, k) ->
            val (type, pid, key) = id.split("~", limit = 3).let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            val ts = maxOf(t, k.updatedAt + 1)
            out += SyncItem(id, type, pid, key, "{}", ts, true, deviceId)
            known[id] = Known("{}", ts, true)
        }
        if (out.isNotEmpty()) persist()
        out
    }

    suspend fun push(backend: SyncBackend): Int {
        val items = pendingUploads()
        if (items.isNotEmpty()) backend.upload(items)
        return items.size
    }

    // ---------------------------------------------------------------- remote -> local

    /** Applies remote items newer than what this device knows. Returns how many were applied. */
    fun applyRemote(items: List<SyncItem>): Int = synchronized(lock) {
        val fresh = items.filter { r -> known[r.id].let { it == null || r.updatedAt > it.updatedAt } }
        if (fresh.isEmpty()) return 0
        val unsynced = { type: String, key: String -> id(type, "", key) !in known }
        store.update { d -> fresh.sortedBy { it.updatedAt }.fold(adopt(d, fresh, unsynced)) { acc, item -> applyOne(acc, item) } }
        fresh.forEach { known[it.id] = Known(it.json, it.updatedAt, it.deleted) }
        persist()
        fresh.size
    }

    /**
     * When a device joins an account, its own (never synced) playlists and profiles that match ones already in the
     * account are merged into them instead of being duplicated: same server + username, or same profile name.
     */
    private fun adopt(d0: AppData, items: List<SyncItem>, unsynced: (String, String) -> Boolean): AppData {
        var d = d0
        fun norm(s: String?) = s.orEmpty().trim().trimEnd('/').lowercase()
        items.filter { it.type == "playlist" && !it.deleted && d.playlists.none { p -> p.id == it.key } }.forEach { item ->
            val b = obj(item.json)
            val local = d.playlists.firstOrNull { p ->
                unsynced("playlist", p.id) && norm(p.server) == norm(b.str("server")) && norm(p.username) == norm(b.str("username"))
            } ?: return@forEach
            d = remapPlaylist(d, local.id, item.key)
        }
        items.filter { it.type == "profile" && !it.deleted && d.profiles.none { p -> p.id == it.key } }.forEach { item ->
            val name = norm(obj(item.json).str("name"))
            val local = d.profiles.firstOrNull { p -> unsynced("profile", p.id) && norm(p.name) == name } ?: return@forEach
            d = remapProfile(d, local.id, item.key)
        }
        return d
    }

    private fun remapPlaylist(d: AppData, from: String, to: String): AppData {
        fun r(id: String?) = if (id == from) to else id
        return d.copy(
            playlists = d.playlists.map { if (it.id == from) it.copy(id = to) else it },
            profiles = d.profiles.map { p -> p.copy(playlistIds = p.playlistIds.map { r(it)!! }.distinct(), activePlaylistId = r(p.activePlaylistId)) },
            progress = d.progress.mapValues { (_, m) -> m.mapValues { (_, p) -> if (p.playlistId == from) p.copy(playlistId = to) else p } },
            playlistAliases = d.playlistAliases.mapValues { (_, v) -> r(v)!! } + (from to to),
        )
    }

    private fun remapProfile(d: AppData, from: String, to: String): AppData {
        fun <V> Map<String, V>.re() = if (from in this) (this - from) + (to to this.getValue(from)) else this
        return d.copy(
            profiles = d.profiles.map { if (it.id == from) it.copy(id = to) else it },
            lastProfileId = if (d.lastProfileId == from) to else d.lastProfileId,
            favorites = d.favorites.re(), progress = d.progress.re(), dismissed = d.dismissed.re(),
        )
    }

    private fun obj(s: String): JsonObject = runCatching { json.parseToJsonElement(s).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: def

    private fun applyOne(d: AppData, item: SyncItem): AppData {
        val b = obj(item.json)
        return when (item.type) {
            "playlist" -> if (item.deleted) d.copy(
                playlists = d.playlists.filterNot { it.id == item.key },
                profiles = d.profiles.map { it.copy(playlistIds = it.playlistIds - item.key, activePlaylistId = it.activePlaylistId.takeIf { a -> a != item.key }) },
            ) else {
                val existing = d.playlists.firstOrNull { it.id == item.key }
                val pw = b.str("password").orEmpty()
                val p = (existing ?: Playlist(id = item.key, name = "", server = "", username = "", passwordEnc = "")).copy(
                    name = b.str("name") ?: existing?.name.orEmpty(), server = b.str("server") ?: existing?.server.orEmpty(),
                    username = b.str("username") ?: existing?.username.orEmpty(),
                    passwordEnc = if (existing != null && decrypt(existing.passwordEnc) == pw) existing.passwordEnc else encrypt(pw),
                    loadEpg = b.bool("loadEpg", true), hideAdult = b.bool("hideAdult", true), autoRefresh = b.bool("autoRefresh", true),
                )
                d.copy(playlists = (d.playlists.filterNot { it.id == p.id } + p).sortedBy { it.createdAt })
            }
            "profile" -> if (item.deleted) d.copy(profiles = d.profiles.filterNot { it.id == item.key }) else {
                val existing = d.profiles.firstOrNull { it.id == item.key }
                val ids = runCatching { b["playlistIds"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(existing?.playlistIds.orEmpty())
                val p = (existing ?: Profile(id = item.key, name = "")).copy(
                    name = b.str("name") ?: existing?.name.orEmpty(), colorIndex = runCatching { b["colorIndex"]!!.jsonPrimitive.int }.getOrDefault(existing?.colorIndex ?: 0),
                    kids = b.bool("kids", existing?.kids ?: false), playlistIds = ids,
                    activePlaylistId = existing?.activePlaylistId?.takeIf { it in ids } ?: ids.firstOrNull(),   // per-device choice, not synced
                )
                val list = if (existing == null) d.profiles + p else d.profiles.map { if (it.id == p.id) p else it }
                d.copy(profiles = list, lastProfileId = d.lastProfileId ?: p.id)
            }
            "favorite" -> {
                val cur = d.favorites[item.profileId].orEmpty()
                val next = if (item.deleted) cur - item.key else if (item.key in cur) cur else listOf(item.key) + cur
                d.copy(favorites = d.favorites + (item.profileId to next))
            }
            "progress" -> {
                val cur = d.progress[item.profileId].orEmpty()
                val next = if (item.deleted) cur - item.key else {
                    val p = Progress(
                        key = item.key, position = b.long("position"), duration = b.long("duration"), updatedAt = b.long("updatedAt").takeIf { it > 0 } ?: item.updatedAt,
                        title = b.str("title").orEmpty(), image = b.str("image"), kind = b.str("kind") ?: "movie", seriesId = b.str("seriesId")?.toIntOrNull(),
                        subtitle = b.str("subtitle"), itemId = b.str("itemId").orEmpty(), ext = b.str("ext") ?: "mp4", playlistId = b.str("playlistId").orEmpty(),
                    )
                    if ((cur[item.key]?.updatedAt ?: 0) > p.updatedAt) cur else cur + (item.key to p)
                }
                d.copy(progress = d.progress + (item.profileId to next))
            }
            "dismissed" -> {
                val cur = d.dismissed[item.profileId].orEmpty()
                val next = if (item.deleted) cur - item.key else cur + (item.key to (b.long("at").takeIf { it > 0 } ?: item.updatedAt))
                d.copy(dismissed = d.dismissed + (item.profileId to next))
            }
            "setting" -> when (item.key) {
                "liveFormat" -> b.str("value")?.let { d.copy(settings = d.settings.copy(liveFormat = it)) } ?: d
                else -> d
            }
            else -> d
        }
    }

    private fun JsonObject.long(k: String): Long = runCatching { this[k]!!.jsonPrimitive.long }.getOrDefault(0L)
}

/** Simple in-memory backend shared by several engines (used by tests). */
class MemoryBackend : SyncBackend {
    private val items = LinkedHashMap<String, SyncItem>()
    private val listeners = mutableListOf<(List<SyncItem>) -> Unit>()
    val all: List<SyncItem> get() = synchronized(this) { items.values.toList() }
    var uploads = 0
        private set

    override suspend fun upload(items: List<SyncItem>) {
        val changed = synchronized(this) {
            uploads += items.size
            items.filter { n -> this.items[n.id].let { it == null || n.updatedAt > it.updatedAt } }.onEach { this.items[it.id] = it }
        }
        if (changed.isNotEmpty()) listeners.toList().forEach { it(changed) }
    }

    override fun listen(onChange: (List<SyncItem>) -> Unit): () -> Unit {
        listeners += onChange
        onChange(all)
        return { listeners -= onChange }
    }

    override suspend fun fetchAll(): List<SyncItem> = all
}

@Suppress("unused")
private fun JsonElement.asStringOrNull() = (this as? JsonPrimitive)?.contentOrNull
