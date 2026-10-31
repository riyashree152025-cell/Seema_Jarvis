package com.seema.assistant.ui.jarvis

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seema.assistant.AgentState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MARK-II: tactical orbit.
 *
 * A wireframe planet with a live telemetry column. The sphere is built from latitude and
 * longitude rings that are rotated around the Y axis and projected with a flattened Y scale, so
 * it reads as a tilted globe rather than a circle. Every orbit point is generated from the same
 * two loops each frame; the only state carried between frames is the rotation angle.
 */
@Composable
fun TacticalOrbitScreen(
    state: AgentState,
    logLines: List<String>,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(state.hudColor(), tween(600), label = "orbit")
    val transition = rememberInfiniteTransition(label = "orbit")
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (state == AgentState.THINKING) 9_000 else 36_000, easing = LinearEasing)),
        label = "spin"
    )
    val feed by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "feed"
    )

    Column(modifier = modifier.fillMaxSize()) {
        Header()
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            GlobeCanvas(spin = spin, color = color, modifier = Modifier.fillMaxSize())
            TelemetryColumn(
                feed = feed,
                state = state,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp)
            )
        }
        OrbitLog(logLines = logLines)
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("MARK-II", color = HUD.BloodDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
        Text("TACTICAL ORBIT", color = HUD.BloodDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
    }
}

@Composable
private fun GlobeCanvas(spin: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = 1.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.34f
        val tilt = 0.52f
        val rad = spin * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)

        fun project(latDeg: Float, lonDeg: Float): Triple<Float, Float, Float> {
            val lat = latDeg * (PI.toFloat() / 180f)
            val lon = lonDeg * (PI.toFloat() / 180f)
            val x = cos(lat) * cos(lon)
            val y = sin(lat)
            val z = cos(lat) * sin(lon)
            val rx = x * cosA - z * sinA
            val rz = x * sinA + z * cosA
            return Triple(cx + rx * radius, cy + y * radius * tilt, rz)
        }

        // ---- atmosphere halo
        for (i in 4 downTo 1) {
            drawCircle(color.copy(alpha = 0.020f), radius = radius * (1f + i * 0.18f), center = Offset(cx, cy))
        }

        // ---- latitude rings
        for (lat in -60..60 step 30) {
            var previous: Triple<Float, Float, Float>? = null
            var lon = 0f
            while (lon <= 360f) {
                val p = project(lat.toFloat(), lon)
                previous?.let { q ->
                    val depth = ((p.third + q.third) / 2f + 1f) / 2f
                    drawLine(
                        color = color.copy(alpha = 0.10f + depth * 0.45f),
                        start = Offset(q.first, q.second),
                        end = Offset(p.first, p.second),
                        strokeWidth = (0.7f + depth * 0.9f) * u
                    )
                }
                previous = p
                lon += 12f
            }
        }

        // ---- longitude rings
        for (lon in 0 until 360 step 30) {
            var previous: Triple<Float, Float, Float>? = null
            var lat = -90f
            while (lat <= 90f) {
                val p = project(lat, lon.toFloat())
                previous?.let { q ->
                    val depth = ((p.third + q.third) / 2f + 1f) / 2f
                    drawLine(
                        color = color.copy(alpha = 0.08f + depth * 0.35f),
                        start = Offset(q.first, q.second),
                        end = Offset(p.first, p.second),
                        strokeWidth = (0.6f + depth * 0.8f) * u
                    )
                }
                previous = p
                lat += 12f
            }
        }

        // ---- horizon ring
        drawCircle(
            color = color.copy(alpha = 0.28f),
            radius = radius * 1.10f,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(0.9f * u)
        )

        // ---- target brackets on the sphere
        val b = radius * 1.24f
        val arm = radius * 0.12f
        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
            drawLine(
                color.copy(alpha = 0.45f),
                Offset(cx + sx * b, cy + sy * b),
                Offset(cx + sx * (b - arm), cy + sy * b),
                1.2f * u
            )
            drawLine(
                color.copy(alpha = 0.45f),
                Offset(cx + sx * b, cy + sy * b),
                Offset(cx + sx * b, cy + sy * (b - arm)),
                1.2f * u
            )
        }
    }
}

/** Live readouts that refresh with the animation clock rather than with the model. */
@Composable
private fun TelemetryColumn(feed: Float, state: AgentState, modifier: Modifier = Modifier) {
    val rows = listOf(
        "ORBIT" to (342 + (feed * 99).toInt()).toString().padStart(4, '0'),
        "SIG" to "${86 + (feed * 12).toInt()}%",
        "LAT" to "25.5941 N",
        "LON" to "85.1376 E",
        "LINK" to "SECURE",
        "TARGET" to "${1 + (feed * 7).toInt()}".padStart(2, '0')
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        rows.forEach { (label, value) ->
            Text(
                text = "$label  $value",
                color = HUD.TextMuted,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.hudLabel(),
            color = state.hudColor(),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp
        )
    }
}

@Composable
private fun OrbitLog(logLines: List<String>) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth().height(52.dp), verticalArrangement = Arrangement.Bottom) {
            val visible = if (logLines.isEmpty()) listOf("no uplink traffic") else logLines.takeLast(3)
            visible.forEach { line ->
                Text(
                    text = "› $line",
                    color = HUD.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}