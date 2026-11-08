package com.mk.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

object Mk {
    val Bg = Color(0xFF06070C)
    val Text = Color(0xFFF5F6FA)
    val Sub = Color(0xFFEBEEFF).copy(alpha = .62f)
    val Faint = Color(0xFFEBEEFF).copy(alpha = .38f)
    val Accent = Color(0xFF7CC4FF)
    val Accent2 = Color(0xFFB28DFF)
    val Live = Color(0xFFFF3B5C)
    val Gold = Color(0xFFFFD36B)
    val Green = Color(0xFF34C77B)
    val Pink = Color(0xFFFF5F8F)
    val SheetBg = Color(0xFF12141E)

    val avatarColors = listOf(
        listOf(Color(0xFF7CC4FF), Color(0xFF9B7BFF)),
        listOf(Color(0xFFFFB36B), Color(0xFFFF5F8F)),
        listOf(Color(0xFF7DF0B2), Color(0xFF2BB6FF)),
        listOf(Color(0xFFC9C9D6), Color(0xFF6F7390)),
        listOf(Color(0xFFFF8A65), Color(0xFFFF3D71)),
        listOf(Color(0xFFFFE066), Color(0xFFFF9F1C)),
    )
    fun avatar(i: Int) = avatarColors[((i % avatarColors.size) + avatarColors.size) % avatarColors.size]
    val accentGradient = Brush.horizontalGradient(listOf(Accent, Accent2))
}

val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun MkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mk.Accent, secondary = Mk.Accent2, background = Mk.Bg, surface = Mk.SheetBg,
            onPrimary = Color(0xFF0A0B10), onBackground = Mk.Text, onSurface = Mk.Text,
        ),
        content = content,
    )
}

/* ---------------- Liquid glass ---------------- */

private val rimBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = .55f), .35f to Color.White.copy(alpha = .10f),
    .65f to Color.White.copy(alpha = .06f), 1f to Color.White.copy(alpha = .28f),
    start = Offset.Zero, end = Offset.Infinite,
)

/** Translucent "liquid glass" surface: tinted fill, specular highlight and a bright rim. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), tint: Color = Color.White, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            0f to tint.copy(alpha = .20f * strength), .4f to tint.copy(alpha = .08f * strength),
            .6f to tint.copy(alpha = .05f * strength), 1f to tint.copy(alpha = .13f * strength),
            start = Offset.Zero, end = Offset.Infinite,
        )
    )
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .16f * strength), Color.Transparent),
                center = Offset(size.width * .2f, -size.height * .15f), radius = size.maxDimension * .75f,
            )
        )
    }
    .border(1.dp, rimBrush, shape)

/** Glass that also blurs whatever is rendered behind it (Android 12+; tinted fallback below). */
@Composable
fun Modifier.liquidGlass(shape: Shape = RoundedCornerShape(24.dp), dark: Boolean = false): Modifier {
    val haze = LocalHaze.current
    val base = if (haze != null) {
        this.clip(shape).hazeChild(
            state = haze,
            style = HazeStyle(
                backgroundColor = Mk.Bg,
                tint = HazeTint(if (dark) Color(0xB0101320) else Color(0x40181B28)),
                blurRadius = 24.dp,
                noiseFactor = 0.05f,
                fallbackTint = HazeTint(Color(0xE0101320)),
            ),
        )
    } else this.clip(shape).background(if (dark) Color(0xE6121520) else Color(0xB0151826))
    return base.glass(shape, strength = if (dark) .7f else 1f)
}

@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    return this.scale(if (pressed) .95f else 1f)
}

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.pressScale(src).clickable(interactionSource = src, indication = null, onClick = onClick)
}

/* ---------------- Components ---------------- */

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.White,
    blur: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .then(if (blur) Modifier.liquidGlass(CircleShape) else Modifier.glass(CircleShape))
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize)) }
}

@Composable
fun GlassChip(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    height: Dp = 36.dp,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .then(
                if (selected) Modifier.clip(shape).background(Color.White.copy(alpha = .93f))
                else Modifier.liquidGlass(shape)
            )
            .tap(onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val c = if (selected) Color(0xFF0B0C12) else Color.White
        leading?.let { Icon(it, null, tint = c, modifier = Modifier.size(16.dp)) }
        Text(text, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        trailing?.let { Icon(it, null, tint = c, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (enabled) .95f else .4f))
            .then(if (enabled && !loading) Modifier.tap(onClick) else Modifier)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF0A0B10), strokeWidth = 2.dp)
        else icon?.let { Icon(it, null, tint = Color(0xFF0A0B10), modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 46.dp,
    tint: Color = Color.White,
) {
    Row(
        modifier
            .height(height)
            .liquidGlass(RoundedCornerShape(50))
            .tap(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (highlighted) Modifier.border(2.dp, Mk.Accent.copy(alpha = .45f), shape) else Modifier)
            .liquidGlass(shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Brush = Mk.accentGradient, height: Dp = 3.5.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = .18f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Mk.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(action, color = Mk.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.tap(onAction))
        }
    }
}

@Composable
fun Badge(text: String, color: Color = Color.White) {
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = .4f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun Avatar(name: String, colorIndex: Int, size: Dp = 42.dp, corner: Dp = size / 2, fontSize: Int = 15, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(Mk.avatar(colorIndex)))
            .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = fontSize.sp,
        )
    }
}

@Composable
fun CenterMessage(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(color = Mk.Accent, strokeWidth = 3.dp)
            icon?.let {
                Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    Icon(it, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Text(title, color = Mk.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            body?.let { Text(it, color = Mk.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp) }
            if (action != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryButton(action, onAction)
            }
        }
    }
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(
        modifier
            .fillMaxSize()
            .background(Mk.Bg)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Color(0x806A4DFF), Color.Transparent), center = Offset(size.width * .1f, size.height * .2f), radius = size.width * .8f))
                drawCircle(Brush.radialGradient(listOf(Color(0x60FF4D8D), Color.Transparent), center = Offset(size.width * 1f, size.height * .5f), radius = size.width * .75f))
                drawCircle(Brush.radialGradient(listOf(Color(0x602BC3FF), Color.Transparent), center = Offset(size.width * .3f, size.height * .95f), radius = size.width * .8f))
            },
        content = content,
    )
}

val TitleStyle = TextStyle(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, color = Mk.Text)

fun initials(name: String): String {
    val words = name.trim().split(" ").filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 && words[0].length <= 2 -> words[0].uppercase()
        words.size == 1 -> words[0].take(1).uppercase()
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase()
    }
}

/* ---------------- Adaptive layout (phones, foldables, tablets) ---------------- */

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** Window width class; re-evaluated on fold/unfold, rotation and multi-window resizes. */
@Composable
fun widthClass(): WidthClass {
    val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WidthClass.EXPANDED
        w >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}

@Composable
fun isWide(): Boolean = widthClass() != WidthClass.COMPACT

/** Two panes side by side (unfolded foldables / tablets in landscape, ≥ 720dp). */
@Composable
fun isTwoPane(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720
