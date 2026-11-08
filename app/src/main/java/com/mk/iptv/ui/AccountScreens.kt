package com.mk.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mk.iptv.MkApp
import com.mk.iptv.data.Crypto
import com.mk.iptv.data.Creds
import com.mk.iptv.data.LocalStore
import com.mk.iptv.data.Playlist
import com.mk.iptv.data.Profile
import com.mk.iptv.data.XtreamApi
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- Welcome ---------------- */

@Composable
fun WelcomeScreen() {
    val actions = LocalActions.current
    AmbientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(110.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Rounded.LiveTv, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(26.dp))
            Text("MK  IPTV", color = Color.White.copy(alpha = .8f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp)
            Spacer(Modifier.height(10.dp))
            Text("Live TV, movies & series.\nAll in one place.", style = TitleStyle, fontSize = 30.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Sign in with your Xtream Codes playlist. Add as many playlists and profiles as you like.", color = Mk.Sub, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
            Spacer(Modifier.height(30.dp))
            PrimaryButton("Add Xtream playlist", { actions.playlistEditor(null) }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth(), height = 54.dp)
        }
    }
}

/* ---------------- Profiles (Who's watching?) ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen() {
    val actions = LocalActions.current
    val session = rememberSession()
    var manage by remember { mutableStateOf(false) }
    AmbientBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("MK  IPTV", color = Color.White.copy(alpha = .8f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp)
            Spacer(Modifier.height(20.dp))
            Text(if (manage) "Manage profiles" else "Who’s watching?", style = TitleStyle, fontSize = 32.sp)
            Spacer(Modifier.height(6.dp))
            Text("Each profile keeps its own favorites & history", color = Mk.Sub, fontSize = 14.sp)
            Spacer(Modifier.height(30.dp))
            FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                session.data.profiles.forEach { p ->
                    val plName = p.activePlaylistId?.let { id -> session.data.playlists.firstOrNull { it.id == id }?.name }
                        ?: p.playlistIds.firstNotNullOfOrNull { id -> session.data.playlists.firstOrNull { it.id == id }?.name }
                    ProfileTile(p, plName, current = p.id == session.profile?.id, manage = manage) {
                        if (manage) actions.profileEditor(p.id)
                        else { MkApp.graph.store.selectProfile(p.id); actions.home() }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("Add profile", { actions.profileEditor(null) }, icon = Icons.Rounded.Add, modifier = Modifier.weight(1f))
                GlassButton(if (manage) "Done" else "Manage", { manage = !manage }, icon = if (manage) Icons.Rounded.Check else Icons.Rounded.Edit, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ProfileTile(p: Profile, playlistName: String?, current: Boolean, manage: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .height(196.dp)
            .then(if (current) Modifier.border(3.dp, Mk.Accent.copy(alpha = .5f), RoundedCornerShape(30.dp)) else Modifier)
            .glass(RoundedCornerShape(30.dp))
            .tap(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Avatar(p.name, p.colorIndex, size = 84.dp, corner = 28.dp, fontSize = 30)
            if (manage) Box(Modifier.align(Alignment.Center).size(84.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Edit, null, tint = Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = Mk.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.kids) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.ChildCare, null, tint = Mk.Gold, modifier = Modifier.size(16.dp)) }
        }
        playlistName?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, start = 8.dp, end = 8.dp)) {
                Icon(Icons.Rounded.Dns, null, tint = Mk.Sub, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(it, color = Mk.Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ---------------- Profile editor ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(profileId: String?) {
    val actions = LocalActions.current
    val session = rememberSession()
    val existing = session.data.profiles.firstOrNull { it.id == profileId }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.colorIndex ?: session.data.profiles.size) }
    var kids by remember { mutableStateOf(existing?.kids ?: false) }
    var lists by remember { mutableStateOf(existing?.playlistIds?.toSet() ?: session.data.playlists.map { it.id }.toSet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    EditorScaffold(if (existing == null) "Add Profile" else "Edit Profile") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(name.ifBlank { "?" }, color, size = 96.dp, corner = 32.dp, fontSize = 34) }
        Spacer(Modifier.height(18.dp))
        GlassField("Name", name, { name = it }, icon = Icons.Rounded.Person, placeholder = "e.g. Sara")
        Spacer(Modifier.height(16.dp))
        FieldLabel("Color")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mk.avatarColors.indices.forEach { i ->
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(Mk.avatar(i)))
                        .border(if (i == color) 3.dp else 1.dp, if (i == color) Color.White else Color.White.copy(alpha = .3f), CircleShape)
                        .tap { color = i }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ToggleCard(listOf(Triple("Kids profile (hides adult categories)", kids) { v: Boolean -> kids = v }))
        if (session.data.playlists.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FieldLabel("Playlists this profile can use")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.data.playlists.forEach { pl ->
                    val on = pl.id in lists
                    GlassChip(pl.name, selected = on, onClick = { lists = if (on) lists - pl.id else lists + pl.id }, leading = if (on) Icons.Rounded.Check else null)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        PrimaryButton("Save profile", {
            val p = Profile(
                id = existing?.id ?: LocalStore.newId(), name = name.trim(), colorIndex = color, kids = kids,
                playlistIds = session.data.playlists.map { it.id }.filter { it in lists },
                activePlaylistId = existing?.activePlaylistId,
            )
            MkApp.graph.store.upsertProfile(p)
            if (existing == null) { MkApp.graph.store.selectProfile(p.id) }
            actions.back()
        }, enabled = name.isNotBlank(), icon = Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), height = 52.dp)
        if (existing != null && session.data.profiles.size > 1) {
            Spacer(Modifier.height(12.dp))
            GlassButton("Delete profile", { confirmDelete = true }, icon = Icons.Rounded.Delete, tint = Mk.Live, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = Mk.SheetBg,
            title = { Text("Delete ${existing.name}?", color = Mk.Text) },
            text = { Text("Favorites and watch history of this profile will be removed.", color = Mk.Sub) },
            confirmButton = { TextButton({ MkApp.graph.store.deleteProfile(existing.id); confirmDelete = false; actions.back() }) { Text("Delete", color = Mk.Live) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = Mk.Text) } },
        )
    }
}

/* ---------------- Playlists tab ---------------- */

