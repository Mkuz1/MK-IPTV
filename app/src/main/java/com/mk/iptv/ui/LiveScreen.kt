package com.mk.iptv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
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
    var cat by rememberSaveable { mutableStateOf(session.data.settings.lastLiveCategory) }
    LaunchedEffect(cat) { if (session.data.settings.lastLiveCategory != cat) MkApp.graph.store.updateSettings { it.copy(lastLiveCategory = cat) } }
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
    // Keep playing the chosen channel while browsing other categories.
    val selected = (selectedId?.let { catalog.liveById[it] }) ?: remember(catalog) { catalog.live.firstOrNull() }

    // Inline preview player
    val player = remember {
        com.mk.iptv.player.Players.build(ctx, MkApp.graph.downloads.upstream, preview = true).apply { volume = 0f }
    }
    // The inline preview starts muted; the speaker button turns sound on (kept while browsing channels).
    var muted by rememberSaveable { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(e: PlaybackException) {
                // Retry once with the other format (MPEG-TS <-> HLS).
                val ch = selected
                val cur = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                val first = liveFormats(session).first()
                if (ch != null && cur.endsWith(".$first")) {
                    player.setMediaItem(liveItem(XtreamApi.liveUrl(MkApp.graph.catalog.creds(pl), ch.id, liveFormats(session).last()))); player.prepare(); player.play()
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
    val previewVisible = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp >= 900 || it.screenHeightDp >= 640 }
    LaunchedEffect(selected?.id, previewVisible) {
        if (!previewVisible) { player.stop(); return@LaunchedEffect }
        val ch = selected ?: return@LaunchedEffect
        error = null
        player.setMediaItem(liveItem(XtreamApi.liveUrl(MkApp.graph.catalog.creds(pl), ch.id, liveFormats(session).first())))
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
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val threePane = cfg.screenWidthDp >= 900
    val catWidth = when { cfg.screenWidthDp < 480 -> 150.dp; cfg.screenWidthDp < 720 -> 176.dp; else -> 250.dp }
    // Short windows (car head units, split-screen): no inline preview, lists get the full height and
    // tapping a channel opens it full screen. Also avoids holding a stream nobody can see.
    val showPreview = threePane || cfg.screenHeightDp >= 640
    val counts = remember(catalog) { catalog.live.groupingBy { it.categoryId }.eachCount() }
    val favCount = favs.count { it.startsWith("live:") }
    var catQuery by rememberSaveable { mutableStateOf("") }
    var chQuery by rememberSaveable(cat) { mutableStateOf("") }
    val shownCats = remember(cats, catQuery) { if (catQuery.isBlank()) cats else cats.filter { it.name.contains(catQuery.trim(), true) } }
    val shownChannels = remember(channels, chQuery) { if (chQuery.isBlank()) channels else channels.filter { it.name.contains(chQuery.trim(), true) || it.num.toString() == chQuery.trim() } }
    val catTitle = when (cat) { "all" -> "All channels"; "fav" -> "Favorites"; else -> cats.firstOrNull { it.id == cat }?.name ?: "Channels" }

    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            TabHeader("Live TV", session)
            if (!threePane && showPreview) {
                // Phones / split-screen: compact preview on top, never more than ~1/3 of the window.
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    previewCard(Modifier.heightIn(max = (cfg.screenHeightDp * 0.30f).coerceAtLeast(120f).dp).aspectRatio(16f / 9f))
                }
            }
            // Mac Finder-style columns: categories | channels (| preview on wide screens)
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = if (threePane) 12.dp else 0.dp)) {
                LazyColumn(
                    Modifier.width(catWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 12.dp, end = 6.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (cats.size > 10) item { MiniSearch(catQuery, { catQuery = it }, "Find category") }
                    item { CategoryRow("All channels", counts.values.sum(), cat == "all", Icons.Rounded.LiveTv) { cat = "all" } }
                    item { CategoryRow("Favorites", favCount, cat == "fav", Icons.Rounded.Favorite, Mk.Pink) { cat = "fav" } }
                    item { Box(Modifier.padding(vertical = 6.dp, horizontal = 8.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .1f))) }
                    items(shownCats, key = { it.id }) { c -> CategoryRow(c.name, counts[c.id] ?: 0, cat == c.id) { cat = c.id } }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .08f)))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(catTitle, color = Mk.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Text("${channels.size}", color = Mk.Sub, fontSize = 13.sp)
                    }
                    if (channels.size > 12) MiniSearch(chQuery, { chQuery = it }, "Find channel", Modifier.padding(horizontal = 12.dp))
                    val listState = remember(cat) { androidx.compose.foundation.lazy.LazyListState() }
                    LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)) {
                        if (shownChannels.isEmpty()) item {
                            CenterMessage(
                                if (cat == "fav") "No favorite channels yet" else "No channels",
                                if (cat == "fav") "Tap ♡ next to any channel to pin it here." else null,
                                icon = if (cat == "fav") Icons.Rounded.Favorite else null, modifier = Modifier.height(260.dp),
                            )
                        }
                        items(shownChannels, key = { it.id }) { ch ->
                            ChannelRow(
                                ch, selected = ch.id == selected?.id, fav = session.isFav("live:${ch.id}"), compact = catWidth < 200.dp && !threePane,
                                onClick = { if (!showPreview || ch.id == selected?.id) openFullscreen(ch) else selectedId = ch.id },
                                onFav = { MkApp.graph.store.toggleFavorite(session.profileId, "live:${ch.id}") },
                            )
                        }
                    }
                }
                if (threePane) {
                    Column(Modifier.width((cfg.screenWidthDp * 0.36f).dp).fillMaxHeight().padding(end = 16.dp)) {
                        previewCard(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        selected?.let { ch -> Spacer(Modifier.height(12.dp)); NowNextPanel(ch, Modifier) }
                    }
                }
            }
        }
    }
}

