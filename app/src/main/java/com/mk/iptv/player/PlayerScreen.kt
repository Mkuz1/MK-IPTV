package com.mk.iptv.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mk.iptv.cast.CastRouteButton
import com.mk.iptv.ui.GlassIconButton
import com.mk.iptv.ui.Mk
import com.mk.iptv.ui.PrimaryButton
import com.mk.iptv.ui.glass
import com.mk.iptv.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal enum class Sheet { NONE, TRACKS, SPEED, AIRPLAY }

@Composable
fun PlayerScreen(a: PlayerActivity) {
    val item = a.current
    val player = a.player.value
    val casting = a.casting.value
    val airplay = a.airplay.value
    val remote = casting || airplay != null
    val live = item?.kind == "live"
    var visible by a.controlsVisible
    // Very short windows (top/bottom split-screen, small car screens): compact centre controls.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 380
    val sideBtn = if (compact) 48.dp else 64.dp
    val mainBtn = if (compact) 64.dp else 88.dp
    val tv = remember { Device.isTv(a) }
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val backFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val fwdFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusScope = rememberCoroutineScope()
    // TV: a seek re-renders the controls (buffering, new position), which can move D-pad focus to another
    // button. Keep it on the button that was pressed so repeated presses keep skipping.
    var lastFocus by remember { mutableStateOf(playFocus) }
    val keepFocus: (androidx.compose.ui.focus.FocusRequester) -> Unit = { r ->
        lastFocus = r
        if (tv) focusScope.launch { for (d in listOf(0L, 120L, 400L, 900L)) { delay(d); runCatching { r.requestFocus() } } }
    }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var interaction by a.interaction
    val playing = if (airplay != null) a.airplayPlaying.value else a.isPlaying.value

    // auto-hide controls
    val remoteScrub = a.scrubTarget.longValue >= 0
    LaunchedEffect(visible, interaction, playing, sheet, scrubbing, remoteScrub) {
        // TV: longer, so the controls don't vanish while you're moving between buttons with the remote.
        if (visible && playing && sheet == Sheet.NONE && !scrubbing && !remoteScrub && !remote) { delay(if (tv) 8000 else 4000); visible = false }
    }
    val bump = { interaction++ }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val hinge = a.tabletopHinge.value
        if (hinge != null && !remote && !a.inPip.value) {
            // Foldable half-open on a table: video above the fold, controls below it.
            TabletopContent(a, hinge, playing, live, onSheet = { sheet = it })
        } else {
        // Video surface
        if (!remote) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        // Never take D-pad focus from the controls (TV remotes).
                        isFocusable = false; isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { v -> v.player = a.exo; v.resizeMode = resize },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Casting / AirPlay backdrop
            Box(Modifier.fillMaxSize()) {
                item?.image?.let { AsyncImage(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Black.copy(alpha = .85f)))))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (airplay != null) Icons.Rounded.Airplay else Icons.Rounded.Cast, null, tint = Mk.Accent, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (airplay != null) "Playing on ${airplay.device.name}" else "Casting to ${a.castDeviceName.value ?: "TV"}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (airplay != null) "AirPlay" else "Google Cast", color = Mk.Sub, fontSize = 13.sp)
                }
            }
        }

        if (a.inPip.value) return@Box

        // TV: when the controls appear, put focus on Play/Pause so the remote works immediately.
        LaunchedEffect(visible, locked) { if (tv && visible && !locked) { kotlinx.coroutines.delay(80); runCatching { playFocus.requestFocus() } } }

        // TV remote seek feedback while the controls are hidden.
        if (!visible && a.seekOsd.longValue > 0) {
            val dur = a.duration.longValue
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 48.dp, vertical = 28.dp).fillMaxWidth()) {
                Text(fmt(a.position.longValue) + if (dur > 0) " / " + fmt(dur) else "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (dur > 0) com.mk.iptv.ui.ProgressLine((a.position.longValue.toFloat() / dur), height = 5.dp)
            }
        }

        // Tap layer
        // (pointer-only: a focusable full-screen layer could take the remote's focus from the buttons)
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { if (sheet != Sheet.NONE) sheet = Sheet.NONE else { visible = !visible; bump() } }
            }
        )

        if (a.buffering.value && !remote && a.error.value == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(54.dp), color = Color.White, strokeWidth = 3.dp)
        }

        a.error.value?.let { err ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(24.dp).glass(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .35f), RoundedCornerShape(28.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play this", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(err, color = Mk.Sub, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Try again", { a.retry() }, icon = Icons.Rounded.Refresh)
            }
        }

        if (locked) {
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(28.dp)) {
                GlassIconButton(Icons.Rounded.Lock, { locked = false; bump() }, size = 56.dp, iconSize = 26.dp, blur = false)
            }
            return@Box
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            // TV: if focus ever leaves the controls while they are showing (a seek re-renders the player),
            // put it back on the button that was used last instead of losing it.
            Box(Modifier.fillMaxSize().onFocusChanged { st ->
                if (tv && !st.hasFocus && visible && !locked && sheet == Sheet.NONE) {
                    focusScope.launch { delay(30); if (visible && sheet == Sheet.NONE) runCatching { lastFocus.requestFocus() } }
                }
            }) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .55f), .3f to Color.Transparent, .65f to Color.Transparent, 1f to Color.Black.copy(alpha = .65f))))
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
                    // ---- top bar ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { a.finish() }, size = 46.dp, blur = false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item?.title ?: "", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(item?.subtitle, if (a.offline.value) "Offline" else null, if (live) "LIVE" else null).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(
                            Modifier.height(50.dp).glass(RoundedCornerShape(50)).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BarIcon(Icons.Rounded.ClosedCaption) { sheet = Sheet.TRACKS; bump() }
                            if (!tv) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CastRouteButton(Modifier.size(44.dp)) }
                                BarIcon(Icons.Rounded.Airplay, tint = if (airplay != null) Mk.Accent else Color.White) { sheet = Sheet.AIRPLAY; bump() }
                                if (!remote) BarIcon(Icons.Rounded.PictureInPictureAlt) { a.enterPip() }
                            }
                            if (!tv) BarIcon(if (a.forceLandscape.value) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, tint = if (a.forceLandscape.value) Mk.Accent else Color.White) {
                                a.toggleLandscape(); bump()
                            }
                            if (!remote) BarIcon(Icons.Rounded.AspectRatio) {
                                resize = when (resize) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }; bump()
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // ---- center controls ----
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (live) {
                            GlassIconButton(Icons.Rounded.KeyboardArrowUp, { a.prev(); bump() }, size = sideBtn, iconSize = 30.dp, blur = false)
                        } else {
                            GlassIconButton(Icons.Rounded.Replay10, {
                                if (airplay != null) a.airPlaySeekBy(-10_000) else player?.seekBack(); bump(); keepFocus(backFocus)
                            }, modifier = Modifier.focusRequester(backFocus).onFocusChanged { if (it.isFocused) lastFocus = backFocus }, size = sideBtn, iconSize = 30.dp, blur = false, contentDescription = "Back 10 seconds")
                        }
                        Spacer(Modifier.width(48.dp))
                        GlassIconButton(modifier = Modifier.focusRequester(playFocus).onFocusChanged { if (it.isFocused) lastFocus = playFocus }, icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, onClick = {
                            if (airplay != null) a.airPlayToggle() else player?.let { if (it.isPlaying) it.pause() else it.play() }; bump()
                        }, size = mainBtn, iconSize = 42.dp, blur = false, contentDescription = if (playing) "Pause" else "Play")
                        Spacer(Modifier.width(48.dp))
                        if (live) {
                            GlassIconButton(Icons.Rounded.KeyboardArrowDown, { a.next(); bump() }, size = sideBtn, iconSize = 30.dp, blur = false)
                        } else {
                            GlassIconButton(Icons.Rounded.Forward10, {
                                if (airplay != null) a.airPlaySeekBy(10_000) else player?.seekForward(); bump(); keepFocus(fwdFocus)
                            }, modifier = Modifier.focusRequester(fwdFocus).onFocusChanged { if (it.isFocused) lastFocus = fwdFocus }, size = sideBtn, iconSize = 30.dp, blur = false, contentDescription = "Forward 10 seconds")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // ---- bottom bar ----
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .15f), RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        val dur = a.duration.longValue
                        if (!live && dur > 0) {
                            val pos = a.position.longValue
                            var barFocused by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fmt(if (scrubbing) (scrubValue * dur).toLong() else pos), color = Color.White, fontSize = if (barFocused) 16.sp else 13.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = if (scrubbing) scrubValue else (pos.toFloat() / dur).coerceIn(0f, 1f),
                                    onValueChange = { scrubbing = true; scrubValue = it; bump() },
                                    onValueChangeFinished = {
                                        val target = (scrubValue * dur).toLong()
                                        if (airplay != null) a.airPlaySeekTo(target) else player?.seekTo(target)
                                        scrubbing = false
                                    },
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Mk.Accent, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                                    thumb = {
                                        Box(
                                            Modifier.size(if (barFocused) 26.dp else 18.dp).clip(CircleShape).background(Color.White)
                                                .then(if (barFocused) Modifier.border(4.dp, Mk.Accent, CircleShape) else Modifier)
                                        )
                                    },
                                    track = { st ->
                                        SliderDefaults.Track(
                                            sliderState = st, modifier = Modifier.height(5.dp),
                                            colors = SliderDefaults.colors(activeTrackColor = Mk.Accent, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                                            drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                                        )
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                                        .onFocusChanged { barFocused = it.isFocused }
                                        // TV remote: left/right scrub (hold to go faster), OK jumps there right away.
                                        .onPreviewKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            val rep = e.nativeKeyEvent.repeatCount
                                            when (e.key) {
                                                Key.DirectionLeft -> { a.scrubBy(-a.scrubStep(rep)); bump(); true }
                                                Key.DirectionRight -> { a.scrubBy(a.scrubStep(rep)); bump(); true }
                                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { a.commitScrub(); bump(); true }
                                                else -> false
                                            }
                                        },
                                )
                                Text("-" + fmt((dur - a.position.longValue).coerceAtLeast(0)), color = Color.White.copy(alpha = .75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (live) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Mk.Live))
                                Spacer(Modifier.width(8.dp))
                                Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
                                Spacer(Modifier.width(10.dp))
                                Text("Channel ${a.index.intValue + 1} of ${a.queue.value.items.size}", color = Mk.Sub, fontSize = 12.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
                            if (!live && !remote) BottomAction(Icons.Rounded.Speed, "Speed (${trim(a.speed.floatValue)}x)") { sheet = Sheet.SPEED; bump() }
                            if (!remote) BottomAction(Icons.Rounded.LockOpen, "Lock") { locked = true; bump() }
                            BottomAction(Icons.Rounded.ClosedCaption, "Audio & Subtitles") { sheet = Sheet.TRACKS; bump() }
                            if (airplay != null) BottomAction(Icons.Rounded.Stop, "Stop AirPlay", Mk.Live) { a.stopAirPlay() }
                            Spacer(Modifier.weight(1f))
                            val nextItem = a.queue.value.items.getOrNull(a.index.intValue + 1)
                            if (nextItem != null && !live) {
                                BottomAction(Icons.Rounded.SkipNext, "Next: " + (nextItem.subtitle?.substringBefore(" ·") ?: nextItem.title)) { a.next(); bump() }
                            }
                        }
                    }
                }
            }
        }

        }

        // ---- sheets ----
        if (sheet != Sheet.NONE) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable(remember { MutableInteractionSource() }, null) { sheet = Sheet.NONE })
            Column(
                Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(16.dp).width(340.dp)
                    .glass(RoundedCornerShape(30.dp)).background(Color(0xD0101320), RoundedCornerShape(30.dp)).padding(18.dp)
            ) {
                when (sheet) {
                    Sheet.TRACKS -> TracksSheet(a) { sheet = Sheet.NONE }
                    Sheet.SPEED -> {
                        SheetTitle("Playback speed")
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                            OptionRow("${trim(s)}x" + if (s == 1f) " (Normal)" else "", a.speed.floatValue == s) { a.setSpeed(s); sheet = Sheet.NONE }
                        }
                    }
                    Sheet.AIRPLAY -> AirPlaySheet(a) { sheet = Sheet.NONE }
                    Sheet.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun TracksSheet(a: PlayerActivity, close: () -> Unit) {
    LazyColumn(Modifier.height(300.dp)) {
        item { SheetTitle("Audio") }
        if (a.audioTracks.value.isEmpty()) item { Text("Default", color = Mk.Sub, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        items(a.audioTracks.value) { t -> OptionRow(t.label, t.selected) { a.selectTrack(C.TRACK_TYPE_AUDIO, t); close() } }
        item { Spacer(Modifier.height(10.dp)); SheetTitle("Subtitles") }
        items(a.textTracks.value) { t -> OptionRow(t.label, t.selected) { a.selectTrack(C.TRACK_TYPE_TEXT, t); close() } }
    }
}

@Composable
private fun AirPlaySheet(a: PlayerActivity, close: () -> Unit) {
    val devices by a.airplayDevices.collectAsState()
    val searching by a.airplaySearching.collectAsState()
    DisposableEffect(Unit) {
        a.startAirPlayDiscovery()
        onDispose { a.stopAirPlayDiscovery() }
    }
    SheetTitle("AirPlay")
    Text("Apple TV & AirPlay TVs on your Wi-Fi", color = Mk.Sub, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    a.airplay.value?.let { s ->
        OptionRow("Stop playing on ${s.device.name}", false, icon = Icons.Rounded.Stop) { a.stopAirPlay(); close() }
    }
    OptionRow("This phone", a.airplay.value == null, icon = Icons.Rounded.PlayArrow) { a.stopAirPlay(); close() }
    devices.forEach { d ->
        OptionRow(d.name, a.airplay.value?.device == d, icon = Icons.Rounded.Tv) { a.airPlayTo(d); close() }
    }
    if (devices.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            if (searching) { CircularProgressIndicator(Modifier.size(16.dp), color = Mk.Accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (searching) "Looking for AirPlay devices…" else "No AirPlay devices found", color = Mk.Sub, fontSize = 13.sp)
        }
    }
    if (a.airplayBusy.value) Text("Connecting…", color = Mk.Accent, fontSize = 13.sp)
}

@Composable
private fun SheetTitle(t: String) = Text(t, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun OptionRow(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (selected) Modifier.background(Color.White.copy(alpha = .1f)) else Modifier)
            .tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = Mk.Sub, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(label, color = Color.White, fontSize = 14.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Rounded.Check, null, tint = Mk.Accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).tap(CircleShape, onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).tap(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = tint.copy(alpha = .9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

private fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}


@Composable
private fun TabletopContent(a: PlayerActivity, hinge: Pair<Int, Int>, playing: Boolean, live: Boolean, onSheet: (Sheet) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val player = a.player.value
    val item = a.current
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    with(density) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(hinge.first.toDp())) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { v -> v.player = a.exo; v.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize(),
                )
                if (a.buffering.value && a.error.value == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White, strokeWidth = 3.dp)
            }
            Spacer(Modifier.height((hinge.second - hinge.first).coerceAtLeast(0).toDp()))
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12141E), Mk.Bg))).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, { a.finish() }, size = 42.dp, blur = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item?.title ?: "", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item?.subtitle?.let { Text(it, color = Mk.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Box(Modifier.size(42.dp).glass(CircleShape), contentAlignment = Alignment.Center) { CastRouteButton(Modifier.size(42.dp)) }
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Rounded.Airplay, { onSheet(Sheet.AIRPLAY) }, size = 42.dp, blur = false)
                }
                a.error.value?.let { Text(it, color = Mk.Live, fontSize = 13.sp, maxLines = 2) }
                val dur = a.duration.longValue
                if (!live && dur > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(if (scrubbing) (scrubValue * dur).toLong() else a.position.longValue), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = if (scrubbing) scrubValue else (a.position.longValue.toFloat() / dur).coerceIn(0f, 1f),
                            onValueChange = { scrubbing = true; scrubValue = it },
                            onValueChangeFinished = { player?.seekTo((scrubValue * dur).toLong()); scrubbing = false },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Mk.Accent, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text("-" + fmt((dur - a.position.longValue).coerceAtLeast(0)), color = Mk.Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (live) GlassIconButton(Icons.Rounded.KeyboardArrowUp, { a.prev() }, size = 52.dp, blur = false)
                    else GlassIconButton(Icons.Rounded.Replay10, { player?.seekBack() }, size = 52.dp, iconSize = 26.dp, blur = false)
                    GlassIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, { player?.let { if (it.isPlaying) it.pause() else it.play() } }, size = 70.dp, iconSize = 34.dp, blur = false)
                    if (live) GlassIconButton(Icons.Rounded.KeyboardArrowDown, { a.next() }, size = 52.dp, blur = false)
                    else GlassIconButton(Icons.Rounded.Forward10, { player?.seekForward() }, size = 52.dp, iconSize = 26.dp, blur = false)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    BottomAction(Icons.Rounded.ClosedCaption, "Audio & Subtitles") { onSheet(Sheet.TRACKS) }
                    if (!live) BottomAction(Icons.Rounded.Speed, "Speed (${trim(a.speed.floatValue)}x)") { onSheet(Sheet.SPEED) }
                    val nextItem = a.queue.value.items.getOrNull(a.index.intValue + 1)
                    if (nextItem != null && !live) BottomAction(Icons.Rounded.SkipNext, "Next") { a.next() }
                }
            }
        }
    }
}
