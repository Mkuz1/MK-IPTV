package com.mk.iptv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.mk.iptv.MkApp
import com.mk.iptv.cast.CastRouteButton
import com.mk.iptv.data.AppData
import com.mk.iptv.data.DownloadMeta
import com.mk.iptv.data.Episode
import com.mk.iptv.data.LiveChannel
import com.mk.iptv.data.PlayItem
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.SeriesInfo
import com.mk.iptv.data.SeriesItem
import com.mk.iptv.data.VodInfo
import com.mk.iptv.data.VodItem
import com.mk.iptv.data.XtreamApi
import com.mk.iptv.download.DownloadEntry
import com.mk.iptv.download.DownloadsRepository
import com.mk.iptv.player.PlayerActivity
import java.net.URLEncoder
import kotlin.math.abs

/* ---------------- Session ---------------- */

data class Session(val data: AppData, val profile: Profile?, val playlist: Playlist?) {
    val hideAdult get() = (playlist?.hideAdult ?: true) || (profile?.kids == true)
    val profileId get() = profile?.id ?: "default"
    fun favorites() = data.favorites[profileId].orEmpty()
    fun isFav(key: String) = key in favorites()
    fun playlistsForProfile(): List<Playlist> =
        profile?.playlistIds?.mapNotNull { id -> data.playlists.firstOrNull { it.id == id } }?.takeIf { it.isNotEmpty() } ?: data.playlists
}

fun sessionOf(data: AppData): Session {
    val profile = data.profiles.firstOrNull { it.id == data.lastProfileId } ?: data.profiles.firstOrNull()
    val playlist = profile?.activePlaylistId?.let { id -> data.playlists.firstOrNull { it.id == id } }
        ?: profile?.playlistIds?.firstNotNullOfOrNull { id -> data.playlists.firstOrNull { it.id == id } }
        ?: data.playlists.firstOrNull()
    return Session(data, profile, playlist)
}

@Composable
fun rememberSession(): Session {
    val data by MkApp.graph.store.data.collectAsState()
    return remember(data) { sessionOf(data) }
}

/* ---------------- Navigation ---------------- */

class Actions(private val nav: NavController) {
    fun back() { nav.popBackStack() }
    fun home() = nav.navigate("home") { popUpTo(0) { inclusive = true } }
    fun profiles() = nav.navigate("profiles") { popUpTo(0) { inclusive = true } }
    fun series(id: Int) = nav.navigate("series/$id")
    fun category(type: String, id: String, title: String) = nav.navigate("category/$type/${enc(id)}/${enc(title)}")
    fun search() = nav.navigate("search")
    fun downloadGroup(key: String) = nav.navigate("downloads/${enc(key)}")
    fun playlistEditor(id: String? = null) = nav.navigate("playlist?id=${id ?: ""}")
    fun profileEditor(id: String? = null) = nav.navigate("profile?id=${id ?: ""}")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

val LocalActions = staticCompositionLocalOf<Actions> { error("No actions") }

/* ---------------- Poster ---------------- */

fun titleBrush(title: String): Brush {
    val h = abs(title.hashCode() % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsl(h, .55f, .28f), Color.hsl((h + 40) % 360, .55f, .10f)))
}

