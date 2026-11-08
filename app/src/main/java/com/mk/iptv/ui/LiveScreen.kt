package com.mk.iptv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mk.iptv.MkApp
import com.mk.iptv.data.Catalog
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.EpgEntry
import com.mk.iptv.data.LiveChannel
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.XtreamApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveScreen(catalog: Catalog, session: Session) {
    val ctx = LocalContext.current
    val pl = session.playlist ?: return
    var cat by rememberSaveable { mutableStateOf("all") }
    val cats = remember(catalog, session.hideAdult) {
        catalog.liveCategories.filter { !session.hideAdult || !CatalogRepository.isAdult(it.name) }
    }
    val favs = session.favorites()
    val channels = remember(catalog, cat, favs, cats) {
        val allowed = cats.map { it.id }.toSet()
        val base = catalog.live.filter { it.categoryId == null || it.categoryId in allowed }
        when (cat) {
            "all" -> base
            "fav" -> favs.mapNotNull { k -> if (k.startsWith("live:")) k.removePrefix("live:").toIntOrNull()?.let { catalog.liveById[it] } else null }
            else -> base.filter { it.categoryId == cat }
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = (selectedId?.let { catalog.liveById[it] }) ?: channels.firstOrNull()

    // Inline preview player
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MkApp.graph.downloads.upstream))
            .build().apply { volume = 1f }
    }
    var muted by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(e: PlaybackException) {
                // Retry once with the MPEG-TS URL many panels prefer.
                val ch = selected
                val cur = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                if (ch != null && cur.endsWith(".m3u8")) {
                    player.setMediaItem(MediaItem.fromUri(XtreamApi.liveUrl(MkApp.graph.catalog.creds(pl), ch.id, "ts"))); player.prepare(); player.play()
                } else error = "This channel is not available right now"
            }
        }
        player.addListener(l)
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) player.pause()
            if (ev == Lifecycle.Event.ON_START && player.mediaItemCount > 0) player.play()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); player.removeListener(l); player.release() }
    }
    LaunchedEffect(selected?.id) {
        val ch = selected ?: return@LaunchedEffect
        error = null
        player.setMediaItem(MediaItem.fromUri(XtreamApi.liveUrl(MkApp.graph.catalog.creds(pl), ch.id, "m3u8")))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    fun openFullscreen(ch: LiveChannel) {
        player.pause()
        val items = Playback.liveItems(pl, channels)
        Playback.launch(ctx, PlayQueue(items, channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)))
    }

    val previewCard: @Composable (Modifier) -> Unit = { outer ->
        Box(
            outer
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(26.dp))
                        .tap { selected?.let { openFullscreen(it) } }
                ) {
                    AndroidView(
                        factory = { c ->
                            PlayerView(c).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                this.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (selected == null) CenterMessage("No channels", icon = Icons.Rounded.LiveTv)
                    if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
                    error?.let { Text(it, color = Mk.Sub, modifier = Modifier.align(Alignment.Center).padding(20.dp)) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
                    Row(Modifier.padding(12.dp).align(Alignment.TopStart).clip(RoundedCornerShape(50)).background(Mk.Live.copy(alpha = .85f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassIconButton(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, { muted = !muted }, size = 36.dp, iconSize = 17.dp, blur = false)
                        GlassIconButton(Icons.Rounded.Fullscreen, { selected?.let { openFullscreen(it) } }, size = 36.dp, iconSize = 19.dp, blur = false)
                    }
                    selected?.let { ch ->
                        val epg by produceState<List<EpgEntry>?>(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                            Text("${if (ch.num > 0) "${ch.num} · " else ""}${ch.name}", color = Color.White.copy(alpha = .8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(epg?.firstOrNull()?.title ?: ch.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            epg?.firstOrNull()?.let { now -> Spacer(Modifier.height(7.dp)); ProgressLine(epgFraction(now), color = Brush.horizontalGradient(listOf(Mk.Live, Mk.Pink))) }
                        }
                    }
                }
    }
    val chips: @Composable () -> Unit = {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    item { GlassChip("All · ${catalog.live.size}", selected = cat == "all", onClick = { cat = "all" }) }
                    item { GlassChip("Favorites", selected = cat == "fav", onClick = { cat = "fav" }, leading = Icons.Rounded.Favorite) }
                    items(cats, key = { it.id }) { c -> GlassChip(c.name, selected = cat == c.id, onClick = { cat = c.id }) }
                }
    }

    AmbientBackground {
        if (isTwoPane()) {
            // Unfolded foldable / tablet: player + now-playing on the left, channel guide on the right.
            Column(Modifier.fillMaxSize()) {
                TabHeader("Live TV", session)
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.15f)) {
                        previewCard(Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f))
                        selected?.let { ch -> NowNextPanel(ch) }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)) {
                        item { chips() }
                        if (channels.isEmpty()) item {
                CenterMessage(if (cat == "fav") "No favorite channels" else "No channels", if (cat == "fav") "Tap ♡ on a channel to add it here." else null, modifier = Modifier.height(260.dp))
                        }
            items(channels, key = { it.id }) { ch ->
                ChannelRow(
                    ch, selected = ch.id == selected?.id, fav = session.isFav("live:${ch.id}"),
                    onClick = { if (ch.id == selected?.id) openFullscreen(ch) else selectedId = ch.id },
                    onFav = { MkApp.graph.store.toggleFavorite(session.profileId, "live:${ch.id}") },
                )
            }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item { TabHeader("Live TV", session) }
            item { previewCard(Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f)) }
            item { chips() }
            if (channels.isEmpty()) item {
                CenterMessage(if (cat == "fav") "No favorite channels" else "No channels", if (cat == "fav") "Tap ♡ on a channel to add it here." else null, modifier = Modifier.height(260.dp))
            }
            items(channels, key = { it.id }) { ch ->
                ChannelRow(
                    ch, selected = ch.id == selected?.id, fav = session.isFav("live:${ch.id}"),
                    onClick = { if (ch.id == selected?.id) openFullscreen(ch) else selectedId = ch.id },
                    onFav = { MkApp.graph.store.toggleFavorite(session.profileId, "live:${ch.id}") },
                )
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
fun epgFraction(e: EpgEntry): Float {
    val now = System.currentTimeMillis()
    return ((now - e.start).toFloat() / (e.end - e.start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

@Composable
private fun ChannelRow(ch: LiveChannel, selected: Boolean, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    val epg by produceState(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
    val now = epg?.firstOrNull()
    val next = epg?.getOrNull(1)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .then(
                if (selected) Modifier.liquidGlass(RoundedCornerShape(22.dp)).border(1.5.dp, Mk.Accent.copy(alpha = .5f), RoundedCornerShape(22.dp))
                else Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .04f))
            )
            .tap(onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(titleBrush(ch.name)).border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(ch.name.split(" ").filter { it.isNotBlank() }.take(3).joinToString("") { it.take(1) }.uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
            if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, ch.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color(0xFF1A1D28)).padding(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("${if (ch.num > 0) "${ch.num} · " else ""}${ch.name}", color = Mk.Sub, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                now?.let { Text("${timeFmt.format(Date(it.start))} – ${timeFmt.format(Date(it.end))}", color = Mk.Sub, fontSize = 11.sp) }
            }
            Text(now?.title ?: ch.name, color = Mk.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Spacer(Modifier.height(5.dp))
                ProgressLine(epgFraction(now), height = 3.dp)
            }
            next?.let { Text("Next: ${it.title}", color = Mk.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
        }
        Icon(
            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite",
            tint = if (fav) Mk.Pink else Color.White.copy(alpha = .4f),
            modifier = Modifier.padding(start = 8.dp).size(22.dp).tap(onFav),
        )
    }
}


/** Larger now/next guide shown beside the player on unfolded screens. */
@Composable
private fun NowNextPanel(ch: LiveChannel) {
    val epg by produceState(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp)) {
        Text("${if (ch.num > 0) "${ch.num} · " else ""}${ch.name}", color = Mk.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val now = epg?.firstOrNull()
        Text("NOW", color = Mk.Live, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 10.dp))
        Text(now?.title ?: "No guide information", color = Mk.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        now?.let {
            Text("${timeFmt.format(Date(it.start))} – ${timeFmt.format(Date(it.end))}", color = Mk.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp)); ProgressLine(epgFraction(it), color = Brush.horizontalGradient(listOf(Mk.Live, Mk.Pink)))
            it.description?.let { d -> Text(d, color = Mk.Sub, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        }
        epg?.getOrNull(1)?.let { n ->
            Text("NEXT · ${timeFmt.format(Date(n.start))}", color = Mk.Faint, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 14.dp))
            Text(n.title, color = Mk.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
