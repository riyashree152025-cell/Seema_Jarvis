package com.seema.assistant.ui.jarvis

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.seema.assistant.AgentState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One point on the unit sphere. */
private class Node(val x: Float, val y: Float, val z: Float)

/**
 * Evenly distributed points on a sphere (golden-angle spiral).
 *
 * Precomputed once: the animation only rotates and projects them, so a frame allocates
 * nothing and the core stays smooth even on a mid-range phone that is also running the
 * wake word engine.
 */
private val CORE_NODES: List<Node> = buildList {
    val count = 96
    val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
    for (i in 0 until count) {
        val y = 1f - (i / (count - 1f)) * 2f
        val ring = sqrt((1f - y * y).coerceAtLeast(0f))
        val theta = golden * i
        add(Node(cos(theta) * ring, y, sin(theta) * ring))
    }
}

/**
 * The core: a rotating constellation, three tilted orbital rings and an instrument collar.
 *
 * This is the whole point of the screen, so it is sized to dominate it — roughly two thirds
 * of the short edge — rather than sitting in the middle like a notification badge.
 *
 * No shader gradients are used. A layered halo built from plain circles costs a fraction of
 * a per-frame radial gradient, which matters because this canvas redraws sixty times a second
 * for as long as the app is open.
 */
@Composable
fun JarvisCore(
    state: AgentState,
    level: Float,
    modifier: Modifier = Modifier
) {
    val target = state.jarvisColor()
    val color by animateColorAsState(target, tween(700), label = "coreColor")

    val transition = rememberInfiniteTransition(label = "core")

    // Slow drift when idle, fast spin while thinking: motion itself is the "working" signal.
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    AgentState.THINKING -> 7_000
                    AgentState.LISTENING, AgentState.HEARING, AgentState.SPEAKING -> 14_000
                    else -> 40_000
                },
                easing = LinearEasing
            )
        ),
        label = "spin"
    )

    // Breathing scale: a steady swell, the "alive but idle" cue.
    val breath by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // Radar sweep across the collar, always running.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_400, easing = LinearEasing)),
        label = "sweep"
    )

    // Expanding shock front while SEEMA is talking.
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "pulse"
    )

    Canvas(modifier = modifier) {
        val unit = 1.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val base = size.minDimension * 0.5f
        // 0.56 puts the constellation at ~56% of the short edge; the collar then reaches ~75%.
        val radius = base * 0.56f * breath * (1f + level * 0.10f)

        // ---- ambient halo: four cheap concentric circles instead of one gradient shader
        val haloAlpha = 0.030f + level * 0.035f
        for (i in 5 downTo 1) {
            drawCircle(
                color = color.copy(alpha = haloAlpha),
                radius = radius * (1f + i * 0.46f),
                center = Offset(cx, cy)
            )
        }

        // ---- speaking shock fronts
        if (state == AgentState.SPEAKING) {
            val p = pulse
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.40f),
                radius = radius * (1.02f + p * 0.75f),
                center = Offset(cx, cy),
                style = Stroke(width = 1.4f * unit)
            )
        }

        // ---- instrument collar: 60 ticks, every fifth one long
        val collarRadius = radius * 1.26f
        for (i in 0 until 60) {
            val a = (i * 6f - 90f) * (PI.toFloat() / 180f)
            val long = i % 5 == 0
            val inner = collarRadius + if (long) 0f else 1.2f * unit
            val outer = collarRadius + if (long) 7.5f * unit else 3.5f * unit
            drawLine(
                color = color.copy(alpha = if (long) 0.65f else 0.20f),
                start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
                end = Offset(cx + cos(a) * outer, cy + sin(a) * outer),
                strokeWidth = if (long) 1.6f * unit else 0.9f * unit
            )
        }

        // ---- radar sweep on the collar
        rotate(degrees = sweep * 360f, pivot = Offset(cx, cy)) {
            drawArc(
                color = color.copy(alpha = 0.38f),
                startAngle = 0f,
                sweepAngle = 48f,
                useCenter = false,
                topLeft = Offset(cx - collarRadius, cy - collarRadius),
                size = Size(collarRadius * 2f, collarRadius * 2f),
                style = Stroke(width = 1.8f * unit)
            )
        }

        // ---- three tilted orbital rings
        val ringRadii = floatArrayOf(1.12f, 0.94f, 0.76f)
        val ringTilts = floatArrayOf(-24f, 38f, 12f)
        ringRadii.forEachIndexed { index, factor ->
            val r = radius * factor
            rotate(
                degrees = ringTilts[index] + spin * (if (index % 2 == 0) 0.35f else -0.22f),
                pivot = Offset(cx, cy)
            ) {
                drawOval(
                    color = color.copy(alpha = 0.20f + index * 0.06f),
                    topLeft = Offset(cx - r, cy - r * 0.42f),
                    size = Size(r * 2f, r * 0.84f),
                    style = Stroke(width = 1.1f * unit)
                )
            }
        }

        // ---- the constellation itself
        val rad = spin * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        CORE_NODES.forEach { node ->
            val rx = node.x * cosA - node.z * sinA
            val rz = node.x * sinA + node.z * cosA
            // Perspective: points nearer the viewer sit further out and brighter.
            val perspective = 1f / (1.55f - rz * 0.5f)
            val depth = (rz + 1f) * 0.5f
            val px = cx + rx * radius * perspective
            val py = cy + node.y * radius * perspective * breath
            val deform = 1f + level * 0.20f * depth
            drawCircle(
                color = color.copy(alpha = 0.12f + depth * 0.80f),
                radius = (0.7f + depth * 2.0f) * unit * deform,
                center = Offset(px, py)
            )
        }

        // ---- hot centre: three flat rings read as a glow without a shader
        drawCircle(color.copy(alpha = 0.16f), radius = radius * 0.24f, center = Offset(cx, cy))
        drawCircle(color.copy(alpha = 0.22f), radius = radius * 0.14f, center = Offset(cx, cy))
        drawCircle(
            color = color.copy(alpha = 0.90f),
            radius = 2.2f * unit + level * 3.0f * unit,
            center = Offset(cx, cy)
        )

        // ---- outer vignette ring so the core does not bleed into the transcript
        drawCircle(
            color = Color.Black.copy(alpha = 0.0f),
            radius = radius * 1.6f,
            center = Offset(cx, cy)
        )
    }
}