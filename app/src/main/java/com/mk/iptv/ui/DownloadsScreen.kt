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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.download.DownloadEntry
import com.mk.iptv.download.DownloadGroup

@Composable
fun DownloadsScreen(session: Session) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val repo = MkApp.graph.downloads
    val entries by repo.entries.collectAsState()
    val groups = remember(entries) { repo.groups(entries) }
    val used = entries.sumOf { it.bytes }
    val anyActive = entries.any { it.active }
    val anyPaused = entries.any { it.paused }
    var confirmDelete by remember { mutableStateOf<DownloadGroup?>(null) }

    AmbientBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item { TabHeader("Downloads", session, showSearch = false) }
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("${formatBytes(used)} used · ${formatBytes(repo.freeSpace())} free", onClick = {}, leading = Icons.Rounded.SdStorage)
                    if (anyActive) GlassChip("Pause all", onClick = { repo.pauseAll() }, leading = Icons.Rounded.Pause)
                    else if (anyPaused) GlassChip("Resume all", onClick = { repo.resumeAll(); entries.filter { it.paused }.forEach { repo.resume(it.id) } }, leading = Icons.Rounded.PlayArrow)
                }
            }
            if (groups.isEmpty()) item {
                CenterMessage(
                    "No downloads yet",
                    "Tap the download button on any movie or episode to watch it offline — even on a plane.",
                    icon = Icons.Rounded.Download, modifier = Modifier.height(480.dp),
                )
            }
            items(groups, key = { it.key }) { g ->
                DownloadGroupCard(
                    g,
                    onClick = {
                        if (g.isSeries) actions.downloadGroup(g.key)
                        else {
                            val e = g.entries.first()
                            if (e.completed) Playback.launch(ctx, PlayQueue(Playback.downloadItems(listOf(e)), 0))
                            else toast(ctx, if (e.failed) "Download failed — use ⋮ to retry" else "Still downloading (${e.percent.toInt().coerceAtLeast(0)}%)")
                        }
                    },
                    onDelete = { confirmDelete = g },
                )
            }
        }
    }
    confirmDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Mk.SheetBg,
            title = { Text("Delete download?", color = Mk.Text) },
            text = { Text(if (g.isSeries) "All ${g.entries.size} downloaded episodes of “${g.title}” will be removed from this device." else "“${g.title}” will be removed from this device.", color = Mk.Sub) },
            confirmButton = { TextButton({ g.entries.forEach { repo.remove(it.id) }; confirmDelete = null }) { Text("Delete", color = Mk.Live) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel", color = Mk.Text) } },
        )
    }
}

