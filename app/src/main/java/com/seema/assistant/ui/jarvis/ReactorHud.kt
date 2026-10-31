package com.seema.assistant.ui.jarvis

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seema.assistant.AgentState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * MARK-I: the arc reactor.
 *
 * A single instrument, centred, sized to dominate the screen. The reactor is not a spinner:
 * the ring speed, segment count, core flare and colour all encode the real agent state, and the
 * core physically swells with the microphone level so it reacts in the same frame as the voice.
 */
@Composable
fun ReactorHud(
    state: AgentState,
    level: Float,
    logs: List<String>,
    config: SeemaConfig,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(state.hudColor(), tween(600), label = "reactor")
    val glow = config.glowIntensity.coerceIn(0f, 1f)
    val fast = state == AgentState.THINKING || state == AgentState.SPEAKING

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            ReactorCore(
                color = color,
                level = level,
                glow = glow,
                fast = fast,
                smooth = config.smoothReactor,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.hudLabel(),
                    color = color.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 7.sp
                )
                Spacer(Modifier.height(8.dp))
                Waveform(level = level, color = color, active = state != AgentState.IDLE && state != AgentState.SLEEPING)
            }
        }

        StatusCard(state = state, logs = logs, color = color)
    }
}

@Composable
private fun ReactorCore(
    color: Color,
    level: Float,
    glow: Float,
    fast: Boolean,
    smooth: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "reactor")
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (fast) 6_000 else 22_000, easing = LinearEasing)),
        label = "spin"
    )
    val counter by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (fast) 9_000 else 30_000, easing = LinearEasing)),
        label = "counter"
    )
    val pulse by transition.animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val sweep by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3_000, easing = LinearEasing)),
        label = "sweep"
    )

    Canvas(modifier = modifier) {
        val u = 1.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = size.minDimension * 0.5f
        val r = unit * 0.44f * (if (smooth) pulse else 1f) * (1f + level * 0.09f)

        // ---- outer halo: layered flat discs, no per-frame shader
        for (i in 6 downTo 1) {
            drawCircle(
                color = color.copy(alpha = 0.022f + glow * 0.018f),
                radius = r * (1f + i * 0.42f),
                center = Offset(cx, cy)
            )
        }

        // ---- tick collar
        val collar = r * 1.42f
        for (i in 0 until 72) {
            val a = (i * 5f - 90f) * (PI.toFloat() / 180f)
            val long = i % 6 == 0
            val inner = collar + if (long) 0f else 1.4f * u
            val outer = collar + if (long) 9f * u else 4f * u
            drawLine(
                color = color.copy(alpha = if (long) 0.60f * (0.5f + glow * 0.5f) else 0.18f),
                start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
                end = Offset(cx + cos(a) * outer, cy + sin(a) * outer),
                strokeWidth = if (long) 1.6f * u else 0.9f * u
            )
        }

        // ---- sweeping radar arc on the collar
        rotate(degrees = sweep * 360f, pivot = Offset(cx, cy)) {
            drawArc(
                color = color.copy(alpha = 0.45f),
                startAngle = 0f,
                sweepAngle = 52f,
                useCenter = false,
                topLeft = Offset(cx - collar, cy - collar),
                size = Size(collar * 2f, collar * 2f),
                style = Stroke(width = 2f * u)
            )
        }

        // ---- three segmented power rings, alternating direction
        val rings = floatArrayOf(1.18f, 1.02f, 0.86f)
        rings.forEachIndexed { index, factor ->
            val rr = r * factor
            val rotation = if (index % 2 == 0) spin else counter
            rotate(degrees = rotation * (1f + index * 0.15f), pivot = Offset(cx, cy)) {
                val segments = 8 - index * 2
                val gap = 360f / segments * 0.42f
                for (s in 0 until segments) {
                    drawArc(
                        color = color.copy(alpha = (0.30f + glow * 0.35f) - index * 0.05f),
                        startAngle = s * (360f / segments) + gap * 0.5f,
                        sweepAngle = 360f / segments - gap,
                        useCenter = false,
                        topLeft = Offset(cx - rr, cy - rr),
                        size = Size(rr * 2f, rr * 2f),
                        style = Stroke(width = (2.4f - index * 0.4f) * u)
                    )
                }
            }
        }

        // ---- crosshair
        val cross = r * 1.55f
        drawLine(color.copy(alpha = 0.10f), Offset(cx - cross, cy), Offset(cx - r * 1.46f, cy), 1f)
        drawLine(color.copy(alpha = 0.10f), Offset(cx + r * 1.46f, cy), Offset(cx + cross, cy), 1f)
        drawLine(color.copy(alpha = 0.10f), Offset(cx, cy - cross), Offset(cx, cy - r * 1.46f), 1f)
        drawLine(color.copy(alpha = 0.10f), Offset(cx, cy + r * 1.46f), Offset(cx, cy + cross), 1f)

        // ---- reactor core: triangular coil inside a ring
        drawCircle(
            color = color.copy(alpha = 0.10f + glow * 0.10f),
            radius = r * 0.58f,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = color.copy(alpha = 0.55f),
            radius = r * 0.58f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.4f * u)
        )
        val coil = r * 0.34f
        for (i in 0 until 3) {
            val a0 = (i * 120f + spin * 0.4f) * (PI.toFloat() / 180f)
            val a1 = ((i + 1) * 120f + spin * 0.4f) * (PI.toFloat() / 180f)
            drawLine(
                color = color.copy(alpha = 0.75f),
                start = Offset(cx + cos(a0) * coil, cy + sin(a0) * coil),
                end = Offset(cx + cos(a1) * coil, cy + sin(a1) * coil),
                strokeWidth = 1.6f * u
            )
        }

        // ---- hot core flare, driven by the microphone
        for (i in 3 downTo 1) {
            drawCircle(
                color = color.copy(alpha = (0.10f + level * 0.10f) * i),
                radius = coil * (0.30f + i * 0.22f) * (1f + level * 0.25f),
                center = Offset(cx, cy)
            )
        }
        drawCircle(
            color = Color.White.copy(alpha = 0.55f + level * 0.35f),
            radius = (2.0f + level * 3.0f) * u,
            center = Offset(cx, cy)
        )
    }
}

