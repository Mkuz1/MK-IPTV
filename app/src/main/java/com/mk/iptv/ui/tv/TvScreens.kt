package com.mk.iptv.ui.tv

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mk.iptv.MkApp
import com.mk.iptv.data.Catalog
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.CatalogState
import com.mk.iptv.data.EpgEntry
import com.mk.iptv.data.LiveChannel
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Progress
import com.mk.iptv.data.SeriesInfo
import com.mk.iptv.data.SeriesItem
import com.mk.iptv.data.VodInfo
import com.mk.iptv.data.VodItem
import com.mk.iptv.data.XtreamApi
import com.mk.iptv.download.DownloadsRepository
import com.mk.iptv.ui.AmbientBackground
import com.mk.iptv.ui.Avatar
import com.mk.iptv.ui.CatalogGate
import com.mk.iptv.ui.CenterMessage
import com.mk.iptv.ui.DownloadButton
import com.mk.iptv.ui.DownloadsScreen
import com.mk.iptv.ui.GlassChip
import com.mk.iptv.ui.LocalActions
import com.mk.iptv.ui.MetaRow
import com.mk.iptv.ui.Mk
import com.mk.iptv.ui.Playback
import com.mk.iptv.ui.Poster
import com.mk.iptv.ui.PrimaryButton
import com.mk.iptv.ui.ProgressLine
import com.mk.iptv.ui.Session
import com.mk.iptv.ui.TitleStyle
import com.mk.iptv.ui.WatchedBadge
import com.mk.iptv.ui.continueWatching
import com.mk.iptv.ui.epgFraction
import com.mk.iptv.ui.glass
import com.mk.iptv.ui.liveFormats
import com.mk.iptv.ui.progressFor
import com.mk.iptv.ui.ratingText
import com.mk.iptv.ui.rememberSession
import com.mk.iptv.ui.tap
import com.mk.iptv.ui.tapOrHold
import com.mk.iptv.ui.titleBrush
import com.mk.iptv.ui.year
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ======================= TV home (left menu + content) ======================= */

private enum class TvTab(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Rounded.Search), LIVE("Live TV", Icons.Rounded.LiveTv), MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.VideoLibrary), FAVORITES("Favorites", Icons.Rounded.Favorite),
    DOWNLOADS("Downloads", Icons.Rounded.Download), SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun TvHomeScreen() {
    val session = rememberSession()
    val actions = LocalActions.current
    val state by MkApp.graph.catalog.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(TvTab.MOVIES) }
    LaunchedEffect(session.playlist?.id) { session.playlist?.id?.let { MkApp.graph.catalog.load(it) } }
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { startFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().background(Mk.Bg)) {
        // ---- left menu: moving focus over an item switches the section (Google TV pattern) ----
        Column(
            Modifier.width(196.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0xFF0D1020), Mk.Bg))).padding(vertical = 22.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(start = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                session.profile?.let { Avatar(it.name, it.colorIndex, size = 38.dp, modifier = Modifier.tap(CircleShape) { actions.profiles() }) }
                Spacer(Modifier.width(10.dp))
                Text("MK IPTV", color = Mk.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 1.sp)
            }
            session.playlist?.let { pl ->
                val busy by MkApp.graph.catalog.refreshingNow.collectAsState()
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                TvMenuAction(if (busy) "Refreshing…" else "Refresh library", Icons.Rounded.Refresh) {
                    if (!busy) scope.launch {
                        runCatching { MkApp.graph.catalog.refreshNow(pl.id) }
                            .onSuccess { com.mk.iptv.ui.toast(ctx, "Library updated · ${it.live.size} channels · ${it.vod.size} movies · ${it.series.size} series") }
                            .onFailure { com.mk.iptv.ui.toast(ctx, "Couldn't refresh: ${it.message}") }
                    }
                }
            }
            TvTab.entries.forEach { t ->
                var focused by remember { mutableStateOf(false) }
                val on = t == tab
                val color by animateColorAsState(if (focused) Color(0xFF0A0B10) else if (on) Color(0xFF9FD3FF) else Color.White.copy(alpha = .7f), label = "m")
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .then(if (t == TvTab.MOVIES) Modifier.focusRequester(startFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused; if (it.isFocused && t != TvTab.SETTINGS && t != TvTab.SEARCH) tab = t }
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (focused) Color.White else if (on) Color.White.copy(alpha = .10f) else Color.Transparent)
                        .tap(RoundedCornerShape(24.dp)) {
                            when (t) {
                                TvTab.SETTINGS -> actions.playlists()
                                TvTab.SEARCH -> actions.search()
                                else -> tab = t
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, tint = color, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(t.label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                TvTab.LIVE -> CatalogGate(state, session) { TvLive(it, session) }
                TvTab.MOVIES -> CatalogGate(state, session) { TvBrowse(it, session, movies = true) }
                TvTab.SERIES -> CatalogGate(state, session) { TvBrowse(it, session, movies = false) }
                TvTab.FAVORITES -> CatalogGate(state, session) { TvFavorites(it, session) }
                TvTab.DOWNLOADS -> DownloadsScreen(session)
                else -> Unit
            }
        }
    }
}

@Composable
private fun TvMenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) Color(0xFF0A0B10) else Color.White.copy(alpha = .7f)
    Row(
        Modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White else Color.Transparent).tap(RoundedCornerShape(22.dp), onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ======================= Focusable cards ======================= */

@Composable
fun TvCard(
    image: String?,
    title: String,
    width: Dp,
    height: Dp,
    onFocus: () -> Unit = {},
    badge: @Composable (() -> Unit)? = null,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "s")
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(14.dp))
            .tap(RoundedCornerShape(14.dp), onClick),
    ) {
        Poster(image, title, Modifier.fillMaxSize())
        badge?.let { Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { it() } }
        if (progress != null && progress > 0f && progress < 1f) Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { ProgressLine(progress, height = 3.dp) }
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(14.dp)))
    }
}