@Composable
private fun DownloadGroupCard(g: DownloadGroup, onClick: () -> Unit, onDelete: () -> Unit) {
    val repo = MkApp.graph.downloads
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .liquidGlass(RoundedCornerShape(24.dp))
            .tap(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Poster(g.image, g.title, Modifier.size(76.dp, 112.dp), corner = 12.dp)
            if (g.completedCount == g.entries.size) {
                Box(Modifier.align(Alignment.Center).size(34.dp).glass(CircleShape).background(Color.Black.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (g.isSeries) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.title, color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val kind = if (g.isSeries) {
                val seasons = g.entries.mapNotNull { it.meta.season }.distinct().size
                "Series · ${g.entries.size} episode${if (g.entries.size == 1) "" else "s"}" + if (seasons > 1) " · $seasons seasons" else ""
            } else "Movie"
            Text("$kind · ${formatBytes(g.bytes)}", color = Mk.Sub, fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            StatusLine(g)
        }
        Box {
            GlassIconButton(Icons.Rounded.MoreVert, { menu = true }, size = 36.dp, iconSize = 18.dp, blur = false)
            DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Mk.SheetBg)) {
                if (g.entries.any { it.active }) DropdownMenuItem({ Text("Pause", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Pause, null, tint = Mk.Text) }, onClick = { menu = false; g.entries.filter { it.active }.forEach { repo.pause(it.id) } })
                if (g.entries.any { it.paused }) DropdownMenuItem({ Text("Resume", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = Mk.Text) }, onClick = { menu = false; g.entries.filter { it.paused }.forEach { repo.resume(it.id) } })
                if (g.entries.any { it.failed }) DropdownMenuItem({ Text("Retry failed", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = Mk.Text) }, onClick = { menu = false; g.entries.filter { it.failed }.forEach { repo.retry(it.id) } })
                DropdownMenuItem({ Text("Delete", color = Mk.Live) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Mk.Live) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun StatusLine(g: DownloadGroup) {
    val done = g.completedCount == g.entries.size
    val failed = g.entries.count { it.failed }
    val paused = g.entries.count { it.paused }
    val (text, color) = when {
        done -> (if (g.isSeries) "All episodes downloaded" else "Downloaded · ready offline") to Mk.Green
        g.activeCount > 0 -> "Downloading ${(g.progress * 100).toInt()}%" + (if (g.isSeries) " · ${g.completedCount}/${g.entries.size} done" else "") to Mk.Accent
        failed > 0 -> "$failed failed" to Mk.Live
        paused > 0 -> "Paused" to Mk.Gold
        else -> "Queued" to Mk.Sub
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (!done) {
        Spacer(Modifier.height(6.dp))
        ProgressLine(g.progress)
    }
}

/* ---------------- Downloaded series: every downloaded episode ---------------- */

@Composable
fun DownloadedSeriesScreen(groupKey: String) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val repo = MkApp.graph.downloads
    val entries by repo.entries.collectAsState()
    val group = remember(entries, groupKey) { repo.groups(entries).firstOrNull { it.key == groupKey } }
    var confirmAll by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Mk.Bg)) {
        if (group == null) {
            CenterMessage("Nothing downloaded", "All episodes of this series were removed.", icon = Icons.Rounded.Download, action = "Back", onAction = { actions.back() })
            return@Box
        }
        val bySeason = group.entries.groupBy { it.meta.season ?: 1 }.toSortedMap()
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    Poster(group.image, group.title, Modifier.fillMaxSize(), corner = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .45f), .4f to Color.Black.copy(alpha = .2f), 1f to Mk.Bg)))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text("DOWNLOADED", color = Mk.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Text(group.title, style = TitleStyle, fontSize = 28.sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${group.entries.size} episodes · ${formatBytes(group.bytes)}", color = Mk.Sub, fontSize = 13.sp)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val playable = Playback.downloadItems(group.entries)
                    PrimaryButton("Play all", { Playback.launch(ctx, PlayQueue(playable, 0)) }, icon = Icons.Rounded.PlayArrow, enabled = playable.isNotEmpty(), modifier = Modifier.weight(1f))
                    GlassButton("Delete all", { confirmAll = true }, icon = Icons.Rounded.Delete, tint = Mk.Live, height = 48.dp)
                }
            }
            bySeason.forEach { (season, eps) ->
                item(key = "s$season") { SectionTitle("Season $season · ${eps.size} episode${if (eps.size == 1) "" else "s"}") }
                items(eps, key = { it.id }) { e ->
                    DownloadedEpisodeRow(e, group.image) {
                        val playable = group.entries.filter { it.completed }
                        val idx = playable.indexOfFirst { it.id == e.id }
                        if (idx >= 0) Playback.launch(ctx, PlayQueue(Playback.downloadItems(playable), idx))
                        else toast(ctx, if (e.failed) "Download failed — use the button to retry" else "Still downloading (${e.percent.toInt().coerceAtLeast(0)}%)")
                    }
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { actions.back() }, blur = false)
        }
    }
    if (confirmAll && group != null) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = Mk.SheetBg,
            title = { Text("Delete all episodes?", color = Mk.Text) },
            text = { Text("${group.entries.size} episodes (${formatBytes(group.bytes)}) will be removed from this device.", color = Mk.Sub) },
            confirmButton = { TextButton({ group.entries.forEach { repo.remove(it.id) }; confirmAll = false; actions.back() }) { Text("Delete", color = Mk.Live) } },
            dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel", color = Mk.Text) } },
        )
    }
}

@Composable
private fun DownloadedEpisodeRow(e: DownloadEntry, fallback: String?, onPlay: () -> Unit) {
    val progress = rememberSession().data.let { d -> d.progress[sessionOf(d).profileId]?.get("ep:${e.id.substringAfterLast(':')}") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp, 74.dp).tap(onPlay)) {
            Poster(e.meta.image ?: fallback, e.meta.title, Modifier.fillMaxSize(), corner = 12.dp)
            if (e.completed) Box(Modifier.align(Alignment.Center).size(32.dp).glass(CircleShape).background(Color.Black.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            progress?.let { p -> if (p.fraction > 0) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 5.dp)) { ProgressLine(p.fraction, height = 3.dp) } }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).tap(onPlay)) {
            Text("E${e.meta.episode ?: "?"} · ${e.meta.title}", color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val status = when {
                e.completed -> "Downloaded · ${formatBytes(e.bytes)}"
                e.failed -> "Failed"
                e.paused -> "Paused · ${e.percent.toInt().coerceAtLeast(0)}%"
                else -> "Downloading · ${e.percent.toInt().coerceAtLeast(0)}%"
            }
            Text(status, color = when { e.completed -> Mk.Sub; e.failed -> Mk.Live; e.paused -> Mk.Gold; else -> Mk.Accent }, fontSize = 12.sp)
            if (!e.completed) { Spacer(Modifier.height(5.dp)); ProgressLine(e.percent.coerceAtLeast(0f) / 100f) }
        }
        Spacer(Modifier.width(8.dp))
        DownloadButton(e.id, size = 38.dp) {}
    }
}
