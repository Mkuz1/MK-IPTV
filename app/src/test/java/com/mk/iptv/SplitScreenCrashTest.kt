package com.mk.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mk.iptv.ui.AmbientBackground
import com.mk.iptv.ui.glass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Android 9 split-screen briefly lays windows out at 0 px while resizing/minimizing.
 * Every drawing path must survive zero-sized surfaces (RadialGradient throws for radius 0).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w512dp-h600dp-land-mdpi")
class SplitScreenCrashTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zeroSizedSurfacesDoNotCrash() {
        compose.setContent {
            Column {
                Box(Modifier.width(0.dp).height(300.dp)) { AmbientBackground {} }
                Box(Modifier.size(0.dp).glass())
                Box(Modifier.width(0.dp).height(40.dp).glass())
            }
        }
        compose.waitForIdle()
    }
}