/** 32-bar equalizer fed by the real microphone level. */
@Composable
private fun Waveform(level: Float, color: Color, active: Boolean) {
    Canvas(modifier = Modifier.width(210.dp).height(16.dp)) {
        val bars = 32
        val gap = size.width / bars
        val mid = bars / 2f
        for (i in 0 until bars) {
            val distance = abs(i - mid) / mid
            val shape = 1f - distance * 0.55f
            val wobble = 0.35f + 0.65f * abs(sin(i * 0.7f + level * 9f))
            val base = if (active) 0.10f + level * shape * wobble else 0.06f
            val h = (size.height * base).coerceIn(1.2f, size.height)
            drawRect(
                color = color.copy(alpha = if (active) 0.75f else 0.25f),
                topLeft = Offset(i * gap + gap * 0.25f, (size.height - h) / 2f),
                size = Size(gap * 0.5f, h)
            )
        }
    }
}

/** Frosted status card: online flag, live state and the tail of the command log. */
@Composable
private fun StatusCard(state: AgentState, logs: List<String>, color: Color) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(7.dp)) {
                        drawCircle(HUD.Online.copy(alpha = 0.30f), radius = size.minDimension)
                        drawCircle(HUD.Online, radius = size.minDimension * 0.45f)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "SEEMA_JARVIS ONLINE",
                        color = HUD.TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.4.sp
                    )
                }
                Text(
                    text = state.hudLabel(),
                    color = color,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HUD.Hairline))
            Spacer(Modifier.height(10.dp))
            CommandLog(logs = logs)
        }
    }
}

@Composable
private fun CommandLog(logs: List<String>) {
    val visible = if (logs.isEmpty()) listOf("core idle — awaiting wake word") else logs.takeLast(3)
    Column(Modifier.fillMaxWidth().height(58.dp), verticalArrangement = Arrangement.Bottom) {
        visible.forEachIndexed { index, line ->
            val newest = index == visible.lastIndex
            Text(
                text = (if (newest) "› " else "  ") + line,
                color = if (newest) HUD.TextPrimary else HUD.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
        }
    }
}