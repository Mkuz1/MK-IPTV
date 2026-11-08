package com.mk.iptv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mk.iptv.MkApp
import com.mk.iptv.data.Catalog
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.CatalogState
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Progress
import com.mk.iptv.data.SeriesItem
import com.mk.iptv.data.VodItem
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.VideoLibrary),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay),
}

@Composable
fun HomeScreen() {
    val session = rememberSession()
    val hazeState = remember { HazeState() }
    var tab by rememberSaveable { mutableStateOf(Tab.MOVIES) }
    val holder = rememberSaveableStateHolder()
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    val catalogState by MkApp.graph.catalog.state.collectAsState()

    LaunchedEffect(session.playlist?.id) { session.playlist?.id?.let { MkApp.graph.catalog.load(it) } }

    Box(Modifier.fillMaxSize().background(Mk.Bg)) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.LIVE -> CatalogGate(catalogState, session) { LiveScreen(it, session) }
                    Tab.MOVIES -> CatalogGate(catalogState, session) { VodHome(true, it, session) { t -> preview = t } }
                    Tab.SERIES -> CatalogGate(catalogState, session) { VodHome(false, it, session) { t -> preview = t } }
                    Tab.DOWNLOADS -> DownloadsScreen(session)
                    Tab.PLAYLISTS -> PlaylistsScreen(session)
                }
            }
        }
        CompositionLocalProvider(LocalHaze provides hazeState) {
            TabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
    preview?.let { PreviewSheet(it, session) { preview = null } }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(68.dp)
            .liquidGlass(RoundedCornerShape(50))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            val color by animateColorAsState(if (on) Color(0xFF9FD3FF) else Color.White.copy(alpha = .75f), label = "tab")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(if (on) Modifier.glass(RoundedCornerShape(50), strength = 1.3f) else Modifier)
                    .tap { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, t.label, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(t.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun CatalogGate(state: CatalogState, session: Session, content: @Composable (Catalog) -> Unit) {
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    when {
        session.playlist == null -> AmbientBackground {
            CenterMessage(
                "No playlist yet", "Add your Xtream Codes playlist to start watching.",
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay, action = "Add playlist", onAction = { actions.playlistEditor(null) },
            )
        }
        state is CatalogState.Ready -> content(state.catalog)
        state is CatalogState.Error && state.stale != null -> content(state.stale)
        state is CatalogState.Error -> AmbientBackground {
            CenterMessage(
                "Couldn't load ${session.playlist.name}", state.message, icon = Icons.Rounded.CloudOff,
                action = "Try again", onAction = { scope.launch { MkApp.graph.catalog.load(session.playlist.id, force = true) } },
            )
        }
        else -> AmbientBackground {
            CenterMessage((state as? CatalogState.Loading)?.message ?: "Loading…", "Large playlists can take a few seconds the first time.", loading = true)
        }
    }
}

/* ---------------- Movies / Series home ---------------- */

private data class Shelf(val title: String, val catId: String?, val items: List<Any>)

@Composable
fun VodHome(movies: Boolean, catalog: Catalog, session: Session, onPreview: (PreviewTarget) -> Unit) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var genres by remember { mutableStateOf(false) }
    val type = if (movies) "movie" else "series"

    val cats = remember(catalog, session.hideAdult, movies) {
        (if (movies) catalog.vodCategories else catalog.seriesCategories).filter { !session.hideAdult || !CatalogRepository.isAdult(it.name) }
    }
    val shelves = remember(catalog, cats, session.favorites(), movies) {
        val allowed = cats.map { it.id }.toSet()
        if (movies) {
            val vod = catalog.vod.filter { it.categoryId in allowed || it.categoryId == null }
            val byCat = vod.groupBy { it.categoryId }
            buildList {
                val fav = session.favorites().mapNotNull { k -> k.removePrefix("vod:").takeIf { k.startsWith("vod:") }?.toIntOrNull()?.let { catalog.vodById[it] } }
                if (fav.isNotEmpty()) add(Shelf("My List", "mylist", fav))
                add(Shelf("Recently Added", "recent", vod.sortedByDescending { it.added }.take(30)))
                val top = vod.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(30)
                if (top.size >= 5) add(Shelf("Top Rated", "top", top))
                cats.forEach { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { add(Shelf(c.name, c.id, it.sortedByDescending { v -> v.added }.take(40))) } }
            }
        } else {
            val ser = catalog.series.filter { it.categoryId in allowed || it.categoryId == null }
            val byCat = ser.groupBy { it.categoryId }
            buildList {
                val fav = session.favorites().mapNotNull { k -> k.removePrefix("series:").takeIf { k.startsWith("series:") }?.toIntOrNull()?.let { catalog.seriesById[it] } }
                if (fav.isNotEmpty()) add(Shelf("My List", "mylist", fav))
                add(Shelf("New Episodes", "recent", ser.sortedByDescending { it.lastModified }.take(30)))
                val top = ser.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(10)
                if (top.size >= 5) add(Shelf("Top 10 Series", "top", top))
                cats.forEach { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { add(Shelf(c.name, c.id, it.sortedByDescending { s -> s.lastModified }.take(40))) } }
            }
        }
    }
    val hero: Any? = remember(shelves) {
        shelves.firstOrNull { it.catId == "recent" }?.items?.firstOrNull { (it as? VodItem)?.poster != null || (it as? SeriesItem)?.cover != null }
            ?: shelves.firstOrNull()?.items?.firstOrNull()
    }
    val continueItems = remember(session.data, session.profileId, movies) {
        session.data.progress[session.profileId].orEmpty().values
            .filter { it.kind == (if (movies) "movie" else "episode") && !it.finished && it.position > 15_000 && it.playlistId == session.playlist?.id }
            .sortedByDescending { it.updatedAt }
            .let { list -> if (movies) list else list.distinctBy { it.seriesId } }
            .take(20)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "hero") {
                Hero(hero, movies, catalog, session, onPreview)
            }
            if (continueItems.isNotEmpty()) item(key = "continue") {
                Column {
                    SectionTitle("Continue Watching")
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continueItems, key = { it.key }) { p -> ContinueCard(p) { resume(ctx, p, session, scope) } }
                    }
                }
            }
            shelves.forEachIndexed { idx, shelf ->
                item(key = "shelf-$idx-${shelf.catId}") {
                    Column {
                        SectionTitle(shelf.title, action = "See all", onAction = { actions.category(type, shelf.catId ?: "", shelf.title) })
                        val ranked = shelf.catId == "top" && !movies
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(if (ranked) 4.dp else 10.dp)) {
                            items(shelf.items.size) { i ->
                                val it = shelf.items[i]
                                if (ranked) RankedPoster(i + 1, it, onPreview) else PosterTile(it, onPreview)
                            }
                        }
                    }
                }
            }
        }
        // Floating header + filter chips
        val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 380
        val headerAlpha by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, label = "hdr")
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent)))
                .background(Brush.verticalGradient(0f to Mk.Bg.copy(alpha = .96f * headerAlpha), .8f to Mk.Bg.copy(alpha = .9f * headerAlpha), 1f to Mk.Bg.copy(alpha = 0f)))
        ) {
            TabHeader(if (movies) "Movies" else "Series", session)
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("All", selected = true, onClick = { scope.launch { listState.animateScrollToItem(0) } })
                GlassChip("Genres", onClick = { genres = true }, trailing = Icons.Rounded.KeyboardArrowDown)
                GlassChip("My List", onClick = { actions.category(type, "mylist", "My List") })
                GlassChip(if (movies) "New" else "New Episodes", onClick = { actions.category(type, "recent", if (movies) "Recently Added" else "New Episodes") })
            }
        }
    }

    if (genres) {
        ModalBottomSheet(onDismissRequest = { genres = false }, containerColor = Mk.SheetBg) {
            Text("Genres", style = TitleStyle, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 20.dp))
            LazyColumn(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(cats, key = { it.id }) { c ->
                    Text(
                        c.name, color = Mk.Text, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().tap { genres = false; actions.category(type, c.id, c.name) }.padding(horizontal = 20.dp, vertical = 13.dp),
                    )
                }
            }
        }
    }
}

