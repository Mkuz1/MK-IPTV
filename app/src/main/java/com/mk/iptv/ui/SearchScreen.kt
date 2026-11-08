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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mk.iptv.MkApp
import com.mk.iptv.data.CatalogRepository
import com.mk.iptv.data.CatalogState
import com.mk.iptv.data.PlayQueue
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val session = rememberSession()
    val state by MkApp.graph.catalog.state.collectAsState()
    val catalog = (state as? CatalogState.Ready)?.catalog ?: (state as? CatalogState.Error)?.stale
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { delay(250); debounced = query.trim() }

    val adultIds = remember(catalog, session.hideAdult) {
        if (catalog == null || !session.hideAdult) emptySet()
        else (catalog.vodCategories + catalog.seriesCategories + catalog.liveCategories).filter { CatalogRepository.isAdult(it.name) }.map { it.id }.toSet()
    }
    val results = remember(debounced, catalog, adultIds) {
        if (catalog == null || debounced.length < 2) null
        else Triple(
            catalog.vod.filter { it.name.contains(debounced, true) && it.categoryId !in adultIds }.take(60),
            catalog.series.filter { it.name.contains(debounced, true) && it.categoryId !in adultIds }.take(60),
            catalog.live.filter { it.name.contains(debounced, true) && it.categoryId !in adultIds }.take(60),
        )
    }

    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { actions.back() }, blur = false)
                Spacer(Modifier.width(10.dp))
                GlassField(null, query, { query = it }, icon = Icons.Rounded.Search, placeholder = "Movies, series, channels…", modifier = Modifier.weight(1f).focusRequester(focus))
            }
            when {
                catalog == null -> CenterMessage("Playlist not loaded", "Open a tab first so your playlist loads.")
                results == null -> CenterMessage("Search everything", "Type at least 2 letters to search ${catalog.vod.size + catalog.series.size + catalog.live.size} titles.", icon = Icons.Rounded.Search)
                results.first.isEmpty() && results.second.isEmpty() && results.third.isEmpty() -> CenterMessage("No results", "Nothing matches “$debounced”.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    if (results.first.isNotEmpty()) item {
                        SectionTitle("Movies · ${results.first.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(results.first, key = { it.id }) { v -> Poster(v.poster, v.name, Modifier.size(108.dp, 160.dp).tap { preview = PreviewTarget.Movie(v) }) }
                        }
                    }
                    if (results.second.isNotEmpty()) item {
                        SectionTitle("Series · ${results.second.size}")
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(results.second, key = { it.id }) { s -> Poster(s.cover, s.name, Modifier.size(108.dp, 160.dp).tap { preview = PreviewTarget.Series(s) }) }
                        }
                    }
                    if (results.third.isNotEmpty()) {
                        item { SectionTitle("Live channels · ${results.third.size}") }
                        items(results.third, key = { "l${it.id}" }) { ch ->
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .05f))
                                    .tap { session.playlist?.let { pl -> Playback.launch(ctx, PlayQueue(Playback.liveItems(pl, results.third), results.third.indexOf(ch))) } }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(titleBrush(ch.name)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.LiveTv, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color(0xFF1A1D28)).padding(5.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(ch.name, color = Mk.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
    preview?.let { if (actions.openDetail(it)) preview = null else PreviewSheet(it, session) { preview = null } }
}
