package com.seema.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.ThinkingAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D wireframe globe: parallels, meridians and glowing grid dots, projected with sine/cosine,
 * spinning on its Y axis with a slight tilt. The far side is drawn faintly for depth.
 * Tapping toggles [locked]: the globe turns amber and a tactical target-lock overlay appears.
 * Otherwise it is drawn in [baseColor] (SEEMA's current state colour).
 */
@Composable
fun WireframeGlobe(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    baseColor: Color = JarvisCyan
) {
    val transition = rememberInfiniteTransition(label = "globe")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(ROTATION_MS, easing = LinearEasing)),
        label = "globeRotation"
    )
    val lockSpin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(LOCK_SPIN_MS, easing = LinearEasing)),
        label = "lockSpin"
    )
    val color by animateColorAsState(if (locked) ThinkingAmber else baseColor, tween(500), label = "globeColor")
    val geometry = remember { GlobeGeometry() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawGlobe(geometry, rotation, color)
            if (locked) drawTargetLock(color, lockSpin)
        }
        if (locked) {
            Text(
                text = "TARGET LOCKED",
                style = MaterialTheme.typography.labelLarge,
                color = color,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
            )
        }
    }
}

/** Precomputed trigonometry and reusable drawing buffers, so frames allocate almost nothing. */
private class GlobeGeometry {
    private fun radians(degrees: IntProgression) = degrees.map { (it * PI / 180).toFloat() }

    // Parallels (latitude lines), each sampled around the full circle
    val parallelCos = radians(-75..75 step 15).map { cos(it) }.toFloatArray()
    val parallelSin = radians(-75..75 step 15).map { sin(it) }.toFloatArray()
    val lonSamples = radians(0 until 360 step 6).toFloatArray()

    // Meridians (longitude lines), each sampled pole to pole
    val meridians = radians(0 until 360 step 15).toFloatArray()
    val latSampleCos = radians(-90..90 step 6).map { cos(it) }.toFloatArray()
    val latSampleSin = radians(-90..90 step 6).map { sin(it) }.toFloatArray()

    // Glowing dots on a coarser grid
    val dotLatCos = radians(-60..60 step 30).map { cos(it) }.toFloatArray()
    val dotLatSin = radians(-60..60 step 30).map { sin(it) }.toFloatArray()
    val dotLons = radians(0 until 360 step 30).toFloatArray()

    val front = Path()
    val back = Path()
    val frontDots = ArrayList<Offset>(dotLatCos.size * dotLons.size)

    /** Projected x, y (both -1..1, y up) and depth z (> 0 faces the viewer). */
    val point = FloatArray(3)

    fun project(cosLat: Float, sinLat: Float, lon: Float) {
        val x = cosLat * sin(lon)
        val y = sinLat
        val z = cosLat * cos(lon)
        // Tilt towards the viewer around the X axis so the north pole is visible.
        point[0] = x
        point[1] = y * COS_TILT - z * SIN_TILT
        point[2] = y * SIN_TILT + z * COS_TILT
    }
}

private fun DrawScope.drawGlobe(g: GlobeGeometry, rotation: Float, color: Color) {
    val c = center
    val r = size.minDimension / 2f * GLOBE_FRACTION

    // Atmosphere glow and silhouette
    drawCircle(
        brush = Brush.radialGradient(
            0.75f to color.copy(alpha = 0.14f),
            1f to Color.Transparent,
            center = c,
            radius = r * 1.25f
        ),
        radius = r * 1.25f,
        center = c
    )
    drawCircle(color.copy(alpha = 0.4f), radius = r, center = c, style = Stroke(1.dp.toPx()))

    g.front.reset()
    g.back.reset()

    // Adds one projected polyline, splitting it between the front and back paths by depth.
    fun addLine(count: Int, project: (Int) -> Unit) {
        var prevX = 0f
        var prevY = 0f
        var prevZ = 0f
        var current: Path? = null
        for (i in 0..count) {
            project(i)
            val x = c.x + g.point[0] * r
            val y = c.y - g.point[1] * r
            val z = g.point[2]
            if (i > 0) {
                val path = if (prevZ + z >= 0f) g.front else g.back
                if (path !== current) {
                    path.moveTo(prevX, prevY)
                    current = path
                }
                path.lineTo(x, y)
            }
            prevX = x
            prevY = y
            prevZ = z
        }
    }

    for (p in g.parallelCos.indices) {
        addLine(g.lonSamples.size) { i ->
            g.project(g.parallelCos[p], g.parallelSin[p], g.lonSamples[i % g.lonSamples.size] + rotation)
        }
    }
    for (meridian in g.meridians) {
        addLine(g.latSampleCos.size - 1) { i ->
            g.project(g.latSampleCos[i], g.latSampleSin[i], meridian + rotation)
        }
    }

    drawPath(g.back, color.copy(alpha = 0.12f), style = Stroke(1.dp.toPx()))
    drawPath(g.front, color.copy(alpha = 0.6f), style = Stroke(1.2.dp.toPx()))

    // Dots only on the visible hemisphere: a soft glow under a bright point
    g.frontDots.clear()
    for (lat in g.dotLatCos.indices) {
        for (lon in g.dotLons) {
            g.project(g.dotLatCos[lat], g.dotLatSin[lat], lon + rotation)
            if (g.point[2] > 0f) g.frontDots += Offset(c.x + g.point[0] * r, c.y - g.point[1] * r)
        }
    }
    drawPoints(g.frontDots, PointMode.Points, color.copy(alpha = 0.25f), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
    drawPoints(g.frontDots, PointMode.Points, color, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
}

/** Corner brackets, a spinning dashed ring and a crosshair over the globe. */
private fun DrawScope.drawTargetLock(color: Color, spin: Float) {
    val c = center
    val r = size.minDimension / 2f * GLOBE_FRACTION
    val stroke = 2.dp.toPx()

    rotate(spin, c) {
        drawCircle(
            color = color.copy(alpha = 0.8f),
            radius = r * 1.1f,
            center = c,
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 12.dp.toPx())))
        )
    }

    val half = r * 1.2f
    val arm = r * 0.25f
    for (sx in SIGNS) {
        for (sy in SIGNS) {
            val corner = Offset(c.x + sx * half, c.y + sy * half)
            drawLine(color, corner, Offset(corner.x - sx * arm, corner.y), stroke)
            drawLine(color, corner, Offset(corner.x, corner.y - sy * arm), stroke)
        }
    }

    val gap = r * 0.15f
    val reach = r * 1.2f
    drawLine(color, Offset(c.x - reach, c.y), Offset(c.x - gap, c.y), stroke)
    drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + reach, c.y), stroke)
    drawLine(color, Offset(c.x, c.y - reach), Offset(c.x, c.y - gap), stroke)
    drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, c.y + reach), stroke)
    drawCircle(color, radius = 4.dp.toPx(), center = c)
}

private const val TWO_PI = (2 * PI).toFloat()
private const val ROTATION_MS = 20_000
private const val LOCK_SPIN_MS = 6_000
private const val GLOBE_FRACTION = 0.78f
private val SIN_TILT = sin(0.35f)
private val COS_TILT = cos(0.35f)
private val SIGNS = floatArrayOf(-1f, 1f)
