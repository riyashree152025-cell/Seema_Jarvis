package com.seema.jarvis.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seema.jarvis.ui.components.GlassPanel
import com.seema.jarvis.ui.components.SciFiRadar
import com.seema.jarvis.ui.components.WireframeGlobe
import com.seema.jarvis.ui.theme.DeepSpace
import com.seema.jarvis.ui.theme.GridLine
import com.seema.jarvis.ui.theme.NeonCyan
import com.seema.jarvis.ui.theme.SeemaJarvisTheme
import kotlinx.coroutines.delay

/**
 * The SEEMA HUD: radar top-left, rotating wireframe globe in the centre, and glass panels
 * (top-right and bottom) ready for live data.
 */
@Composable
fun HudScreen() {
    var scanning by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }

    // A scan runs for a few seconds, then the radar settles back.
    LaunchedEffect(scanning) {
        if (scanning) {
            delay(SCAN_DURATION_MS)
            scanning = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
            .drawBehind { drawHudBackdrop() }
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val globeSize = minOf(maxWidth, maxHeight * 0.55f)
            WireframeGlobe(
                locked = locked,
                onClick = { locked = !locked },
                modifier = Modifier.size(globeSize).align(Alignment.Center)
            )
        }

        GlassPanel(
            title = "SEEMA // SYSTEM",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.46f)
                .height(150.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassPanel(title = "COMMS", modifier = Modifier.weight(1f).fillMaxHeight())
            GlassPanel(title = "TELEMETRY", modifier = Modifier.weight(1f).fillMaxHeight())
        }

        // Last, so the expanded radar draws over the panels and the globe.
        SciFiRadar(
            scanning = scanning,
            onClick = { scanning = !scanning },
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

/** Faint tactical grid plus corner brackets framing the screen. */
private fun DrawScope.drawHudBackdrop() {
    val step = 32.dp.toPx()
    val thin = 1.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), thin)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(GridLine, Offset(0f, y), Offset(size.width, y), thin)
        y += step
    }

    val inset = 10.dp.toPx()
    val arm = 26.dp.toPx()
    val bracket = NeonCyan.copy(alpha = 0.6f)
    val width = 2.dp.toPx()
    for ((corner, dir) in listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(size.width - inset, inset) to Offset(-1f, 1f),
        Offset(inset, size.height - inset) to Offset(1f, -1f),
        Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f)
    )) {
        drawLine(bracket, corner, Offset(corner.x + arm * dir.x, corner.y), width)
        drawLine(bracket, corner, Offset(corner.x, corner.y + arm * dir.y), width)
    }
}

private const val SCAN_DURATION_MS = 4_000L

@Preview(
    name = "SEEMA HUD",
    showBackground = true,
    backgroundColor = 0xFF050A14,
    device = "spec:width=411dp,height=891dp,dpi=420"
)
@Composable
private fun HudScreenPreview() {
    SeemaJarvisTheme { HudScreen() }
}
