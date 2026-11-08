package com.mk.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mk.iptv.MkApp
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.SeriesItem
import com.mk.iptv.data.VodInfo
import com.mk.iptv.data.VodItem

sealed interface PreviewTarget {
    data class Movie(val item: VodItem) : PreviewTarget
    data class Series(val item: SeriesItem) : PreviewTarget
}

/** Netflix-style quick preview: poster, meta, brief description and one-tap actions. */
@Composable
fun PreviewSheet(target: PreviewTarget, session: Session, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(Brush.verticalGradient(listOf(Color(0xF21A1E2C), Color(0xFA0E1018))))
                .glass(RoundedCornerShape(36.dp), strength = .6f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())   // short windows: split-screen, car screens
                .padding(20.dp)
                .navigationBarsPadding()
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f)))
            Spacer(Modifier.height(14.dp))
            when (target) {
                is PreviewTarget.Movie -> MoviePreview(target.item, session, onDismiss)
                is PreviewTarget.Series -> SeriesPreview(target.item, session, onDismiss)
            }
        }
    }
}

@Composable
private fun PreviewHeader(poster: String?, title: String, onClose: () -> Unit, meta: @Composable () -> Unit) {
    Row {
        Poster(poster, title, Modifier.size(112.dp, 168.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = TitleStyle, fontSize = 22.sp, lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Close, onClose, size = 34.dp, iconSize = 17.dp, blur = false)
            }
            Spacer(Modifier.height(8.dp))
            meta()
        }
    }
}