@Composable
fun PlaylistsScreen(session: Session) {
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }
    AmbientBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Playlists", style = TitleStyle, fontSize = 30.sp)
                        Text("Xtream Codes accounts on this device", color = Mk.Sub, fontSize = 13.sp)
                    }
                    GlassIconButton(Icons.Rounded.Add, { actions.playlistEditor(null) }, tint = Mk.Accent, blur = false, contentDescription = "Add playlist")
                }
            }
            if (session.data.playlists.isEmpty()) item {
                CenterMessage("No playlists yet", "Add your Xtream Codes server URL, username and password.", icon = Icons.AutoMirrored.Rounded.PlaylistPlay, action = "Add playlist", onAction = { actions.playlistEditor(null) }, modifier = Modifier.height(420.dp))
            }
            items(session.data.playlists, key = { it.id }) { pl ->
                PlaylistCard(
                    pl, inUse = pl.id == session.playlist?.id,
                    onUse = { session.profile?.let { pr ->
                        if (pl.id !in pr.playlistIds) MkApp.graph.store.upsertProfile(pr.copy(playlistIds = pr.playlistIds + pl.id))
                        MkApp.graph.store.setActivePlaylist(pr.id, pl.id)
                    } },
                    onRefresh = { scope.launch { MkApp.graph.catalog.load(pl.id, force = true); session.playlist?.id?.let { if (it != pl.id) MkApp.graph.catalog.load(it) } } },
                    onEdit = { actions.playlistEditor(pl.id) },
                    onDelete = { confirmDelete = pl },
                )
            }
            item {
                SectionTitle("Profiles", action = "Manage", onAction = { actions.profiles() })
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(session.data.profiles, key = { it.id }) { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tap { actions.profileEditor(p.id) }) {
                            Avatar(p.name, p.colorIndex, size = 58.dp, corner = 20.dp, fontSize = 20)
                            Spacer(Modifier.height(6.dp))
                            Text(p.name, color = if (p.id == session.profile?.id) Mk.Accent else Mk.Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tap { actions.profileEditor(null) }) {
                            Box(Modifier.size(58.dp).glass(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White) }
                            Spacer(Modifier.height(6.dp))
                            Text("Add", color = Mk.Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Text(
                    "MK IPTV ${com.mk.iptv.BuildConfig.VERSION_NAME} · Passwords are encrypted on this device.\nCast to Chromecast & Google TV, or AirPlay to Apple TV from the player.",
                    color = Mk.Faint, fontSize = 11.5.sp, textAlign = TextAlign.Center, lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
    confirmDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = Mk.SheetBg,
            title = { Text("Remove ${pl.name}?", color = Mk.Text) },
            text = { Text("The playlist will be removed from this device. Downloads stay until you delete them.", color = Mk.Sub) },
            confirmButton = { TextButton({ MkApp.graph.store.deletePlaylist(pl.id); MkApp.graph.catalog.clear(pl.id); confirmDelete = null }) { Text("Remove", color = Mk.Live) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel", color = Mk.Text) } },
        )
    }
}

private val nf = NumberFormat.getIntegerInstance()
private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@Composable
private fun PlaylistCard(pl: Playlist, inUse: Boolean, onUse: () -> Unit, onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).fillMaxWidth().tap(onUse), highlighted = inUse) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(Mk.avatar(pl.name.length))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Dns, null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pl.name, color = Mk.Text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (inUse) {
                        Spacer(Modifier.width(8.dp))
                        Text("IN USE", color = Color(0xFF06070C), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Mk.Accent).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text("${pl.server.removePrefix("http://").removePrefix("https://")} · ${pl.username}", color = Mk.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                GlassIconButton(Icons.Rounded.MoreHoriz, { menu = true }, size = 36.dp, iconSize = 18.dp, blur = false)
                DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Mk.SheetBg)) {
                    if (!inUse) DropdownMenuItem({ Text("Use this playlist", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Check, null, tint = Mk.Text) }, onClick = { menu = false; onUse() })
                    DropdownMenuItem({ Text("Refresh now", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = Mk.Text) }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem({ Text("Edit", color = Mk.Text) }, leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = Mk.Text) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem({ Text("Remove", color = Mk.Live) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Mk.Live) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(Icons.Rounded.LiveTv, "Live", pl.liveCount, Modifier.weight(1f))
            Stat(Icons.Rounded.Movie, "Movies", pl.movieCount, Modifier.weight(1f))
            Stat(Icons.Rounded.VideoLibrary, "Series", pl.seriesCount, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val active = pl.status.equals("Active", true)
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) Mk.Green else if (pl.status == null) Mk.Faint else Mk.Live))
            Spacer(Modifier.width(6.dp))
            val parts = listOfNotNull(
                pl.status ?: "Not synced",
                pl.expiry?.let { "exp ${dateFmt.format(Date(it))}" } ?: if (pl.status != null) "no expiry" else null,
                if (pl.maxCons != null) "${pl.activeCons ?: 0} / ${pl.maxCons} conn." else null,
            )
            Text(parts.joinToString(" · "), color = Mk.Sub, fontSize = 11.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.Refresh, null, tint = Mk.Sub, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(if (pl.lastSync > 0) ago(pl.lastSync) else "never", color = Mk.Sub, fontSize = 11.5.sp)
        }
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: Int, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .06f)).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mk.Sub, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Mk.Sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(nf.format(value), color = Mk.Text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

/* ---------------- Playlist editor (Add Xtream) ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistEditorScreen(playlistId: String?) {
    val actions = LocalActions.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = rememberSession()
    val existing = session.data.playlists.firstOrNull { it.id == playlistId }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var server by remember { mutableStateOf(existing?.server ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.let { Crypto.decrypt(it.passwordEnc) } ?: "") }
    var showPass by remember { mutableStateOf(false) }
    var epg by remember { mutableStateOf(existing?.loadEpg ?: true) }
    var auto by remember { mutableStateOf(existing?.autoRefresh ?: true) }
    var adult by remember { mutableStateOf(existing?.hideAdult ?: true) }
    var profiles by remember {
        mutableStateOf(
            if (existing != null) session.data.profiles.filter { existing.id in it.playlistIds }.map { it.id }.toSet()
            else session.data.profiles.map { it.id }.toSet()
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    EditorScaffold(if (existing == null) "Add Playlist" else "Edit Playlist") {
        Row(Modifier.fillMaxWidth().height(46.dp).glass(RoundedCornerShape(50)).padding(4.dp)) {
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .93f)), contentAlignment = Alignment.Center) {
                Text("Xtream Codes", color = Color(0xFF0B0C12), fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        GlassField("Playlist name", name, { name = it }, placeholder = "e.g. Home Server")
        Spacer(Modifier.height(14.dp))
        GlassField("Server URL", server, { server = it }, icon = Icons.Rounded.Link, placeholder = "http://line.example.com:8080", keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(14.dp))
        GlassField("Username", user, { user = it }, icon = Icons.Rounded.Person, placeholder = "username")
        Spacer(Modifier.height(14.dp))
        GlassField(
            "Password", pass, { pass = it }, icon = Icons.Rounded.Lock, placeholder = "password", keyboard = KeyboardType.Password,
            visual = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = Mk.Sub, modifier = Modifier.size(20.dp).tap { showPass = !showPass }) },
        )
        Spacer(Modifier.height(16.dp))
        ToggleCard(
            listOf(
                Triple("Load TV guide (EPG)", epg) { v: Boolean -> epg = v },
                Triple("Auto-refresh every 24h", auto) { v: Boolean -> auto = v },
                Triple("Hide adult categories", adult) { v: Boolean -> adult = v },
            )
        )
        if (session.data.profiles.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FieldLabel("Available to profiles")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.data.profiles.forEach { p ->
                    val on = p.id in profiles
                    GlassChip(p.name, selected = on, onClick = { profiles = if (on) profiles - p.id else profiles + p.id }, leading = if (on) Icons.Rounded.Check else null)
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Mk.Live, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            "Connect & Sync", {
                error = null; busy = true
                scope.launch {
                    val srv = XtreamApi.normalizeServer(server)
                    val res = runCatching { MkApp.graph.api.authenticate(Creds(srv, user.trim(), pass)) }
                    busy = false
                    val auth = res.getOrNull()
                    if (auth == null) { error = res.exceptionOrNull()?.message ?: "Could not reach the server"; return@launch }
                    if (!auth.ok) { error = auth.message ?: "Login failed"; return@launch }
                    val store = MkApp.graph.store
                    val pl = (existing ?: Playlist(id = LocalStore.newId(), name = "", server = "", username = "", passwordEnc = "")).copy(
                        name = name.trim().ifBlank { srv.substringAfter("://").substringBefore(":").substringBefore("/") },
                        server = srv, username = user.trim(), passwordEnc = Crypto.encrypt(pass),
                        loadEpg = epg, autoRefresh = auto, hideAdult = adult,
                        status = auth.status, expiry = auth.expiry, activeCons = auth.activeCons, maxCons = auth.maxCons,
                    )
                    store.upsertPlaylist(pl)
                    if (store.data.value.profiles.isEmpty()) {
                        val me = Profile(id = LocalStore.newId(), name = "Me", colorIndex = 0, playlistIds = listOf(pl.id), activePlaylistId = pl.id)
                        store.upsertProfile(me); store.selectProfile(me.id)
                    } else {
                        store.data.value.profiles.forEach { p ->
                            val want = p.id in profiles
                            val has = pl.id in p.playlistIds
                            if (want && !has) store.upsertProfile(p.copy(playlistIds = p.playlistIds + pl.id, activePlaylistId = p.activePlaylistId ?: pl.id))
                            if (!want && has) store.upsertProfile(p.copy(playlistIds = p.playlistIds - pl.id))
                        }
                    }
                    MkApp.graph.catalog.clear(pl.id)
                    toast(ctx, "Connected to ${pl.name}")
                    if (existing == null && store.data.value.playlists.size == 1) actions.home() else actions.back()
                }
            },
            enabled = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(), loading = busy,
            icon = Icons.Rounded.Bolt, modifier = Modifier.fillMaxWidth(), height = 54.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text("Credentials are stored encrypted on this device only", color = Mk.Faint, fontSize = 11.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/* ---------------- editor building blocks ---------------- */

@Composable
fun EditorScaffold(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val actions = LocalActions.current
    AmbientBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0xCC10131D))
                .glass(RoundedCornerShape(36.dp), strength = .7f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.Rounded.Close, { actions.back() }, size = 38.dp, iconSize = 18.dp, blur = false)
                Text(title, color = Mk.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(38.dp))
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, color = Mk.Sub, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
}

@Composable
fun GlassField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        label?.let { FieldLabel(it) }
        Row(
            Modifier.fillMaxWidth().height(54.dp).glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, null, tint = Mk.Sub, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = Mk.Faint, fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Mk.Accent),
                    visualTransformation = visual,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
    }
}

@Composable
fun ToggleCard(items: List<Triple<String, Boolean, (Boolean) -> Unit>>) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
        items.forEachIndexed { i, (label, on, set) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Mk.Text, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Mk.Green, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = .18f), uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White))
            }
        }
    }
}
