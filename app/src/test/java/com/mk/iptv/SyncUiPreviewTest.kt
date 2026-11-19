package com.mk.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.mk.iptv.sync.LinkCode
import com.mk.iptv.sync.SyncStatus
import com.mk.iptv.ui.SyncSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h500dp-xhdpi")
class SyncUiPreviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun states() {
        val f = com.mk.iptv.sync.SyncManager::class.java.getDeclaredField("_status").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val st = f.get(MkApp.graph.sync) as MutableStateFlow<SyncStatus>
        compose.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF0A0B12))) { SyncSettings() } }
        val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        st.value = SyncStatus.Off; compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "sync-off.png").path)
        st.value = SyncStatus.On("mk@example.com", System.currentTimeMillis() - 60_000, LinkCode("K7QM4XPZ", 0)); compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "sync-on.png").path)
    }
}
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() = onNode(androidx.compose.ui.test.isRoot())