@Composable
private fun RatingLine(rating: Double?, genre: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ratingText(rating)?.let {
            Icon(Icons.Rounded.Star, null, tint = Mk.Gold, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
        }
        genre?.let { Text(it, color = Mk.Sub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun MoviePreview(vod: VodItem, session: Session, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pl = session.playlist ?: return
    val info by produceState<VodInfo?>(null, vod.id) { value = MkApp.graph.catalog.vodInfo(vod.id) }
    val catalog = MkApp.graph.catalog.current
    val favKey = "vod:${vod.id}"
    val progress = session.data.progress[session.profileId]?.get(favKey)

    PreviewHeader(vod.poster ?: info?.cover, vod.name, onDismiss) {
        MetaRow(year(info?.releaseDate), info?.duration)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            info?.ageRating?.takeIf { it.isNotBlank() && it.length < 8 }?.let { Badge(it) }
            Badge(vod.ext.uppercase())
        }
        Spacer(Modifier.height(8.dp))
        RatingLine(info?.rating ?: vod.rating, info?.genre ?: catalog?.vodCategoryName(vod.categoryId))
    }
    Spacer(Modifier.height(14.dp))
    when {
        info == null -> Text("Loading description…", color = Mk.Faint, fontSize = 14.sp)
        info?.plot.isNullOrBlank() -> Text("No description available for this title.", color = Mk.Faint, fontSize = 14.sp)
        else -> Text(info!!.plot!!, color = Color(0xD9F0F2FF), fontSize = 14.sp, lineHeight = 21.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
    info?.cast?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text("Starring: $it", color = Mk.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    info?.director?.takeIf { it.isNotBlank() }?.let {
        Text("Director: $it", color = Mk.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (progress != null && !progress.finished) {
        Spacer(Modifier.height(12.dp))
        ProgressLine(progress.fraction)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        if (progress != null && !progress.finished && progress.position > 30_000) "Resume" else "Play",
        onClick = { Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(pl, vod, info?.ext)), 0)); onDismiss() },
        icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth(), height = 50.dp,
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        info?.trailer?.let { t -> ActionCircle(Icons.Rounded.SmartDisplay, "Trailer") { Playback.openTrailer(ctx, t) } }
        val fav = session.isFav(favKey)
        ActionCircle(if (fav) Icons.Rounded.Check else Icons.Rounded.Add, "My List") {
            MkApp.graph.store.toggleFavorite(session.profileId, favKey)
        }
        val dlId = com.mk.iptv.download.DownloadsRepository.movieId(pl.id, vod.id)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DownloadButton(dlId, size = 46.dp) { Playback.downloadMovie(ctx, pl, vod, info) }
            Spacer(Modifier.height(6.dp))
            Text("Download", color = Mk.Sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SeriesPreview(series: SeriesItem, session: Session, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val favKey = "series:${series.id}"
    val catalog = MkApp.graph.catalog.current
    PreviewHeader(series.cover, series.name, onDismiss) {
        MetaRow(year(series.releaseDate), "Series")
        Spacer(Modifier.height(8.dp))
        RatingLine(series.rating, series.genre ?: catalog?.seriesCategoryName(series.categoryId))
    }
    Spacer(Modifier.height(14.dp))
    Text(
        series.plot?.takeIf { it.isNotBlank() } ?: "No description available for this title.",
        color = if (series.plot.isNullOrBlank()) Mk.Faint else Color(0xD9F0F2FF), fontSize = 14.sp, lineHeight = 21.sp,
        maxLines = 6, overflow = TextOverflow.Ellipsis,
    )
    series.cast?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text("Starring: $it", color = Mk.Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Episodes & Play", { onDismiss(); actions.series(series.id) }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth(), height = 50.dp)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        val fav = session.isFav(favKey)
        ActionCircle(if (fav) Icons.Rounded.Check else Icons.Rounded.Add, "My List") { MkApp.graph.store.toggleFavorite(session.profileId, favKey) }
        ActionCircle(Icons.Rounded.Download, "Download") { onDismiss(); actions.series(series.id) }
        ActionCircle(Icons.Rounded.VideoLibrary, "Seasons") { onDismiss(); actions.series(series.id) }
        ActionCircle(Icons.Rounded.Info, "Details") { onDismiss(); actions.series(series.id) }
    }
}

@Composable
fun ActionCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlassIconButton(icon, onClick, size = 46.dp, iconSize = 21.dp, blur = false)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Mk.Sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Shows the live state of a download and lets the user start / pause / resume / delete it. */
@Composable
fun DownloadButton(id: String, size: Dp = 40.dp, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val entries by MkApp.graph.downloads.entries.collectAsState()
    val e = entries.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(size).glass(CircleShape).tap {
            if (e == null) onStart() else menu = true
        }, contentAlignment = Alignment.Center) {
            when {
                e == null -> Icon(Icons.Rounded.Download, "Download", tint = Color.White, modifier = Modifier.size(size * .48f))
                e.completed -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Mk.Green, modifier = Modifier.size(size * .48f))
                e.failed -> Icon(Icons.Rounded.ErrorOutline, "Failed", tint = Mk.Live, modifier = Modifier.size(size * .48f))
                e.paused -> Icon(Icons.Rounded.Pause, "Paused", tint = Mk.Gold, modifier = Modifier.size(size * .45f))
                else -> {
                    val p = (e.percent.coerceAtLeast(0f) / 100f)
                    if (p > 0f) CircularProgressIndicator(progress = { p }, modifier = Modifier.size(size * .72f), color = Mk.Accent, strokeWidth = 2.5.dp, trackColor = Color.White.copy(alpha = .15f))
                    else CircularProgressIndicator(modifier = Modifier.size(size * .72f), color = Mk.Accent, strokeWidth = 2.5.dp)
                    Text("${e.percent.toInt().coerceAtLeast(0)}", color = Color.White, fontSize = (size.value * .24f).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Mk.SheetBg)) {
            if (e != null) {
                when {
                    e.completed -> Unit
                    e.failed -> DropdownMenuItem({ Text("Retry", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = Mk.Text) }, onClick = { menu = false; MkApp.graph.downloads.retry(id) })
                    e.paused -> DropdownMenuItem({ Text("Resume", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = Mk.Text) }, onClick = { menu = false; MkApp.graph.downloads.resume(id) })
                    else -> DropdownMenuItem({ Text("Pause", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Pause, null, tint = Mk.Text) }, onClick = { menu = false; MkApp.graph.downloads.pause(id) })
                }
                DropdownMenuItem(
                    { Text(if (e.completed) "Delete download" else "Cancel download", color = Mk.Live) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Mk.Live) },
                    onClick = { menu = false; MkApp.graph.downloads.remove(id); toast(ctx, "Download removed") },
                )
            }
        }
    }
}

@Composable
fun NotificationPermissionOnce() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val act = ctx as? android.app.Activity ?: return@LaunchedEffect
            if (act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
    }
}
