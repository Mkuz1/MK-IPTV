package com.mk.iptv.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mk.iptv.BuildConfig
import com.mk.iptv.MkApp
import com.mk.iptv.ui.GlassButton
import com.mk.iptv.ui.Mk
import com.mk.iptv.ui.PrimaryButton
import com.mk.iptv.ui.ProgressLine
import com.mk.iptv.ui.TitleStyle
import com.mk.iptv.ui.formatBytes
import com.mk.iptv.ui.glass
import kotlinx.coroutines.launch

/** Global "update available" dialog; shown over any screen on phone, tablet and TV. */
@Composable
fun UpdateDialogHost() {
    val mgr = MkApp.graph.updates
    val state by mgr.state.collectAsState()
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    Dialog(onDismissRequest = { if (!busy) mgr.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 460.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp)).background(Color(0xF2141826))
                    .glass(RoundedCornerShape(32.dp), strength = .7f).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Mk.accentGradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("Update available", style = TitleStyle, fontSize = 22.sp)
                Text("MK IPTV ${info.versionName}  ·  you have ${BuildConfig.VERSION_NAME}", color = Mk.Sub, fontSize = 13.sp)
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        info.notes, color = Color(0xD9F0F2FF), fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(18.dp))
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        ProgressLine(s.progress, height = 6.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("Downloading… ${(s.progress * 100).toInt()}%" + if (info.sizeBytes > 0) " of ${formatBytes(info.sizeBytes)}" else "", color = Mk.Sub, fontSize = 13.sp)
                    }
                    is UpdateState.Installing -> Text("Installing — confirm on the next screen if asked.", color = Mk.Sub, fontSize = 13.sp, textAlign = TextAlign.Center)
                    is UpdateState.NeedsPermission -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = Mk.Gold, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text("Allow MK IPTV to install updates (one time).", color = Mk.Gold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Open settings", { runCatching { ctx.startActivity(mgr.permissionIntent()) } }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GlassButton("Later", { mgr.dismiss() }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Mk.Live, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(s.message, color = Mk.Live, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Try again", { scope.launch { mgr.downloadAndInstall(info) } }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GlassButton("Close", { mgr.dismiss() }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        PrimaryButton("Update now", { scope.launch { mgr.downloadAndInstall(info) } }, icon = Icons.Rounded.Download, modifier = Modifier.fillMaxWidth(), height = 52.dp)
                        Spacer(Modifier.height(8.dp))
                        GlassButton("Later", { mgr.dismiss() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Mk.Faint, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                    Text("Verified: checksum + MK IPTV signature", color = Mk.Faint, fontSize = 11.sp)
                }
            }
        }
    }
}
