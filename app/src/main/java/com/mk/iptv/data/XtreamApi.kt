package com.mk.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import java.io.IOException

/** Client for the Xtream Codes "player_api.php" API. */
class XtreamApi(private val client: OkHttpClient) {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /* -------- URL helpers -------- */

    companion object {
        /** Normalises what users type: "host:8080", "http://host:8080/", ".../player_api.php", ".../get.php?..." */
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return s.trimEnd('/')
            val port = if ((url.scheme == "http" && url.port == 80) || (url.scheme == "https" && url.port == 443)) "" else ":${url.port}"
            // Keep any path prefix the provider uses, but strip known endpoints.
            var path = url.encodedPath.trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (path.endsWith(suffix, true)) path = path.dropLast(suffix.length)
            }
            return "${url.scheme}://${url.host}$port$path"
        }

        fun liveUrl(c: Creds, id: Int, ext: String = "m3u8") =
            if (DemoServer.isDemo(c.server)) DemoServer.STREAM else "${c.server}/live/${c.username}/${c.password}/$id.$ext"
        fun movieUrl(c: Creds, id: Int, ext: String) = "${c.server}/movie/${c.username}/${c.password}/$id.${ext.ifBlank { "mp4" }}"
        fun episodeUrl(c: Creds, id: String, ext: String) = "${c.server}/series/${c.username}/${c.password}/$id.${ext.ifBlank { "mp4" }}"
    }

    private suspend fun call(c: Creds, action: String?, vararg params: Pair<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val base = "${c.server}/player_api.php".toHttpUrlOrNull()
                ?: throw IOException("Invalid server URL: ${c.server}")
            val url = base.newBuilder()
                .addQueryParameter("username", c.username)
                .addQueryParameter("password", c.password)
                .apply {
                    if (action != null) addQueryParameter("action", action)
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
            val req = Request.Builder().url(url).header("User-Agent", "MK IPTV/1.0 (Android)").build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw IOException("Wrong username or password (HTTP ${resp.code})")
                if (!resp.isSuccessful) throw IOException("Server error HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty().trim()
                if (body.isEmpty()) return@use JsonArray(emptyList())
                try {
                    json.parseToJsonElement(body)
                } catch (e: Exception) {
                    throw IOException("The server did not return Xtream data. Check the URL.")
                }
            }
        }

    /* -------- API -------- */

    suspend fun authenticate(c: Creds): AuthResult {
        val root = call(c, null) as? JsonObject ?: return AuthResult(false, "Unexpected server response", null, null, null, null)
        val ui = root["user_info"] as? JsonObject
            ?: return AuthResult(false, "Not an Xtream Codes server", null, null, null, null)
        val auth = ui.int("auth") ?: 0
        val status = ui.str("status")
        val ok = auth == 1 && (status == null || status.equals("Active", true))
        return AuthResult(
            ok = ok,
            message = when {
                auth != 1 -> "Wrong username or password"
                !ok -> "Account is $status"
                else -> null
            },
            status = status,
            expiry = ui.long("exp_date")?.let { it * 1000 },
            activeCons = ui.int("active_cons"),
            maxCons = ui.int("max_connections"),
        )
    }

    suspend fun liveCategories(c: Creds) = categories(call(c, "get_live_categories"))
    suspend fun vodCategories(c: Creds) = categories(call(c, "get_vod_categories"))
    suspend fun seriesCategories(c: Creds) = categories(call(c, "get_series_categories"))

    suspend fun liveStreams(c: Creds): List<LiveChannel> = call(c, "get_live_streams").objects().mapNotNull { o ->
        val id = o.int("stream_id") ?: return@mapNotNull null
        LiveChannel(
            id = id,
            num = o.int("num") ?: 0,
            name = o.str("name")?.trim().orEmpty(),
            logo = o.str("stream_icon")?.takeIf { it.isNotBlank() },
            categoryId = o.str("category_id"),
            epgId = o.str("epg_channel_id"),
        )
    }

    suspend fun vodStreams(c: Creds): List<VodItem> = call(c, "get_vod_streams").objects().mapNotNull { o ->
        val id = o.int("stream_id") ?: return@mapNotNull null
        VodItem(
            id = id,
            name = o.str("name")?.trim().orEmpty(),
            poster = o.str("stream_icon")?.takeIf { it.isNotBlank() },
            categoryId = o.str("category_id"),
            rating = o.double("rating")?.takeIf { it > 0 } ?: o.double("rating_5based")?.takeIf { it > 0 }?.times(2),
            added = o.long("added") ?: 0,
            ext = o.str("container_extension") ?: "mp4",
        )
    }

    suspend fun series(c: Creds): List<SeriesItem> = call(c, "get_series").objects().mapNotNull { o ->
        val id = o.int("series_id") ?: return@mapNotNull null
        SeriesItem(
            id = id,
            name = o.str("name")?.trim().orEmpty(),
            cover = o.str("cover")?.takeIf { it.isNotBlank() },
            categoryId = o.str("category_id"),
            rating = o.double("rating")?.takeIf { it > 0 } ?: o.double("rating_5based")?.takeIf { it > 0 }?.times(2),
            plot = o.str("plot"),
            genre = o.str("genre"),
            releaseDate = o.str("releaseDate") ?: o.str("release_date"),
            lastModified = o.long("last_modified") ?: 0,
            backdrop = o.firstOf("backdrop_path"),
            cast = o.str("cast"),
        )
    }

    suspend fun vodInfo(c: Creds, id: Int): VodInfo {
        val root = call(c, "get_vod_info", "vod_id" to id.toString()) as? JsonObject ?: JsonObject(emptyMap())
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val movie = root["movie_data"] as? JsonObject ?: JsonObject(emptyMap())
        return VodInfo(
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            duration = info.str("duration") ?: info.long("duration_secs")?.let { formatSecs(it) },
            releaseDate = info.str("releasedate") ?: info.str("release_date") ?: info.str("year"),
            rating = info.double("rating")?.takeIf { it > 0 },
            backdrop = info.firstOf("backdrop_path"),
            trailer = info.str("youtube_trailer")?.takeIf { it.isNotBlank() },
            ageRating = info.str("mpaa_rating") ?: info.str("age"),
            ext = movie.str("container_extension"),
            cover = info.str("movie_image") ?: info.str("cover_big"),
        )
    }

    suspend fun seriesInfo(c: Creds, id: Int): SeriesInfo {
        val root = call(c, "get_series_info", "series_id" to id.toString()) as? JsonObject ?: JsonObject(emptyMap())
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val episodes = mutableMapOf<Int, List<Episode>>()
        fun parseEp(o: JsonObject, seasonKey: Int?): Episode? {
            val epId = o.str("id") ?: return null
            val einfo = o["info"] as? JsonObject ?: JsonObject(emptyMap())
            return Episode(
                id = epId,
                num = o.int("episode_num") ?: 0,
                season = o.int("season") ?: seasonKey ?: 1,
                title = o.str("title")?.trim().orEmpty(),
                plot = einfo.str("plot"),
                image = einfo.str("movie_image") ?: einfo.str("cover_big"),
                duration = einfo.str("duration") ?: einfo.long("duration_secs")?.let { formatSecs(it) },
                ext = o.str("container_extension") ?: "mp4",
            )
        }
        when (val eps = root["episodes"]) {
            is JsonObject -> eps.forEach { (k, v) ->
                val list = (v as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> parseEp(o, k.toIntOrNull()) } }.orEmpty()
                if (list.isNotEmpty()) episodes[k.toIntOrNull() ?: list.first().season] = list.sortedBy { it.num }
            }
            is JsonArray -> eps.forEach { seasonArr ->
                val list = (seasonArr as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> parseEp(o, null) } }.orEmpty()
                if (list.isNotEmpty()) episodes[list.first().season] = list.sortedBy { it.num }
            }
            else -> Unit
        }
        return SeriesInfo(
            name = info.str("name").orEmpty(),
            plot = info.str("plot"),
            cast = info.str("cast"),
            director = info.str("director"),
            genre = info.str("genre"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            rating = info.double("rating")?.takeIf { it > 0 },
            cover = info.str("cover"),
            backdrop = info.firstOf("backdrop_path"),
            trailer = info.str("youtube_trailer")?.takeIf { it.isNotBlank() },
            seasons = episodes.keys.sorted(),
            episodes = episodes,
        )
    }

    suspend fun shortEpg(c: Creds, streamId: Int, limit: Int = 2): List<EpgEntry> {
        val root = call(c, "get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString())
        val arr = (root as? JsonObject)?.get("epg_listings") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val end = o.long("stop_timestamp") ?: return@mapNotNull null
            EpgEntry(
                title = decodeB64(o.str("title")).ifBlank { "No information" },
                description = decodeB64(o.str("description")).ifBlank { null },
                start = start * 1000,
                end = end * 1000,
            )
        }.sortedBy { it.start }
    }

    /* -------- parsing helpers -------- */

    private fun categories(el: JsonElement) = el.objects().mapNotNull { o ->
        val id = o.str("category_id") ?: return@mapNotNull null
        Category(id, o.str("category_name")?.trim().orEmpty())
    }

    private fun decodeB64(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.decodeBase64()?.utf8()?.trim() ?: s
    }

    private fun formatSecs(secs: Long): String {
        val h = secs / 3600; val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

/* -------- lenient JSON accessors (Xtream panels mix numbers/strings/nulls freely) -------- */

internal fun JsonElement.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject } // some panels return {"0":{...}}
    else -> emptyList()
}

internal fun JsonObject.str(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    if (v is JsonPrimitive) return v.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
    return null
}

internal fun JsonObject.long(key: String): Long? = str(key)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()
internal fun JsonObject.double(key: String): Double? = str(key)?.trim()?.replace(',', '.')?.toDoubleOrNull()

internal fun JsonObject.firstOf(key: String): String? = when (val v = this[key]) {
    is JsonArray -> v.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
    is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
    else -> null
}
