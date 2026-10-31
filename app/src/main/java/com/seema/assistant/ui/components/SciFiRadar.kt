package com.seema.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.seema.assistant.ui.theme.JarvisCyan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radar with a rotating sweep, contacts that light up as the sweep passes, and a pulsing core.
 * Tapping toggles [scanning]: the radar grows, sweeps faster and shows "SCANNING...".
 * Animated values are only read while drawing, so the radar never recomposes per frame.
 */
@Composable
fun SciFiRadar(
    scanning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactSize: Dp = COMPACT_SIZE,
    expandedSize: Dp = EXPANDED_SIZE
) {
    val radarSize by animateDpAsState(
        targetValue = if (scanning) expandedSize else compactSize,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "radarSize"
    )
    val transition = rememberInfiniteTransition(label = "radar")
    val slowSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SLOW_SWEEP_MS, easing = LinearEasing)),
        label = "slowSweep"
    )
    val fastSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(FAST_SWEEP_MS, easing = LinearEasing)),
        label = "fastSweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .size(radarSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRadar(
                sweep = if (scanning) fastSweep else slowSweep,
                pulse = pulse,
                color = JarvisCyan
            )
        }
        if (scanning) {
            Text(
                text = "SCANNING...",
                style = MaterialTheme.typography.labelLarge,
                color = JarvisCyan,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .graphicsLayer { alpha = 0.35f + 0.65f * pulse }
            )
        }
    }
}

private fun DrawScope.drawRadar(sweep: Float, pulse: Float, color: Color) {
    val c = center
    val r = size.minDimension / 2f * 0.92f
    val thin = 1.dp.toPx()

    // Disc and rings
    drawCircle(color.copy(alpha = 0.05f), radius = r, center = c)
    for (ring in 1..RINGS) {
        drawCircle(color.copy(alpha = 0.15f + 0.1f * ring / RINGS), radius = r * ring / RINGS, center = c, style = Stroke(thin))
    }
    // Glowing outer rim: a wide faint stroke under a thin bright one
    drawCircle(color.copy(alpha = 0.18f), radius = r, center = c, style = Stroke(6.dp.toPx()))
    drawCircle(color.copy(alpha = 0.9f), radius = r, center = c, style = Stroke(1.5.dp.toPx()))

    // Crosshair
    drawLine(color.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), thin)
    drawLine(color.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), thin)

    // Bezel ticks every 10°, longer every 30°
    for (deg in 0 until 360 step 10) {
        val a = deg * DEG_TO_RAD
        val inner = if (deg % 30 == 0) r * 0.88f else r * 0.94f
        drawLine(
            color.copy(alpha = if (deg % 30 == 0) 0.8f else 0.35f),
            Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
            Offset(c.x + cos(a) * r, c.y + sin(a) * r),
            thin
        )
    }

    // Sweep: a fading wedge trailing a bright leading edge (Compose angles: 0° = 3 o'clock, clockwise)
    rotate(sweep, c) {
        drawArc(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                TRAIL_START to Color.Transparent,
                1f to color.copy(alpha = 0.55f),
                center = c
            ),
            startAngle = -TRAIL_DEGREES,
            sweepAngle = TRAIL_DEGREES,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
        drawLine(color, c, Offset(c.x + r, c.y), 2.dp.toPx())
    }

    // Contacts glow when the sweep has just passed them, then fade
    for ((angle, distance) in CONTACTS) {
        val sincePassed = (sweep - angle + 360f) % 360f
        val glow = (1f - sincePassed / CONTACT_FADE_DEGREES).coerceAtLeast(0f)
        if (glow <= 0f) continue
        val a = angle * DEG_TO_RAD
        val p = Offset(c.x + cos(a) * r * distance, c.y + sin(a) * r * distance)
        drawCircle(color.copy(alpha = 0.3f * glow), radius = 7.dp.toPx(), center = p)
        drawCircle(color.copy(alpha = glow), radius = 2.5.dp.toPx(), center = p)
    }

    // Pulsing core
    val core = 3.dp.toPx() + 3.dp.toPx() * pulse
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), center = c, radius = core * 4),
        radius = core * 4,
        center = c
    )
    drawCircle(color, radius = core, center = c)
}

private val COMPACT_SIZE = 140.dp
private val EXPANDED_SIZE = 220.dp
private const val SLOW_SWEEP_MS = 3_000
private const val FAST_SWEEP_MS = 1_200
private const val PULSE_MS = 900
private const val RINGS = 4
private const val TRAIL_DEGREES = 65f
private const val TRAIL_START = 1f - TRAIL_DEGREES / 360f
private const val CONTACT_FADE_DEGREES = 140f
private const val DEG_TO_RAD = (PI / 180).toFloat()

/** Fixed contacts: angle in degrees, distance as a fraction of the radius. */
private val CONTACTS = listOf(35f to 0.62f, 118f to 0.38f, 205f to 0.8f, 290f to 0.5f, 330f to 0.25f)