private fun resume(ctx: android.content.Context, p: Progress, session: Session, scope: kotlinx.coroutines.CoroutineScope) {
    val pl = session.data.playlists.firstOrNull { it.id == p.playlistId } ?: session.playlist ?: return
    val catalog = MkApp.graph.catalog.current
    if (p.kind == "movie") {
        val vod = p.itemId.toIntOrNull()?.let { catalog?.vodById?.get(it) } ?: VodItem(p.itemId.toIntOrNull() ?: return, p.title, p.image, null, null, 0, p.ext)
        Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(pl, vod, p.ext)), 0))
    } else {
        val sid = p.seriesId ?: return
        scope.launch {
            val info = MkApp.graph.catalog.seriesInfo(sid, pl.id) ?: return@launch toast(ctx, "Couldn't load episodes")
            val items = Playback.episodeItems(pl, catalog?.seriesById?.get(sid), sid, info)
            Playback.launch(ctx, PlayQueue(items, items.indexOfFirst { it.key == p.key }.coerceAtLeast(0)))
        }
    }
}

@Composable
private fun Hero(hero: Any?, movies: Boolean, catalog: Catalog, session: Session, onPreview: (PreviewTarget) -> Unit) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    Box(Modifier.fillMaxWidth().height(590.dp)) {
        if (hero == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241A4A), Mk.Bg))))
            return@Box
        }
        val title = (hero as? VodItem)?.name ?: (hero as SeriesItem).name
        val image = (hero as? VodItem)?.poster ?: (hero as? SeriesItem)?.let { it.backdrop ?: it.cover }
        val wide = isWide()
        Poster(image, title, Modifier.fillMaxSize(), corner = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .25f to Color.Transparent, .55f to Color.Transparent, .82f to Mk.Bg.copy(alpha = .85f), 1f to Mk.Bg)))
        if (wide) {
            // Unfolded / tablet: dim the cropped artwork and show the full poster beside the details.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Mk.Bg.copy(alpha = .2f), .55f to Mk.Bg.copy(alpha = .75f), 1f to Mk.Bg.copy(alpha = .9f))))
            Poster(
                (hero as? VodItem)?.poster ?: (hero as SeriesItem).cover, title,
                Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 24.dp).size(240.dp, 360.dp), corner = 22.dp,
            )
        }
        Column(
            if (wide) Modifier.align(Alignment.BottomStart).padding(start = 310.dp, end = 32.dp, bottom = 30.dp).widthIn(max = 520.dp)
            else Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 6.dp),
            horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Text(if (movies) "RECENTLY ADDED" else "NEW EPISODES", color = Color(0xFFD9C8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(8.dp))
            Text(title.uppercase(), style = TitleStyle, fontSize = if (title.length > 18) 34.sp else 46.sp, lineHeight = if (title.length > 18) 36.sp else 46.sp, textAlign = if (wide) TextAlign.Start else TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            val genre = if (hero is VodItem) catalog.vodCategoryName(hero.categoryId) else (hero as SeriesItem).genre ?: catalog.seriesCategoryName(hero.categoryId)
            val rating = ratingText((hero as? VodItem)?.rating ?: (hero as? SeriesItem)?.rating)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaRow(genre, if (hero is SeriesItem) year(hero.releaseDate) else null, color = Color.White.copy(alpha = .8f))
                rating?.let {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Star, null, tint = Mk.Gold, modifier = Modifier.size(14.dp))
                    Text(" $it", color = Mk.Gold, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            val favKey = if (hero is VodItem) "vod:${hero.id}" else "series:${(hero as SeriesItem).id}"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassIconButton(if (session.isFav(favKey)) Icons.Rounded.Check else Icons.Rounded.Add, { MkApp.graph.store.toggleFavorite(session.profileId, favKey) }, size = 48.dp, blur = false)
                PrimaryButton("Play", {
                    if (hero is VodItem) session.playlist?.let { Playback.launch(ctx, PlayQueue(listOf(Playback.movieItem(it, hero)), 0)) }
                    else actions.series((hero as SeriesItem).id)
                }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.width(170.dp))
                GlassIconButton(Icons.Rounded.Info, { onPreview(if (hero is VodItem) PreviewTarget.Movie(hero) else PreviewTarget.Series(hero as SeriesItem)) }, size = 48.dp, blur = false)
            }
        }
    }
}

