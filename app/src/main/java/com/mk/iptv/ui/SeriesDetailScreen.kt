package com.mk.iptv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mk.iptv.MkApp
import com.mk.iptv.cast.CastRouteButton
import com.mk.iptv.data.Episode
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.SeriesInfo
import com.mk.iptv.download.DownloadsRepository

@Composable
fun SeriesDetailScreen(seriesId: Int) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val session = rememberSession()
    val pl = session.playlist
    val series = MkApp.graph.catalog.current?.seriesById?.get(seriesId)
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(seriesId, reload) {
        failed = false
        info = MkApp.graph.catalog.seriesInfo(seriesId)
        failed = info == null
    }
    val downloads by MkApp.graph.downloads.entries.collectAsState()
    NotificationPermissionOnce()

    val title = series?.name ?: info?.name ?: ""
    val cover = series?.cover ?: info?.cover
    val backdrop = info?.backdrop ?: series?.backdrop ?: cover
    var season by remember(info) { mutableIntStateOf(info?.seasons?.firstOrNull() ?: 1) }
    val progressMap = session.data.progress[session.profileId].orEmpty()
    val allItems = remember(info, pl) { if (info != null && pl != null) Playback.episodeItems(pl, series, seriesId, info!!) else emptyList() }
    val lastWatched = remember(progressMap, info) {
        progressMap.values.filter { it.seriesId == seriesId }.maxByOrNull { it.updatedAt }
    }
    LaunchedEffect(lastWatched, info) {
        lastWatched?.let { lw -> info?.episodes?.entries?.firstOrNull { e -> e.value.any { "ep:${it.id}" == lw.key } }?.key?.let { season = it } }
    }

    Box(Modifier.fillMaxSize().background(Mk.Bg)) {
        val infoItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    Poster(backdrop, title, Modifier.fillMaxSize(), corner = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .4f), .3f to Color.Transparent, .6f to Color.Transparent, 1f to Mk.Bg)))
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Text(title.uppercase(), style = TitleStyle, fontSize = 30.sp, lineHeight = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaRow(
                            year(info?.releaseDate ?: series?.releaseDate),
                            info?.seasons?.size?.let { if (it == 1) "1 Season" else "$it Seasons" },
                            (info?.genre ?: series?.genre)?.split(",")?.firstOrNull()?.trim(),
                        )
                        ratingText(info?.rating ?: series?.rating)?.let {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.Star, null, tint = Mk.Gold, modifier = Modifier.size(14.dp))
                            Text(" $it", color = Mk.Gold, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val resumeIdx = lastWatched?.let { lw -> allItems.indexOfFirst { it.key == lw.key } } ?: -1
                    val resumeItem = allItems.getOrNull(resumeIdx)
                    val label = when {
                        resumeItem != null && lastWatched?.finished == true && resumeIdx + 1 < allItems.size ->
                            "Play " + allItems[resumeIdx + 1].subtitle?.substringBefore(" ·")
                        resumeItem != null -> "Resume " + resumeItem.subtitle?.substringBefore(" ·")
                        else -> "Play S${allItems.firstOrNull()?.subtitle?.substringAfter("S")?.substringBefore(":") ?: "1"}:E1"
                    }
                    PrimaryButton(label, {
                        val start = when {
                            resumeItem != null && lastWatched?.finished == true -> (resumeIdx + 1).coerceAtMost(allItems.lastIndex)
                            resumeIdx >= 0 -> resumeIdx
                            else -> 0
                        }
                        Playback.launch(ctx, PlayQueue(allItems, start))
                    }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth(), enabled = allItems.isNotEmpty())
                    if (lastWatched != null && !lastWatched.finished) {
                        Spacer(Modifier.height(10.dp))
                        ProgressLine(lastWatched.fraction)
                    }
                    Spacer(Modifier.height(12.dp))
                    (info?.plot ?: series?.plot)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color(0xD1F0F2FF), fontSize = 13.5.sp, lineHeight = 20.sp)
                    }
                    (info?.cast ?: series?.cast)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Starring: $it", color = Mk.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val fav = session.isFav("series:$seriesId")
                        GlassIconButton(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, { MkApp.graph.store.toggleFavorite(session.profileId, "series:$seriesId") }, size = 42.dp, tint = if (fav) Mk.Pink else Color.White, blur = false)
                        info?.trailer?.let { t -> GlassIconButton(Icons.Rounded.SmartDisplay, { Playback.openTrailer(ctx, t) }, size = 42.dp, blur = false) }
                        Spacer(Modifier.weight(1f))
                        val eps = info?.episodes?.get(season).orEmpty()
                        if (pl != null && eps.isNotEmpty()) {
                            GlassButton("Download season", {
                                Playback.downloadEpisodes(ctx, pl, seriesId, title, cover, eps)
                            }, icon = Icons.Rounded.Download, height = 42.dp)
                        }
                    }
                }
            }
        }
        val episodeItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            if (info == null) item {
                if (failed) CenterMessage("Couldn't load episodes", icon = Icons.Rounded.CloudOff, action = "Retry", onAction = { reload++ }, modifier = Modifier.height(300.dp))
                else CenterMessage("Loading episodes…", loading = true, modifier = Modifier.height(240.dp))
            } else {
                item {
                    LazyRow(
                        Modifier.padding(top = 18.dp, bottom = 6.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(info!!.seasons) { s ->
                            GlassChip("Season $s", selected = s == season, onClick = { season = s })
                        }
                    }
                }
                val eps = info!!.episodes[season].orEmpty()
                items(eps, key = { it.id }) { ep ->
                    val key = "ep:${ep.id}"
                    EpisodeRow(
                        ep = ep, fallback = cover ?: backdrop, seriesTitle = title,
                        progress = progressMap[key]?.fraction,
                        downloadId = pl?.let { DownloadsRepository.episodeId(it.id, ep.id) },
                        downloaded = pl?.let { p -> downloads.any { it.id == DownloadsRepository.episodeId(p.id, ep.id) && it.completed } } == true,
                        onPlay = { Playback.launch(ctx, PlayQueue(allItems, allItems.indexOfFirst { it.key == key }.coerceAtLeast(0))) },
                        onDownload = { pl?.let { Playback.downloadEpisodes(ctx, it, seriesId, title, cover, listOf(ep)) } },
                    )
                }
            }
        }
        if (isTwoPane()) {
            // Unfolded foldable / tablet: details on the left, seasons & episodes on the right.
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 60.dp)) { infoItems() }
                LazyColumn(Modifier.weight(1f).statusBarsPadding(), contentPadding = PaddingValues(top = 56.dp, bottom = 60.dp)) { episodeItems() }
            }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            infoItems()
            episodeItems()
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { actions.back() }, blur = false)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) { CastRouteButton(Modifier.size(44.dp)) }
        }
    }
}

@Composable
fun EpisodeRow(
    ep: Episode,
    fallback: String?,
    seriesTitle: String,
    progress: Float?,
    downloadId: String?,
    downloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(132.dp, 74.dp).tap(onPlay)) {
            Poster(ep.image ?: fallback, seriesTitle, Modifier.fillMaxSize(), corner = 12.dp)
            Box(Modifier.align(Alignment.Center).size(32.dp).glass(CircleShape).background(Color.Black.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 5.dp)) { ProgressLine(progress, height = 3.dp) }
            }
            if (downloaded) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Mk.Green.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Text("OFFLINE", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).tap(onPlay)) {
            Text("${ep.num}. ${ep.title.ifBlank { "Episode ${ep.num}" }}", color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.duration?.let { Text(it, color = Mk.Sub, fontSize = 12.sp) }
            ep.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = Mk.Sub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (downloadId != null) {
            Spacer(Modifier.width(8.dp))
            DownloadButton(downloadId, size = 38.dp, onStart = onDownload)
        }
    }
}