private data class TvShelf(val title: String, val items: List<Any>)

/* ======================= Movies / Series browse ======================= */

@Composable
fun TvBrowse(catalog: Catalog, session: Session, movies: Boolean) {
    val actions = LocalActions.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelves = remember(catalog, session.favorites(), movies, session.hideAdult) { shelvesFor(catalog, session, movies) }
    val cont = remember(session.data, movies) { continueWatching(session, movies) }
    var focusedItem by remember { mutableStateOf<Any?>(shelves.firstOrNull()?.items?.firstOrNull()) }

    Box(Modifier.fillMaxSize()) {
        TvHero(focusedItem, catalog, Modifier.fillMaxWidth().height(300.dp))
        LazyColumn(Modifier.fillMaxSize().padding(top = 250.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (cont.isNotEmpty()) item {
                TvRowTitle("Continue Watching")
                LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(cont, key = { it.key }) { p ->
                        TvCard(
                            p.image, p.title, 240.dp, 135.dp,
                            onFocus = { focusedItem = p },
                            badge = if (p.finished) ({ TvPill("UP NEXT", Mk.Accent) }) else null,
                            progress = if (p.finished) null else p.fraction,
                        ) { tvResume(ctx, p, session, scope, actions) }
                    }
                }
            }
            shelves.forEach { shelf ->
                item(key = "${shelf.title}") {
                    TvRowTitle(shelf.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(shelf.items.size) { i ->
                            val it = shelf.items[i]
                            when (it) {
                                is VodItem -> TvCard(it.poster, it.name, 132.dp, 198.dp, onFocus = { focusedItem = it },
                                    progress = session.data.progress[session.profileId]?.get("vod:${it.id}")?.fraction) { actions.movie(it.id) }
                                is SeriesItem -> TvCard(it.cover, it.name, 132.dp, 198.dp, onFocus = { focusedItem = it }) { actions.series(it.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shelvesFor(catalog: Catalog, session: Session, movies: Boolean): List<TvShelf> {
    val cats = (if (movies) catalog.vodCategories else catalog.seriesCategories).filter { !session.hideAdult || !CatalogRepository.isAdult(it.name) }
    val allowed = cats.map { it.id }.toSet()
    return if (movies) {
        val vod = catalog.vod.filter { it.categoryId in allowed || it.categoryId == null }
        val by = vod.groupBy { it.categoryId }
        buildList {
            val fav = session.favorites().mapNotNull { k -> if (k.startsWith("vod:")) k.removePrefix("vod:").toIntOrNull()?.let { catalog.vodById[it] } else null }
            if (fav.isNotEmpty()) add(TvShelf("My List", fav))
            add(TvShelf("Recently Added", vod.sortedByDescending { it.added }.take(30)))
            vod.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(30).takeIf { it.size >= 5 }?.let { add(TvShelf("Top Rated", it)) }
            cats.forEach { c -> by[c.id]?.takeIf { it.isNotEmpty() }?.let { add(TvShelf(c.name, it.sortedByDescending { v -> v.added }.take(40))) } }
        }
    } else {
        val ser = catalog.series.filter { it.categoryId in allowed || it.categoryId == null }
        val by = ser.groupBy { it.categoryId }
        buildList {
            val fav = session.favorites().mapNotNull { k -> if (k.startsWith("series:")) k.removePrefix("series:").toIntOrNull()?.let { catalog.seriesById[it] } else null }
            if (fav.isNotEmpty()) add(TvShelf("My List", fav))
            add(TvShelf("New Episodes", ser.sortedByDescending { it.lastModified }.take(30)))
            cats.forEach { c -> by[c.id]?.takeIf { it.isNotEmpty() }?.let { add(TvShelf(c.name, it.sortedByDescending { s -> s.lastModified }.take(40))) } }
        }
    }
}

private fun tvResume(ctx: android.content.Context, p: Progress, session: Session, scope: kotlinx.coroutines.CoroutineScope, actions: com.mk.iptv.ui.Actions) {
    val pl = session.data.playlists.firstOrNull { it.id == p.playlistId } ?: session.playlist ?: return
    if (p.kind == "movie") {
        val vod = p.itemId.toIntOrNull()?.let { MkApp.graph.catalog.current?.vodById?.get(it) } ?: VodItem(p.itemId.toIntOrNull() ?: return, p.title, p.image, null, null, 0, p.ext)
        Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(pl, vod, p.ext)), 0))
    } else scope.launch {
        val sid = p.seriesId ?: return@launch
        val info = MkApp.graph.catalog.seriesInfo(sid, pl.id) ?: return@launch
        val items = Playback.episodeItems(pl, MkApp.graph.catalog.current?.seriesById?.get(sid), sid, info)
        val idx = items.indexOfFirst { it.key == p.key }.coerceAtLeast(0) + if (p.finished) 1 else 0
        if (idx > items.lastIndex) actions.series(sid) else Playback.launch(ctx, PlayQueue(items, idx))
    }
}

@Composable
private fun TvRowTitle(t: String) = Text(t, color = Mk.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 32.dp, top = 14.dp))

@Composable
fun TvPill(text: String, color: Color) =
    Text(text, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 7.dp, vertical = 2.dp))

/** Large backdrop + description of whatever card has focus (loads the plot lazily). */
@Composable
private fun TvHero(item: Any?, catalog: Catalog, modifier: Modifier) {
    val vod = item as? VodItem
    val series = item as? SeriesItem
    val prog = item as? Progress
    val info by produceState<VodInfo?>(null, vod?.id) { value = null; if (vod != null) { delay(350); value = MkApp.graph.catalog.vodInfo(vod.id) } }
    val title = vod?.name ?: series?.name ?: prog?.title ?: ""
    val image = info?.backdrop ?: vod?.poster ?: series?.backdrop ?: series?.cover ?: prog?.image
    Box(modifier) {
        if (item != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f)) {
                Poster(image, title, Modifier.fillMaxSize(), corner = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Mk.Bg, .45f to Mk.Bg.copy(alpha = .4f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Mk.Bg)))
            }
            Column(Modifier.padding(start = 32.dp, top = 28.dp).widthIn(max = 460.dp)) {
                Text(title, style = TitleStyle, fontSize = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaRow(
                        year(info?.releaseDate ?: series?.releaseDate), info?.duration,
                        (info?.genre ?: series?.genre ?: vod?.let { catalog.vodCategoryName(it.categoryId) })?.split(",")?.firstOrNull(),
                        prog?.subtitle?.substringBefore(" ·"),
                    )
                    ratingText(info?.rating ?: vod?.rating ?: series?.rating)?.let {
                        Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.Star, null, tint = Mk.Gold, modifier = Modifier.size(14.dp))
                        Text(" $it", color = Mk.Gold, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(info?.plot ?: series?.plot ?: "", color = Color(0xD9F0F2FF), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ======================= Movie detail ======================= */

@Composable
fun TvMovieScreen(vodId: Int) {
    val ctx = LocalContext.current
    val session = rememberSession()
    val pl = session.playlist
    val vod = MkApp.graph.catalog.current?.vodById?.get(vodId) ?: return CenterMessage("Movie not found")
    val info by produceState<VodInfo?>(null, vodId) { value = MkApp.graph.catalog.vodInfo(vodId) }
    val progress = session.data.progress[session.profileId]?.get("vod:$vodId")
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Mk.Bg)) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f)) {
            Poster(info?.backdrop ?: vod.poster, vod.name, Modifier.fillMaxSize(), corner = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Mk.Bg, .5f to Mk.Bg.copy(alpha = .5f), 1f to Color.Transparent)))
        }
        Row(Modifier.fillMaxSize().padding(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(vod.poster ?: info?.cover, vod.name, Modifier.size(220.dp, 330.dp), corner = 18.dp)
            Spacer(Modifier.width(36.dp))
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(vod.name, style = TitleStyle, fontSize = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                MetaRow(year(info?.releaseDate), info?.duration, info?.genre, info?.ageRating, ratingText(info?.rating ?: vod.rating)?.let { "★ $it" })
                Spacer(Modifier.height(14.dp))
                Text(info?.plot ?: "Loading…", color = Color(0xD9F0F2FF), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                info?.cast?.takeIf { it.isNotBlank() }?.let { Text("Starring: $it", color = Mk.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                if (progress != null && !progress.finished) { Spacer(Modifier.height(14.dp)); ProgressLine(progress.fraction, modifier = Modifier.widthIn(max = 360.dp)) }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val resume = progress != null && !progress.finished && progress.position > 10_000
                    PrimaryButton(if (resume) "Resume" else "Play", {
                        pl?.let { Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(it, vod, info?.ext)), 0)) }
                    }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus))
                    if (resume) com.mk.iptv.ui.GlassButton("From start", {
                        pl?.let { MkApp.graph.store.removeProgress(session.profileId, listOf("vod:$vodId")); Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(it, vod, info?.ext)), 0)) }
                    }, icon = Icons.Rounded.Replay)
                    val fav = session.isFav("vod:$vodId")
                    com.mk.iptv.ui.GlassButton(if (fav) "In My List" else "My List", { MkApp.graph.store.toggleFavorite(session.profileId, "vod:$vodId") }, icon = if (fav) Icons.Rounded.Check else Icons.Rounded.Add)
                    if (pl != null) DownloadButton(DownloadsRepository.movieId(pl.id, vodId), size = 46.dp) { Playback.downloadMovie(ctx, pl, vod, info) }
                }
            }
        }
    }
}

