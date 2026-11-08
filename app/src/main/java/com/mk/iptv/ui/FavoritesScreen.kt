package com.mk.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mk.iptv.MkApp
import com.mk.iptv.data.Catalog
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.SeriesItem
import com.mk.iptv.data.VodItem

/** Everything the current profile hearted or added to My List: movies, series and live channels. */
@Composable
fun FavoritesScreen(catalog: Catalog, session: Session, onPreview: (PreviewTarget) -> Unit) {
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("all") }
    val favs = session.favorites()
    val movies = remember(favs, catalog) { favs.mapNotNull { k -> if (k.startsWith("vod:")) k.removePrefix("vod:").toIntOrNull()?.let { catalog.vodById[it] } else null } }
    val series = remember(favs, catalog) { favs.mapNotNull { k -> if (k.startsWith("series:")) k.removePrefix("series:").toIntOrNull()?.let { catalog.seriesById[it] } else null } }
    val channels = remember(favs, catalog) { favs.mapNotNull { k -> if (k.startsWith("live:")) k.removePrefix("live:").toIntOrNull()?.let { catalog.liveById[it] } else null } }
    val progress = session.data.progress[session.profileId].orEmpty()
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    AmbientBackground {
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = full) { TabHeader("Favorites", session, hPad = 0.dp) }
            item(span = full) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    GlassChip("All · ${movies.size + series.size + channels.size}", filter == "all", { filter = "all" })
                    GlassChip("Movies · ${movies.size}", filter == "movies", { filter = "movies" })
                    GlassChip("Series · ${series.size}", filter == "series", { filter = "series" })
                    GlassChip("Channels · ${channels.size}", filter == "live", { filter = "live" })
                }
            }
            if (movies.isEmpty() && series.isEmpty() && channels.isEmpty()) item(span = full) {
                CenterMessage(
                    "No favorites yet",
                    "Tap + (My List) on any movie or series, or ♡ on a live channel, and it will appear here.",
                    icon = Icons.Rounded.Favorite, modifier = Modifier.height(460.dp),
                )
            }
            if (filter in listOf("all", "movies") && movies.isNotEmpty()) {
                item(span = full) { FavSection("Movies") }
                items(movies, key = { "m${it.id}" }) { v -> FavPoster(v.poster, v.name, progress["vod:${v.id}"]?.let { if (it.finished) 1f else it.fraction }) { onPreview(PreviewTarget.Movie(v)) } }
            }
            if (filter in listOf("all", "series") && series.isNotEmpty()) {
                item(span = full) { FavSection("Series") }
                items(series, key = { "s${it.id}" }) { s -> FavPoster(s.cover, s.name, null) { onPreview(PreviewTarget.Series(s)) } }
            }
            if (filter in listOf("all", "live") && channels.isNotEmpty()) {
                item(span = full) { FavSection("Live channels") }
                items(channels, key = { "l${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { ch ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .05f))
                            .tap { session.playlist?.let { pl -> Playback.launch(ctx, PlayQueue(Playback.liveItems(pl, channels), channels.indexOf(ch))) } }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(titleBrush(ch.name)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.LiveTv, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color(0xFF1A1D28)).padding(5.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ch.num > 0) Text("Channel ${ch.num}", color = Mk.Sub, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.Favorite, "Remove from favorites", tint = Mk.Pink, modifier = Modifier.size(22.dp).tap { MkApp.graph.store.toggleFavorite(session.profileId, "live:${ch.id}") })
                    }
                }
            }
        }
    }
}

@Composable
private fun FavSection(title: String) =
    Text(title, color = Mk.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun FavPoster(url: String?, title: String, progress: Float?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(162.dp).tap(onClick = onClick)) {
        Poster(url, title, Modifier.fillMaxSize())
        if (progress == 1f) WatchedBadge(Modifier.align(Alignment.TopStart).padding(5.dp))
        else if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { ProgressLine(progress, height = 3.dp) }
    }
}