/** One entry of the category column (Mac Finder-style list). */
@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, iconTint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Mk.Accent.copy(alpha = .22f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Mk.Accent.copy(alpha = .45f), RoundedCornerShape(12.dp)) else Modifier)
            .tap(RoundedCornerShape(12.dp), onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (selected) Color.White else iconTint, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(name, color = if (selected) Color.White else Mk.Text.copy(alpha = .85f), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        Text("$count", color = if (selected) Color.White.copy(alpha = .8f) else Mk.Faint, fontSize = 11.5.sp)
        if (selected) Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Small glass filter field used above long lists. */
@Composable
private fun MiniSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 6.dp).height(38.dp).glass(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Mk.Sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Mk.Faint, fontSize = 13.sp, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Mk.Accent), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = Mk.Sub, modifier = Modifier.size(16.dp).tap(CircleShape) { onChange("") })
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
fun epgFraction(e: EpgEntry): Float {
    val now = System.currentTimeMillis()
    return ((now - e.start).toFloat() / (e.end - e.start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

@Composable
private fun ChannelRow(ch: LiveChannel, selected: Boolean, fav: Boolean, compact: Boolean = false, onClick: () -> Unit, onFav: () -> Unit) {
    val epg by produceState(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
    val now = epg?.firstOrNull()
    val next = epg?.getOrNull(1)
    Row(
        Modifier
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 3.dp)
            .fillMaxWidth()
            .then(
                if (selected) Modifier.liquidGlass(RoundedCornerShape(22.dp)).border(1.5.dp, Mk.Accent.copy(alpha = .5f), RoundedCornerShape(22.dp))
                else Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .04f))
            )
            .tap(RoundedCornerShape(22.dp), onClick)
            .padding(if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 42.dp else 54.dp).clip(RoundedCornerShape(if (compact) 12.dp else 16.dp)).background(titleBrush(ch.name)).border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(16.dp)),
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
            modifier = Modifier.padding(start = 8.dp).size(22.dp).tap(onClick = onFav),
        )
    }
}


/** Larger now/next guide shown beside the player on unfolded screens. */
@Composable
private fun NowNextPanel(ch: LiveChannel, modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    val epg by produceState(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp)) {
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


/** Preferred order of live formats: MPEG-TS first ("Fastest") or HLS first. */
fun liveFormats(session: Session) = if (session.data.settings.liveFormat == "hls") listOf("m3u8", "ts") else listOf("ts", "m3u8")

private fun liveItem(url: String): MediaItem = MediaItem.Builder().setUri(url)
    .apply { if (url.endsWith(".m3u8")) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
    .build()