/* ======================= Series detail ======================= */

@Composable
fun TvSeriesScreen(seriesId: Int) {
    val ctx = LocalContext.current
    val session = rememberSession()
    val pl = session.playlist
    val series = MkApp.graph.catalog.current?.seriesById?.get(seriesId)
    val info by produceState<SeriesInfo?>(null, seriesId) { value = MkApp.graph.catalog.seriesInfo(seriesId) }
    val progressMap = session.data.progress[session.profileId].orEmpty()
    val items = remember(info, pl) { if (info != null && pl != null) Playback.episodeItems(pl, series, seriesId, info!!) else emptyList() }
    var season by remember(info) { mutableIntStateOf(info?.seasons?.firstOrNull() ?: 1) }
    val last = remember(progressMap) { progressMap.values.filter { it.seriesId == seriesId }.maxByOrNull { it.updatedAt } }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(info) { if (info != null) { delay(200); runCatching { playFocus.requestFocus() } } }
    val title = series?.name ?: info?.name ?: ""

    Box(Modifier.fillMaxSize().background(Mk.Bg)) {
        Box(Modifier.align(Alignment.TopEnd).height(330.dp).aspectRatio(16f / 9f)) {
            Poster(info?.backdrop ?: series?.backdrop ?: series?.cover, title, Modifier.fillMaxSize(), corner = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Mk.Bg, .5f to Mk.Bg.copy(alpha = .4f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Mk.Bg)))
        }
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Column(Modifier.padding(horizontal = 48.dp).widthIn(max = 560.dp)) {
                Text(title, style = TitleStyle, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaRow(year(info?.releaseDate ?: series?.releaseDate), info?.seasons?.size?.let { "$it Seasons" }, (info?.genre ?: series?.genre)?.split(",")?.firstOrNull(), ratingText(info?.rating ?: series?.rating)?.let { "★ $it" })
                Spacer(Modifier.height(10.dp))
                Text(info?.plot ?: series?.plot ?: "", color = Color(0xD9F0F2FF), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val idx = last?.let { l -> items.indexOfFirst { it.key == l.key } } ?: -1
                    val start = if (idx >= 0 && last?.finished == true) (idx + 1).coerceAtMost(items.lastIndex) else idx.coerceAtLeast(0)
                    PrimaryButton(
                        if (idx >= 0) (if (last?.finished == true) "Play " else "Resume ") + (items.getOrNull(start)?.subtitle?.substringBefore(" ·") ?: "") else "Play",
                        { if (items.isNotEmpty()) Playback.launch(ctx, PlayQueue(items, start)) },
                        icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus), enabled = items.isNotEmpty(),
                    )
                    val fav = session.isFav("series:$seriesId")
                    com.mk.iptv.ui.GlassButton(if (fav) "In My List" else "My List", { MkApp.graph.store.toggleFavorite(session.profileId, "series:$seriesId") }, icon = if (fav) Icons.Rounded.Check else Icons.Rounded.Add)
                    val eps = info?.episodes?.get(season).orEmpty()
                    if (pl != null && eps.isNotEmpty()) com.mk.iptv.ui.GlassButton("Download season", { Playback.downloadEpisodes(ctx, pl, seriesId, title, series?.cover ?: info?.cover, eps) }, icon = Icons.Rounded.Download)
                }
            }
            Spacer(Modifier.height(18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(info?.seasons.orEmpty()) { s -> GlassChip("Season $s", s == season, { season = s }) }
            }
            val eps = info?.episodes?.get(season).orEmpty()
            val watched = eps.count { progressMap["ep:${it.id}"]?.finished == true }
            Row(Modifier.padding(start = 48.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$watched of ${eps.size} watched", color = Mk.Sub, fontSize = 13.sp)
                Spacer(Modifier.width(16.dp))
                Text(
                    if (watched == eps.size && eps.isNotEmpty()) "Mark season unwatched" else "Mark season watched", color = Mk.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.tap(RoundedCornerShape(8.dp)) {
                        val keys = eps.map { "ep:${it.id}" }
                        MkApp.graph.store.setWatched(session.profileId, items.filter { it.key in keys }.map { progressFor(it, progressMap[it.key]) }, watched != eps.size)
                    }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(eps, key = { it.id }) { ep ->
                    val key = "ep:${ep.id}"
                    val pr = progressMap[key]
                    Column(Modifier.width(250.dp)) {
                        TvCard(
                            ep.image ?: series?.cover, title, 250.dp, 141.dp,
                            badge = if (pr?.finished == true) ({ WatchedBadge() }) else null,
                            progress = pr?.takeIf { !it.finished }?.fraction,
                        ) { Playback.launch(ctx, PlayQueue(items, items.indexOfFirst { it.key == key }.coerceAtLeast(0))) }
                        Spacer(Modifier.height(10.dp))
                        Text("${ep.num}. ${ep.title.ifBlank { "Episode ${ep.num}" }}", color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(ep.duration, if (pr?.finished == true) "Watched" else pr?.takeIf { it.position > 10_000 }?.let { "${((it.duration - it.position) / 60_000).coerceAtLeast(1)}m left" }).joinToString(" · "), color = if (pr?.finished == true) Mk.Green else Mk.Sub, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/* ======================= Live TV ======================= */

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun TvLive(catalog: Catalog, session: Session) {
    val ctx = LocalContext.current
    val pl = session.playlist ?: return
    var cat by rememberSaveable { mutableStateOf(session.data.settings.lastLiveCategory) }
    LaunchedEffect(cat) { if (session.data.settings.lastLiveCategory != cat) MkApp.graph.store.updateSettings { it.copy(lastLiveCategory = cat) } }
    val cats = remember(catalog, session.hideAdult) { catalog.liveCategories.filter { !session.hideAdult || !CatalogRepository.isAdult(it.name) } }
    val favs = session.favorites()
    val channels = remember(catalog, cat, favs) {
        val allowed = cats.map { it.id }.toSet()
        when (cat) {
            "all" -> catalog.live.filter { it.categoryId == null || it.categoryId in allowed }
            "fav" -> favs.mapNotNull { k -> if (k.startsWith("live:")) k.removePrefix("live:").toIntOrNull()?.let { catalog.liveById[it] } else null }
            else -> catalog.live.filter { it.categoryId == cat }
        }
    }
    var focusedCh by remember { mutableStateOf<LiveChannel?>(null) }
    var playing by remember { mutableStateOf<LiveChannel?>(null) }
    LaunchedEffect(focusedCh) { delay(700); focusedCh?.let { playing = it } }   // preview follows focus (debounced)
    val player = remember { com.mk.iptv.player.Players.build(ctx, MkApp.graph.downloads.upstream, preview = true) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(playing?.id) {
        val ch = playing ?: return@LaunchedEffect
        val url = XtreamApi.liveUrl(MkApp.graph.catalog.creds(pl), ch.id, liveFormats(session).first())
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
    }
    val counts = remember(catalog) { catalog.live.groupingBy { it.categoryId }.eachCount() }
    Row(Modifier.fillMaxSize().padding(24.dp)) {
        // Category column (Mac Finder-style): focus/OK selects, channels open to the right.
        LazyColumn(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { TvCategoryRow("All channels", catalog.live.size, cat == "all") { cat = "all" } }
            item { TvCategoryRow("♥ Favorites", favs.count { it.startsWith("live:") }, cat == "fav") { cat = "fav" } }
            items(cats, key = { it.id }) { c -> TvCategoryRow(c.name, counts[c.id] ?: 0, cat == c.id) { cat = c.id } }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (channels.isEmpty()) item { CenterMessage(if (cat == "fav") "No favorite channels yet" else "No channels", if (cat == "fav") "Hold OK on any channel to add it to Favorites." else null) }
                items(channels, key = { it.id }) { ch -> TvChannelRow(ch, ch.id == playing?.id, session.isFav("live:${ch.id}"), onFocus = { focusedCh = ch }, onFav = {
                    val was = session.isFav("live:${ch.id}")
                    MkApp.graph.store.toggleFavorite(session.profileId, "live:${ch.id}")
                    com.mk.iptv.ui.toast(ctx, if (was) "Removed ${ch.name} from Favorites" else "Added ${ch.name} to Favorites")
                }) {
                    player.pause()
                    Playback.launch(ctx, PlayQueue(Playback.liveItems(pl, channels), channels.indexOf(ch)))
                } }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1.1f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(Color.Black).border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(22.dp))) {
                AndroidView({ c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; layoutParams = ViewGroup.LayoutParams(-1, -1); this.player = player } }, Modifier.fillMaxSize())
                if (playing == null) CenterMessage("Choose a channel", "Move through the list to preview. Press OK for full screen.", icon = Icons.Rounded.LiveTv)
                Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(Mk.Live).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            playing?.let { ch ->
                val epg by produceState<List<EpgEntry>?>(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
                Column(Modifier.padding(top = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(18.dp)) {
                    Text("${if (ch.num > 0) "${ch.num} · " else ""}${ch.name}", color = Mk.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    val now = epg?.firstOrNull()
                    Text(now?.title ?: "No guide information", color = Mk.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    now?.let { Text("${hm.format(Date(it.start))} – ${hm.format(Date(it.end))}", color = Mk.Sub, fontSize = 12.sp); Spacer(Modifier.height(6.dp)); ProgressLine(epgFraction(it)) }
                    epg?.getOrNull(1)?.let { Text("Next · ${hm.format(Date(it.start))}  ${it.title}", color = Mk.Faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun TvCategoryRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else if (selected) Mk.Accent.copy(alpha = .22f) else Color.Transparent)
            .tap(RoundedCornerShape(14.dp), onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (focused) Color(0xFF0A0B10) else Color.White
        Text(name, color = c, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = c.copy(alpha = .6f), fontSize = 12.sp)
    }
}

@Composable
private fun TvChannelRow(ch: LiveChannel, playing: Boolean, fav: Boolean, onFocus: () -> Unit, onFav: () -> Unit = {}, onClick: () -> Unit) {
    val epg by produceState(MkApp.graph.catalog.cachedEpg(ch.id), ch.id) { value = MkApp.graph.catalog.epg(ch.id) }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(18.dp))
            .background(if (focused) Color.White.copy(alpha = .16f) else if (playing) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .03f))
            .tapOrHold(RoundedCornerShape(18.dp), onLongClick = onFav, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(titleBrush(ch.name)), contentAlignment = Alignment.Center) {
            Text(ch.name.split(" ").filter { it.isNotBlank() }.take(3).joinToString("") { it.take(1) }.uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
            if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color(0xFF1A1D28)).padding(5.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${if (ch.num > 0) "${ch.num} · " else ""}${ch.name}", color = Mk.Sub, fontSize = 12.sp, maxLines = 1)
            Text(epg?.firstOrNull()?.title ?: ch.name, color = Mk.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            epg?.firstOrNull()?.let { Spacer(Modifier.height(4.dp)); ProgressLine(epgFraction(it), height = 3.dp) }
        }
        if (fav) Icon(Icons.Rounded.Favorite, null, tint = Mk.Pink, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

/* ======================= Favorites ======================= */

@Composable
fun TvFavorites(catalog: Catalog, session: Session) {
    val actions = LocalActions.current
    val ctx = LocalContext.current
    val favs = session.favorites()
    val movies = favs.mapNotNull { k -> if (k.startsWith("vod:")) k.removePrefix("vod:").toIntOrNull()?.let { catalog.vodById[it] } else null }
    val series = favs.mapNotNull { k -> if (k.startsWith("series:")) k.removePrefix("series:").toIntOrNull()?.let { catalog.seriesById[it] } else null }
    val channels = favs.mapNotNull { k -> if (k.startsWith("live:")) k.removePrefix("live:").toIntOrNull()?.let { catalog.liveById[it] } else null }
    if (movies.isEmpty() && series.isEmpty() && channels.isEmpty()) return AmbientBackground { CenterMessage("No favorites yet", "Add movies and series to My List, or favorite a live channel.", icon = Icons.Rounded.Favorite) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        item { Text("Favorites", style = TitleStyle, fontSize = 30.sp, modifier = Modifier.padding(start = 32.dp)) }
        if (movies.isNotEmpty()) item {
            TvRowTitle("Movies")
            LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(movies, key = { it.id }) { v -> TvCard(v.poster, v.name, 132.dp, 198.dp) { actions.movie(v.id) } }
            }
        }
        if (series.isNotEmpty()) item {
            TvRowTitle("Series")
            LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(series, key = { it.id }) { s -> TvCard(s.cover, s.name, 132.dp, 198.dp) { actions.series(s.id) } }
            }
        }
        if (channels.isNotEmpty()) item {
            TvRowTitle("Live channels")
            LazyRow(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(channels, key = { it.id }) { ch ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.width(170.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(18.dp))
                            .background(if (focused) Color.White.copy(alpha = .16f) else Color.White.copy(alpha = .05f))
                            .tap(RoundedCornerShape(18.dp)) { session.playlist?.let { pl -> Playback.launch(ctx, PlayQueue(Playback.liveItems(pl, channels), channels.indexOf(ch))) } }
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(titleBrush(ch.name)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LiveTv, null, tint = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        Text(ch.name, color = Mk.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
