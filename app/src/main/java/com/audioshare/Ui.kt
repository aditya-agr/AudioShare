package com.audioshare

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val Violet = Color(0xFF7C5CFF)
private val Brand = Brush.linearGradient(listOf(Violet, Color(0xFF22D3EE)))

private class Palette(
    val bg: Color, val card: Color, val cardHi: Color, val line: Color, val text: Color, val dim: Color,
    val cyan: Color, val pink: Color, val danger: Color,
)

private val Dark = Palette(
    bg = Color(0xFF0B0D17), card = Color(0xFF151827), cardHi = Color(0xFF1E2236), line = Color(0xFF262A40),
    text = Color(0xFFF2F3F8), dim = Color(0xFF9AA0B8), cyan = Color(0xFF22D3EE), pink = Color(0xFFF472B6), danger = Color(0xFFFF5470),
)
private val Light = Palette(
    bg = Color(0xFFF4F5FA), card = Color.White, cardHi = Color(0xFFECEEF6), line = Color(0xFFE2E5EF),
    text = Color(0xFF12142A), dim = Color(0xFF5D6380), cyan = Color(0xFF0891B2), pink = Color(0xFFDB2777), danger = Color(0xFFE11D48),
)

private val LocalPalette = staticCompositionLocalOf { Dark }
private val P: Palette @Composable get() = LocalPalette.current

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = Violet, onPrimary = Color.White, secondary = p.cyan, background = p.bg, surface = p.card,
            onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.dim, error = p.danger,
        ),
    ) {
        // Surface sets the default text/icon colour; without it Text falls back to black.
        CompositionLocalProvider(LocalPalette provides p) { Surface(color = p.bg, contentColor = p.text, content = content) }
    }
}

@Composable
fun HomeScreen(onShare: () -> Unit, onSong: () -> Unit, onJoin: () -> Unit, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(P.bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        Hero(Status.mode)
        AnimatedContent(Status.mode == Mode.Idle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "body") { idle ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (idle) {
                    Text("START A SESSION", style = MaterialTheme.typography.labelMedium, color = P.dim,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                    ModeCard(R.drawable.ic_eq, Violet, "Share phone audio", "Spotify, YouTube or any app", onShare)
                    ModeCard(R.drawable.ic_music, P.cyan, "Play a song", "Pick a file. This phone plays in sync too", onSong)
                    ModeCard(R.drawable.ic_speaker, P.pink, "Join as speaker", "Play what a host on this Wi-Fi shares", onJoin)
                    Text("All phones must be on the same Wi-Fi network", style = MaterialTheme.typography.bodySmall,
                        color = P.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    if (Status.mode != Mode.Capture) DelayCard() // capture host has no local playback to tune
                    Button(
                        onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = P.danger.copy(alpha = 0.15f), contentColor = P.danger),
                    ) {
                        Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brand), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_stat), null, Modifier.size(26.dp), tint = Color.White)
    }
    Spacer(Modifier.width(12.dp))
    Column {
        Text("AudioShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("One sound, every speaker", style = MaterialTheme.typography.bodySmall, color = P.dim)
    }
}

@Composable
private fun Hero(mode: Mode) {
    val active = mode != Mode.Idle
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(P.card)
            .border(1.dp, if (active) Violet.copy(alpha = 0.5f) else P.line, shape).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            if (active) Rings()
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(if (active) Brand else SolidColor(P.cardHi)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_stat), null, Modifier.size(40.dp), tint = if (active) Color.White else P.dim)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (mode) {
                Mode.Idle -> "Ready"
                Mode.Capture -> "Sharing this phone"
                Mode.Song -> "Playing a song"
                Mode.Receiver -> "Speaker mode"
            },
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(Status.text, style = MaterialTheme.typography.bodyMedium, color = P.dim, textAlign = TextAlign.Center)
        if (mode == Mode.Capture || mode == Mode.Song) {
            Spacer(Modifier.height(16.dp))
            val n = Status.clients
            Row(
                Modifier.clip(CircleShape).background(P.cardHi).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (n > 0) P.cyan else P.dim))
                Spacer(Modifier.width(8.dp))
                Text("$n speaker${if (n == 1) "" else "s"} connected", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Expanding rings: the "broadcasting" signal while a session is live. */
@Composable
private fun Rings() {
    val t by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        repeat(3) { i ->
            val p = (t + i / 3f) % 1f
            drawCircle(Brand, radius = size.minDimension / 2 * (0.5f + 0.5f * p), alpha = (1 - p) * 0.6f, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ModeCard(@DrawableRes icon: Int, tint: Color, title: String, subtitle: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.card).clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
    }
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = P.dim)
    }
    Icon(painterResource(R.drawable.ic_chevron), null, tint = P.dim)
}

@Composable
private fun DelayCard() = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.card).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Speaker delay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${Status.delayMs} ms", style = MaterialTheme.typography.titleMedium, color = P.cyan)
    }
    Text("If this speaker plays ahead of the others, add delay until they sound like one.",
        style = MaterialTheme.typography.bodySmall, color = P.dim)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Nudge("−") { Status.delayMs = (Status.delayMs - 10).coerceAtLeast(0) }
        Slider(
            value = Status.delayMs.toFloat(), onValueChange = { Status.delayMs = it.toInt() }, valueRange = 0f..500f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Violet, inactiveTrackColor = P.cardHi),
        )
        Nudge("+") { Status.delayMs = (Status.delayMs + 10).coerceAtMost(500) }
    }
}

/** ±10 ms fine step: dragging the slider alone is too coarse to line up a beat. */
@Composable
private fun Nudge(label: String, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(P.cardHi).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(label, style = MaterialTheme.typography.titleLarge) }
