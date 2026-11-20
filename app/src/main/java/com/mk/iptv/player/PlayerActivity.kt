package com.mk.iptv.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mk.iptv.MkApp
import com.mk.iptv.airplay.AirPlayDevice
import com.mk.iptv.airplay.AirPlayDiscovery
import com.mk.iptv.airplay.AirPlaySession
import com.mk.iptv.cast.Cast
import com.mk.iptv.data.PlayItem
import com.mk.iptv.data.PlayQueue
import com.mk.iptv.data.Progress
import com.mk.iptv.ui.MkTheme
import com.mk.iptv.ui.sessionOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

class PlayerActivity : AppCompatActivity() {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun intent(ctx: Context, queue: PlayQueue): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra("queueToken", NowPlaying.put(ctx, queue))   // never the queue itself: see NowPlaying
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { ctx !is android.app.Activity } ?: 0)
    }

    private val graph get() = MkApp.graph
    lateinit var exo: ExoPlayer
        private set
    private var castPlayer: CastPlayer? = null

    // ----- UI state (read by PlayerScreen) -----
    val queue = mutableStateOf(PlayQueue(emptyList(), 0))
    val index = mutableIntStateOf(0)
    val player = mutableStateOf<Player?>(null)
    val casting = mutableStateOf(false)
    val castDeviceName = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)
    val buffering = mutableStateOf(true)
    val position = mutableLongStateOf(0L)
    val duration = mutableLongStateOf(0L)
    val bufferedPos = mutableLongStateOf(0L)
    val error = mutableStateOf<String?>(null)
    val speed = mutableFloatStateOf(1f)
    val inPip = mutableStateOf(false)
    val offline = mutableStateOf(false)
    val audioTracks = mutableStateOf<List<TrackOption>>(emptyList())
    /** Controls overlay visibility + user-interaction counter (drives auto-hide). */
    val controlsVisible = mutableStateOf(true)
    val interaction = mutableIntStateOf(0)
    /** Time of the last remote-control seek while controls are hidden (shows a small seek bar). */
    val seekOsd = mutableLongStateOf(0L)
    private var osdJob: Job? = null

    /** Hinge position (top, bottom in px) while a foldable is half-open with a horizontal fold ("tabletop"). */
    val tabletopHinge = mutableStateOf<Pair<Int, Int>?>(null)
    private var hasFold = false
    val textTracks = mutableStateOf<List<TrackOption>>(emptyList())

    // AirPlay
    val airplayDevices get() = discovery.devices
    val airplaySearching get() = discovery.searching
    val airplay = mutableStateOf<AirPlaySession?>(null)
    val airplayPlaying = mutableStateOf(true)
    val airplayBusy = mutableStateOf(false)
    private val discovery by lazy { AirPlayDiscovery(this) }
    private var airplayPoll: Job? = null

    val current: PlayItem? get() = queue.value.items.getOrNull(index.intValue)
    private var triedAlt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        applyOrientationPolicy()
        // Foldables: watch the hinge so we can switch to tabletop layout and stop forcing landscape.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@PlayerActivity).windowLayoutInfo(this@PlayerActivity).collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    hasFold = hasFold || fold != null
                    tabletopHinge.value = fold?.takeIf { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                        ?.bounds?.let { it.top to it.bottom }
                    applyOrientationPolicy()
                }
            }
        }

        val q = NowPlaying.take(this, intent.getLongExtra("queueToken", -1))
        if (q == null || q.items.isEmpty()) { finish(); return }
        queue.value = q

        exo = Players.build(this, graph.downloads.upstream)
        exo.addListener(listener)

        Cast.context(this)?.let { ctx ->
            castPlayer = CastPlayer(ctx).apply {
                addListener(listener)
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = switchToCast()
                    override fun onCastSessionUnavailable() = switchToLocal()
                })
            }
        }

        if (castPlayer?.isCastSessionAvailable == true) {
            casting.value = true
            castDeviceName.value = Cast.context(this)?.sessionManager?.currentCastSession?.castDevice?.friendlyName
            player.value = castPlayer
        } else player.value = exo
        load(q.index, resume = true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        // UI position ticker + periodic progress save
        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                player.value?.let { p ->
                    position.longValue = p.currentPosition.coerceAtLeast(0)
                    duration.longValue = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
                    bufferedPos.longValue = p.bufferedPosition
                }
                if (++tick % 6 == 0) saveProgress() // every 3 s while playing
                delay(500)
            }
        }

        setContent { MkTheme { PlayerScreen(this) } }
    }

    /* ---------------- loading ---------------- */

    private fun mimeFor(item: PlayItem, url: String): String = when {
        item.kind == "live" && url.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
        item.kind == "live" || url.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        url.endsWith(".mkv", true) -> MimeTypes.VIDEO_MATROSKA
        url.endsWith(".webm", true) -> MimeTypes.VIDEO_WEBM
        url.endsWith(".ts", true) -> MimeTypes.VIDEO_MP2T
        else -> MimeTypes.VIDEO_MP4
    }

    private fun mediaItem(item: PlayItem, url: String, forCast: Boolean): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(item.title)
            .setSubtitle(item.subtitle)
            .setArtworkUri(item.image?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.key)
            .setMediaMetadata(meta)
            .apply {
                if (forCast) setMimeType(mimeFor(item, url))
                else if (url.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                if (!forCast && item.downloadId != null && graph.downloads.isCompleted(item.downloadId)) setCustomCacheKey(item.downloadId)
            }
            .build()
    }

    fun load(i: Int, resume: Boolean = true) {
        saveProgress()
        val item = queue.value.items.getOrNull(i) ?: return
        index.intValue = i
        error.value = null
        triedAlt = false
        val startAt = if (resume && item.kind != "live") graph.store.progress(profileId(), item.key)?.takeIf { !it.finished && it.position > 10_000 }?.position ?: 0L else 0L
        val isOffline = item.downloadId != null && graph.downloads.isCompleted(item.downloadId)
        offline.value = isOffline

        airplay.value?.let { s -> lifecycleScope.launch { runCatching { s.play(item.url, 0.0) }.onFailure { error.value = it.message } } }

        val p = player.value ?: return
        if (p === castPlayer) {
            castPlayer?.setMediaItem(mediaItem(item, item.url, forCast = true), startAt)
            castPlayer?.prepare()
            castPlayer?.playWhenReady = true
        } else {
            val factory = if (isOffline) graph.downloads.offlineFactory else graph.downloads.upstream
            val source = Players.mediaSourceFactory(this, factory).createMediaSource(mediaItem(item, localUrl(item), forCast = false))
            exo.setMediaSource(source, startAt)
            exo.prepare()
            exo.playWhenReady = airplay.value == null
            watchStart(i)
        }
    }

    /** If a stream hasn't started after 15 s, try the other live format, then say so instead of a silent black screen. */
    private var startWatch: Job? = null
    private fun watchStart(i: Int) {
        startWatch?.cancel()
        startWatch = lifecycleScope.launch {
            delay(15_000)
            if (index.intValue != i || player.value !== exo || airplay.value != null) return@launch
            if (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_ENDED || error.value != null) return@launch
            val item = current ?: return@launch
            if (!triedAlt && item.altUrl != null) {
                triedAlt = true
                val other = if (localUrl(item) == item.url) item.altUrl else item.url
                exo.setMediaSource(Players.mediaSourceFactory(this@PlayerActivity, graph.downloads.upstream).createMediaSource(mediaItem(item, other, forCast = false)))
                exo.prepare(); exo.play()
                watchStart(i)
            } else error.value = "This title isn't starting. The server may be slow or offline — press Retry or try again later."
        }
    }

    /** Local playback URL: for live channels MPEG-TS first when "Fastest" is selected (Cast always uses HLS). */
    private fun localUrl(item: PlayItem): String =
        if (item.kind == "live" && item.altUrl != null && graph.store.data.value.settings.liveFormat != "hls") item.altUrl else item.url

    fun next() { if (index.intValue < queue.value.items.lastIndex) load(index.intValue + 1, resume = current?.kind == "live") }
    fun prev() { if (index.intValue > 0) load(index.intValue - 1, resume = current?.kind == "live") }
    fun retry() = load(index.intValue, resume = true)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
            if (!playing) saveProgress()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) saveProgress()
        }
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { error.value = null; refreshTracks() }
            if (state == Player.STATE_ENDED && current?.kind != "live") {
                saveProgress(ended = true)
                if (index.intValue < queue.value.items.lastIndex) next() else finish()
            }
        }
        override fun onTracksChanged(tracks: Tracks) = refreshTracks()
        override fun onPlayerError(e: PlaybackException) {
            val item = current ?: return
            if (!triedAlt && item.altUrl != null && player.value === exo) {
                triedAlt = true
                // Live: switch between MPEG-TS and HLS automatically.
                val other = if (localUrl(item) == item.url) item.altUrl else item.url
                val src = Players.mediaSourceFactory(this@PlayerActivity, graph.downloads.upstream).createMediaSource(mediaItem(item, other, forCast = false))
                exo.setMediaSource(src); exo.prepare(); exo.play()
                return
            }
            error.value = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Network problem — check your connection."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream (it may be offline or your connection limit is reached)."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    "This video format isn't supported on this device."
                else -> e.localizedMessage ?: "Playback error"
            }
        }
    }

    /* ---------------- tracks ---------------- */

    private fun refreshTracks() {
        val p = player.value ?: return
        if (!p.isCommandAvailable(Player.COMMAND_GET_TRACKS)) return
        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()
        p.currentTracks.groups.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it }
                val label = listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" } +
                    (if (g.type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) " · ${f.channelCount}ch" else "")
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption(label, g, i, g.isTrackSelected(i))
                    C.TRACK_TYPE_TEXT -> text += TrackOption(label, g, i, g.isTrackSelected(i))
                }
            }
        }
        val textOff = text.none { it.selected }
        audioTracks.value = audio
        textTracks.value = listOf(TrackOption("Off", null, -1, textOff)) + text
    }

    fun selectTrack(type: Int, opt: TrackOption) {
        val p = player.value ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (opt.group == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(opt.group.mediaTrackGroup, opt.index))
        p.trackSelectionParameters = b.build()
        refreshTracks()
    }

    fun setSpeed(s: Float) {
        speed.floatValue = s
        player.value?.playbackParameters = PlaybackParameters(s)
    }

    /* ---------------- Chromecast ---------------- */

    private fun switchToCast() {
        val cp = castPlayer ?: return
        stopAirPlay(resumeLocal = false)
        val item = current ?: return
        val pos = exo.currentPosition
        exo.pause()
        casting.value = true
        castDeviceName.value = Cast.context(this)?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        player.value = cp
        cp.setMediaItem(mediaItem(item, item.url, forCast = true), if (item.kind == "live") 0 else pos)
        cp.prepare()
        cp.playWhenReady = true
    }

    private fun switchToLocal() {
        val cp = castPlayer
        val pos = cp?.currentPosition ?: 0
        casting.value = false
        castDeviceName.value = null
        player.value = exo
        if (current?.kind != "live" && pos > 0) exo.seekTo(pos)
        exo.playWhenReady = true
    }

    /* ---------------- AirPlay ---------------- */

    fun startAirPlayDiscovery() = discovery.start()
    fun stopAirPlayDiscovery() = discovery.stop()

    fun airPlayTo(device: AirPlayDevice) {
        val item = current ?: return
        airplayBusy.value = true
        val session = AirPlaySession(device, graph.http)
        val fraction = if (exo.duration > 0 && item.kind != "live") exo.currentPosition.toDouble() / exo.duration else 0.0
        lifecycleScope.launch {
            val res = runCatching { session.play(item.url, fraction) }
            airplayBusy.value = false
            if (res.isFailure) { error.value = res.exceptionOrNull()?.message; return@launch }
            exo.pause()
            airplay.value = session
            airplayPlaying.value = true
            airplayPoll?.cancel()
            airplayPoll = launch {
                while (isActive && airplay.value === session) {
                    session.position()?.let { (pos, dur) ->
                        position.longValue = (pos * 1000).toLong(); duration.longValue = (dur * 1000).toLong()
                    }
                    delay(1000)
                }
            }
        }
    }

    fun airPlayToggle() {
        val s = airplay.value ?: return
        val play = !airplayPlaying.value
        airplayPlaying.value = play
        lifecycleScope.launch { runCatching { s.setRate(play) } }
    }

    fun airPlaySeekBy(deltaMs: Long) {
        val s = airplay.value ?: return
        val target = ((position.longValue + deltaMs).coerceAtLeast(0)) / 1000.0
        lifecycleScope.launch { runCatching { s.seek(target) } }
    }

    fun airPlaySeekTo(ms: Long) {
        val s = airplay.value ?: return
        lifecycleScope.launch { runCatching { s.seek(ms / 1000.0) } }
    }

    fun stopAirPlay(resumeLocal: Boolean = true) {
        val s = airplay.value ?: return
        val pos = position.longValue
        airplay.value = null
        airplayPoll?.cancel()
        lifecycleScope.launch { s.stop() }
        if (resumeLocal) {
            if (current?.kind != "live" && pos > 0) exo.seekTo(pos)
            exo.play()
        }
    }

    /* ---------------- progress ---------------- */

    private fun profileId() = sessionOf(graph.store.data.value).profileId

    private fun saveProgress(ended: Boolean = false) {
        val item = current ?: return
        if (item.kind == "live") return
        val p = player.value ?: return
        val dur = if (airplay.value != null) duration.longValue else p.duration
        val pos = if (airplay.value != null) position.longValue else p.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET) return
        if (pos < 5_000 && !ended) return
        graph.store.saveProgress(
            profileId(),
            Progress(
                key = item.key, position = if (ended) dur else pos, duration = dur, updatedAt = System.currentTimeMillis(),
                title = item.title, image = item.image, kind = item.kind, seriesId = item.seriesId, subtitle = item.subtitle,
                itemId = item.itemId, ext = item.ext, playlistId = item.playlistId,
            ),
        )
    }

    /* ---------------- PiP & lifecycle ---------------- */

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || casting.value || airplay.value != null) return
        val fmt = exo.videoFormat
        val ratio = if (fmt != null && fmt.width > 0 && fmt.height > 0) Rational(fmt.width, fmt.height) else Rational(16, 9)
        val safe = if (ratio.toFloat() > 2.39f) Rational(239, 100) else if (ratio.toFloat() < 0.42f) Rational(42, 100) else ratio
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exo.isPlaying && !Device.isTv(this)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val q = NowPlaying.take(this, intent.getLongExtra("queueToken", -1)) ?: return
        saveProgress()
        queue.value = q
        load(q.index, resume = true)
    }

    /**
     * Regular phones: landscape for video. Foldables and large screens (unfolded, tablets, multi-window):
     * follow the device so tabletop / book postures and the inner display work naturally.
     */
    /** Full-screen landscape button: locks the player to landscape until pressed again. */
    val forceLandscape = mutableStateOf(false)

    fun toggleLandscape() {
        forceLandscape.value = !forceLandscape.value
        applyOrientationPolicy()
    }

    private fun applyOrientationPolicy() {
        val large = resources.configuration.smallestScreenWidthDp >= 600
        val want = if (forceLandscape.value) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else if (large || hasFold || (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode)) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    /* ---------------- TV remote / keyboard ---------------- */

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun showControls() { controlsVisible.value = true; interaction.intValue++ }

    private fun osdSeek(deltaMs: Long) {
        val p = player.value ?: return
        if (airplay.value != null) airPlaySeekBy(deltaMs) else p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
        position.longValue = (position.longValue + deltaMs).coerceAtLeast(0)
        seekOsd.longValue = System.currentTimeMillis()
        osdJob?.cancel()
        osdJob = lifecycleScope.launch { delay(1800); seekOsd.longValue = 0 }
    }

    private fun togglePlay() {
        if (airplay.value != null) airPlayToggle() else player.value?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun handleKey(code: Int): Boolean {
        val live = current?.kind == "live"
        when (code) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); showControls(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!live) osdSeek(30_000); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { if (!live) osdSeek(-30_000); return true }
            android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { next(); return true }
            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { prev(); return true }
        }
        if (controlsVisible.value) {
            if (code == android.view.KeyEvent.KEYCODE_BACK && Device.isTv(this)) { controlsVisible.value = false; return true }
            interaction.intValue++ // keep controls up while the user navigates them
            return false
        }
        return when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!live) togglePlay(); showControls(); true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (live) showControls() else osdSeek(-10_000); true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (live) showControls() else osdSeek(10_000); true }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { if (live) prev() else showControls(); true }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (live) next() else showControls(); true }
            else -> false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        // Persist immediately: after onPause the process may be killed at any time.
        saveProgress()
        graph.store.flushBlocking()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        graph.store.flushBlocking()
        // keep playing while casting / AirPlaying; pause local playback otherwise (unless in PiP)
        if (!casting.value && airplay.value == null && !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) exo.pause()
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode.not() && isFinishing) exo.stop()
    }

    override fun onDestroy() {
        if (::exo.isInitialized) {
            saveProgress()
            graph.store.flushBlocking()
            exo.removeListener(listener)
            exo.release()
        }
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        runCatching { discovery.stop() }
        airplay.value?.let { s -> stopAirPlay(resumeLocal = false); kotlinx.coroutines.GlobalScope.launch { s.stop() } }
        super.onDestroy()
    }
}
