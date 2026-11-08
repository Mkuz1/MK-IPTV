package com.mk.iptv.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object Cast {
    /** Null when Google Play services aren't available (e.g. some Android TV boxes / Huawei). */
    fun context(ctx: Context): CastContext? = try {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) != ConnectionResult.SUCCESS) null
        else CastContext.getSharedInstance(ctx.applicationContext)
    } catch (t: Throwable) {
        null
    }

    fun isCasting(ctx: Context) = context(ctx)?.sessionManager?.currentCastSession?.isConnected == true
}

/** Google Cast / Chromecast route button. */
@Composable
fun CastRouteButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ctx).apply {
                runCatching {
                    if (Cast.context(ctx) != null) CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
                }
                setAlwaysVisible(true)
            }
        },
    )
}
