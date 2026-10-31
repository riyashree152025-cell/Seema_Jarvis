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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.seema.assistant.AgentState
import kotlin.math.PI
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
    val count = 120
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
 * It is not a progress spinner.  The rotation speed, colour, breath rate and ring behaviour
 * all encode the agent state, and the audio level physically deforms the constellation while
 * the user speaks, so the screen reacts in the same frame as the voice.
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

    // Breathing scale: a steady 0.92-1.08 swell, the "alive but idle" cue.
    val breath by transition.animateFloat(
        initialValue = 0.93f,
        targetValue = 1.07f,
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
        val radius = base * 0.36f * breath * (1f + level * 0.10f)

        // ---- ambient glow: the light the core throws onto the glass
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.20f + level * 0.18f),
                    color.copy(alpha = 0.05f),
                    androidx.compose.ui.graphics.Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius * 2.9f
            ),
            radius = radius * 2.9f,
            center = Offset(cx, cy)
        )

        // ---- speaking shock fronts
        if (state == AgentState.SPEAKING) {
            val p = pulse
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.35f),
                radius = radius * (1.05f + p * 0.85f),
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f * unit)
            )
        }

        // ---- instrument collar: 72 ticks, every sixth one long
        val collarRadius = radius * 1.34f
        for (i in 0 until 72) {
            val a = (i * 5f - 90f) * (PI.toFloat() / 180f)
            val long = i % 6 == 0
            val inner = collarRadius + if (long) 0f else 0.9f * unit
            val outer = collarRadius + if (long) 4.6f * unit else 2.2f * unit
            drawLine(
                color = color.copy(alpha = if (long) 0.55f else 0.16f),
                start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
                end = Offset(cx + cos(a) * outer, cy + sin(a) * outer),
                strokeWidth = if (long) 1.4f * unit else 0.8f * unit
            )
        }

        // ---- radar sweep on the collar
        val sweepAngle = sweep * 360f
        rotate(degrees = sweepAngle, pivot = Offset(cx, cy)) {
            drawArc(
                color = color.copy(alpha = 0.30f),
                startAngle = 0f,
                sweepAngle = 46f,
                useCenter = false,
                topLeft = Offset(cx - collarRadius, cy - collarRadius),
                size = Size(collarRadius * 2f, collarRadius * 2f),
                style = Stroke(width = 1.4f * unit)
            )
        }

        // ---- three tilted orbital rings
        val ringRadii = floatArrayOf(1.16f, 1.00f, 0.82f)
        val ringTilts = floatArrayOf(-24f, 38f, 12f)
        ringRadii.forEachIndexed { index, factor ->
            val r = radius * factor
            rotate(degrees = ringTilts[index] + spin * (if (index % 2 == 0) 0.35f else -0.22f), pivot = Offset(cx, cy)) {
                drawOval(
                    color = color.copy(alpha = 0.14f + index * 0.05f),
                    topLeft = Offset(cx - r, cy - r * 0.42f),
                    size = Size(r * 2f, r * 0.84f),
                    style = Stroke(width = 0.9f * unit)
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
            // Audio deformation: louder speech pushes the shell outward.
            val deform = 1f + level * 0.22f * depth
            drawCircle(
                color = color.copy(alpha = 0.10f + depth * 0.78f),
                radius = (0.55f + depth * 1.55f) * unit * deform,
                center = Offset(px, py)
            )
        }

        // ---- hot centre
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.55f + level * 0.35f),
                    color.copy(alpha = 0.08f)
                ),
                center = Offset(cx, cy),
                radius = radius * 0.30f
            ),
            radius = radius * 0.30f,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = color.copy(alpha = 0.85f),
            radius = 1.6f * unit + level * 2.2f * unit,
            center = Offset(cx, cy)
        )
    }
}