@Composable
private fun PosterTile(item: Any, onPreview: (PreviewTarget) -> Unit) {
    when (item) {
        is VodItem -> Poster(item.poster, item.name, Modifier.size(114.dp, 170.dp).tap { onPreview(PreviewTarget.Movie(item)) })
        is SeriesItem -> Poster(item.cover, item.name, Modifier.size(114.dp, 170.dp).tap { onPreview(PreviewTarget.Series(item)) })
    }
}

@Composable
private fun RankedPoster(rank: Int, item: Any, onPreview: (PreviewTarget) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "$rank", fontSize = 104.sp, fontWeight = FontWeight.Black, lineHeight = 96.sp,
            color = Color.White.copy(alpha = .14f), modifier = Modifier.padding(end = 2.dp),
        )
        PosterTile(item, onPreview)
    }
}

@Composable
private fun ContinueCard(p: Progress, onClick: () -> Unit) {
    Column(Modifier.width(176.dp).tap(onClick)) {
        Box(Modifier.fillMaxWidth().height(99.dp)) {
            Poster(p.image, p.title, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp).size(36.dp).glass(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .2f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(p.fraction)
        Spacer(Modifier.height(6.dp))
        val left = ((p.duration - p.position) / 60_000).coerceAtLeast(1)
        Text(
            p.subtitle?.let { "${p.title} · ${it.substringBefore(" ·")}" } ?: "${p.title} · ${left}m left",
            color = Mk.Sub, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ---------------- Category grid ---------------- */

@Composable
fun CategoryScreen(type: String, catId: String, title: String) {
    val session = rememberSession()
    val actions = LocalActions.current
    val state by MkApp.graph.catalog.state.collectAsState()
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    val catalog = (state as? CatalogState.Ready)?.catalog ?: (state as? CatalogState.Error)?.stale
    val items: List<Any> = remember(catalog, catId, type, session.favorites()) {
        if (catalog == null) return@remember emptyList()
        if (type == "movie") when (catId) {
            "mylist" -> session.favorites().mapNotNull { k -> if (k.startsWith("vod:")) k.removePrefix("vod:").toIntOrNull()?.let { catalog.vodById[it] } else null }
            "recent" -> catalog.vod.sortedByDescending { it.added }.take(300)
            "top" -> catalog.vod.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(300)
            else -> catalog.vod.filter { it.categoryId == catId }.sortedByDescending { it.added }
        } else when (catId) {
            "mylist" -> session.favorites().mapNotNull { k -> if (k.startsWith("series:")) k.removePrefix("series:").toIntOrNull()?.let { catalog.seriesById[it] } else null }
            "recent" -> catalog.series.sortedByDescending { it.lastModified }.take(300)
            "top" -> catalog.series.filter { (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating }.take(300)
            else -> catalog.series.filter { it.categoryId == catId }.sortedByDescending { it.lastModified }
        }.let { list ->
            if (!session.hideAdult) list else {
                val adult = (if (type == "movie") catalog.vodCategories else catalog.seriesCategories).filter { CatalogRepository.isAdult(it.name) }.map { it.id }.toSet()
                list.filter { i -> ((i as? VodItem)?.categoryId ?: (i as? SeriesItem)?.categoryId) !in adult }
            }
        }
    }
    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { actions.back() }, blur = false)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = TitleStyle, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${items.size} titles", color = Mk.Sub, fontSize = 12.sp)
                }
            }
            if (items.isEmpty()) {
                CenterMessage(if (catId == "mylist") "Your list is empty" else "Nothing here yet", if (catId == "mylist") "Tap + on any title to add it to My List." else null)
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items.size) { i ->
                        val it = items[i]
                        val mod = Modifier.fillMaxWidth().height(162.dp)
                        when (it) {
                            is VodItem -> Poster(it.poster, it.name, mod.tap { preview = PreviewTarget.Movie(it) })
                            is SeriesItem -> Poster(it.cover, it.name, mod.tap { preview = PreviewTarget.Series(it) })
                        }
                    }
                }
            }
        }
    }
    preview?.let { PreviewSheet(it, session) { preview = null } }
}
