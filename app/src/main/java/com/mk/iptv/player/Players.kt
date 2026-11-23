package com.mk.iptv.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Device helpers shared by the UI and the player. */
object Device {
    fun isTv(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isLowRam(ctx: Context): Boolean =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}

/**
 * ExoPlayer tuned for the fastest start-up on phones, tablets and TV boxes:
 * - playback starts after ~1 s of media (2.5 s after a stall), with a larger buffer behind it,
 * - one shared bandwidth estimate so every stream starts at the right quality immediately,
 * - MPEG-TS live streams start without waiting for a keyframe,
 * - automatic fallback to a software decoder if a hardware decoder fails,
 * - exact seeking (skip buttons always move the right way).
 */
object Players {
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)

    fun mediaSourceFactory(ctx: Context, dataSource: DataSource.Factory) = DefaultMediaSourceFactory(dataSource, extractors())

    fun build(ctx: Context, dataSource: DataSource.Factory, preview: Boolean = false): ExoPlayer {
        val lowRam = Device.isLowRam(ctx)
        val tv = Device.isTv(ctx)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ if (preview) 8_000 else 20_000,
                /* maxBufferMs */ if (preview) 20_000 else if (lowRam) 40_000 else 90_000,
                /* bufferForPlaybackMs */ 1_000,
                /* bufferForPlaybackAfterRebufferMs */ 2_500,
            )
            .setTargetBufferBytes(if (lowRam) 24 * 1024 * 1024 else C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val selector = DefaultTrackSelector(ctx).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false)   // tunneled playback stalls on many TV boxes with IPTV streams
                .setPreferredAudioLanguage(java.util.Locale.getDefault().language)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        }
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(mediaSourceFactory(ctx, dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(ctx))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { setSeekParameters(SeekParameters.DEFAULT) }   // exact: +10 s never snaps back to an earlier keyframe
    }
}