@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, corner: Dp = 14.dp, contentScale: ContentScale = ContentScale.Crop) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(titleBrush(title))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (failed) {
            Text(
                title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 17.sp,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = title, contentScale = contentScale,
                onError = { failed = true }, onSuccess = { failed = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/* ---------------- Header ---------------- */

@Composable
fun TabHeader(title: String, session: Session, modifier: Modifier = Modifier, showSearch: Boolean = true) {
    val actions = LocalActions.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        session.profile?.let { p ->
            Avatar(p.name, p.colorIndex, modifier = Modifier.tap { actions.profiles() })
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = TitleStyle, fontSize = 23.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
        val lists = session.playlistsForProfile()
        if (session.playlist != null) {
            Box {
                GlassChip(
                    session.playlist.name, onClick = { menu = true }, leading = Icons.Rounded.Dns,
                    trailing = if (lists.size > 1) Icons.Rounded.KeyboardArrowDown else null, height = 40.dp,
                    modifier = Modifier.widthIn(max = 116.dp),
                )
                DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Mk.SheetBg)) {
                    lists.forEach { pl ->
                        DropdownMenuItem(
                            text = { Text(pl.name, color = Mk.Text) },
                            leadingIcon = { if (pl.id == session.playlist.id) Icon(Icons.Rounded.Check, null, tint = Mk.Accent) },
                            onClick = {
                                menu = false
                                session.profile?.let { MkApp.graph.store.setActivePlaylist(it.id, pl.id) }
                                    ?: MkApp.graph.store.update { d -> d }
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Manage playlists…", color = Mk.Sub) }, onClick = { menu = false; actions.playlistEditor(null) })
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        if (showSearch) {
            GlassIconButton(Icons.Rounded.Search, { actions.search() }, size = 40.dp)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(40.dp).liquidGlass(CircleShape), contentAlignment = Alignment.Center) {
            CastRouteButton(Modifier.size(40.dp))
        }
    }
}

@Composable
fun MetaRow(vararg parts: String?, modifier: Modifier = Modifier, color: Color = Mk.Sub) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.filterNotNull().filter { it.isNotBlank() }.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.size(3.dp).clip(CircleShape).background(Mk.Faint))
            Text(p, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

fun year(date: String?): String? = date?.let { Regex("(19|20)\\d{2}").find(it)?.value }
fun ratingText(r: Double?): String? = r?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", if (it > 10) it / 10 else it) }

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
    b > 0 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    else -> "0 MB"
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

/* ---------------- Playback & downloads ---------------- */

object Playback {
    private val graph get() = MkApp.graph

    fun launch(ctx: Context, queue: PlayQueue) {
        if (queue.items.isEmpty()) return
        ctx.startActivity(PlayerActivity.intent(ctx, queue))
    }

    fun movieItem(pl: Playlist, vod: VodItem, ext: String? = null): PlayItem {
        val c = graph.catalog.creds(pl)
        val e = ext ?: vod.ext
        val dlId = DownloadsRepository.movieId(pl.id, vod.id)
        return PlayItem(
            key = "vod:${vod.id}", kind = "movie", title = vod.name, url = XtreamApi.movieUrl(c, vod.id, e),
            image = vod.poster, downloadId = dlId, itemId = vod.id.toString(), ext = e, playlistId = pl.id,
        )
    }

    fun episodeItems(pl: Playlist, series: SeriesItem?, seriesId: Int, info: SeriesInfo): List<PlayItem> {
        val c = graph.catalog.creds(pl)
        val title = series?.name ?: info.name
        return info.seasons.flatMap { s -> info.episodes[s].orEmpty() }.map { ep -> episodeItem(pl, c, seriesId, title, series?.cover ?: info.cover, ep) }
    }

    private fun episodeItem(pl: Playlist, c: com.mk.iptv.data.Creds, seriesId: Int, seriesTitle: String, cover: String?, ep: Episode) = PlayItem(
        key = "ep:${ep.id}", kind = "episode", title = seriesTitle,
        subtitle = "S${ep.season}:E${ep.num}" + (ep.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        url = XtreamApi.episodeUrl(c, ep.id, ep.ext), image = ep.image ?: cover,
        downloadId = DownloadsRepository.episodeId(pl.id, ep.id), seriesId = seriesId,
        itemId = ep.id, ext = ep.ext, playlistId = pl.id,
    )

    fun liveItems(pl: Playlist, channels: List<LiveChannel>): List<PlayItem> {
        val c = graph.catalog.creds(pl)
        return channels.map { ch ->
            PlayItem(
                key = "live:${ch.id}", kind = "live", title = ch.name, subtitle = if (ch.num > 0) "Channel ${ch.num}" else null,
                url = XtreamApi.liveUrl(c, ch.id, "m3u8"), altUrl = XtreamApi.liveUrl(c, ch.id, "ts"),
                image = ch.logo, itemId = ch.id.toString(), playlistId = pl.id,
            )
        }
    }

    fun downloadItems(entries: List<DownloadEntry>): List<PlayItem> = entries.filter { it.completed }.map { e ->
        val m = e.meta
        PlayItem(
            key = if (m.kind == "episode") "ep:${e.id.substringAfterLast(':')}" else "vod:${e.id.substringAfterLast(':')}",
            kind = m.kind, title = m.seriesTitle ?: m.title,
            subtitle = if (m.kind == "episode") "S${m.season}:E${m.episode}" + (m.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else null,
            url = runCatching { graph.downloads.resolve(m.url) }.getOrDefault(m.url), image = m.image ?: m.seriesImage, downloadId = e.id, seriesId = m.seriesId,
            itemId = e.id.substringAfterLast(':'), playlistId = m.playlistId,
        )
    }

    fun downloadMovie(ctx: Context, pl: Playlist, vod: VodItem, info: VodInfo?) {
        val ext = info?.ext ?: vod.ext
        graph.downloads.start(
            DownloadsRepository.movieId(pl.id, vod.id),
            DownloadMeta(kind = "movie", playlistId = pl.id, title = vod.name, image = vod.poster ?: info?.cover, plot = info?.plot, url = DownloadsRepository.movieUrl(pl.id, vod.id, ext)),
        )
        toast(ctx, "Downloading “${vod.name}”")
    }

    fun downloadEpisodes(ctx: Context, pl: Playlist, seriesId: Int, seriesTitle: String, seriesImage: String?, eps: List<Episode>) {
        var n = 0
        eps.forEach { ep ->
            val id = DownloadsRepository.episodeId(pl.id, ep.id)
            if (graph.downloads.entry(id) == null) {
                graph.downloads.start(
                    id,
                    DownloadMeta(
                        kind = "episode", playlistId = pl.id, title = ep.title.ifBlank { "Episode ${ep.num}" }, image = ep.image, plot = ep.plot,
                        seriesId = seriesId, seriesTitle = seriesTitle, seriesImage = seriesImage, season = ep.season, episode = ep.num,
                        url = DownloadsRepository.episodeUrl(pl.id, ep.id, ep.ext),
                    ),
                )
                n++
            }
        }
        toast(ctx, if (n == 1) "Downloading 1 episode" else if (n > 1) "Downloading $n episodes" else "Already downloaded")
    }

    fun openTrailer(ctx: Context, trailer: String) {
        // Trailer values come from the provider: only ever open http(s) links.
        val url = when {
            trailer.startsWith("https://") || trailer.startsWith("http://") -> trailer
            Regex("^[A-Za-z0-9_-]{6,20}$").matches(trailer) -> "https://www.youtube.com/watch?v=$trailer"
            else -> return
        }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }
}
