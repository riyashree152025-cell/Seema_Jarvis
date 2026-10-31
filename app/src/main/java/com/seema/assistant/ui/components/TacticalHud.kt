package com.seema.assistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seema.assistant.AgentState
import com.seema.assistant.ui.theme.AlertRed
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.SpeakingGreen
import com.seema.assistant.ui.theme.TextSecondary
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Tactical view: radar top-left, 3D wireframe globe in the centre, glass panels with live
 * data. The globe takes SEEMA's state colour, and the radar scans while she is hearing you.
 */
@Composable
fun TacticalHud(state: AgentState, modifier: Modifier = Modifier) {
    val hud = rememberHudData()
    var tapScan by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }

    // A tapped scan runs for a few seconds, then the radar settles back.
    LaunchedEffect(tapScan) {
        if (tapScan) {
            delay(SCAN_DURATION_MS)
            tapScan = false
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val globeSize = minOf(maxWidth, maxHeight) * GLOBE_FRACTION
        WireframeGlobe(
            locked = locked,
            onClick = { locked = !locked },
            baseColor = state.reactorColor(),
            modifier = Modifier.size(globeSize).align(Alignment.Center)
        )

        GlassPanel(
            title = "SYSTEM",
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.46f)
        ) {
            HudLine("TIME", hud.now.format(TIME_FORMAT))
            HudLine(
                "POWER",
                hud.batteryPercent?.let { "$it%" + if (hud.charging) " ⚡" else "" } ?: "--",
                if (hud.charging) SpeakingGreen else JarvisCyan
            )
            HudLine(
                "NET",
                listOfNotNull(hud.network, hud.signalLevel?.let { signalBars(it) }).joinToString(" ").ifEmpty { "--" },
                if (hud.network == OFFLINE) AlertRed else JarvisCyan
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassPanel(title = "COMMS", modifier = Modifier.weight(1f)) {
                HudLine(
                    "MSGS",
                    hud.messageCount?.toString() ?: "--",
                    if ((hud.messageCount ?: 0) > 0) SpeakingGreen else JarvisCyan
                )
                HudLine("ALARM", hud.alarmTime ?: "NONE")
            }
            GlassPanel(title = "TELEMETRY", modifier = Modifier.weight(1f)) {
                HudLine("WX", hud.temperature ?: "--")
                HudLine("AUDIO", hud.mediaTitle ?: "NONE", if (hud.mediaPlaying) SpeakingGreen else JarvisCyan)
            }
        }

        // Last, so the expanded radar draws over the globe and the panels.
        SciFiRadar(
            scanning = tapScan || state == AgentState.HEARING,
            onClick = { tapScan = !tapScan },
            compactSize = RADAR_COMPACT,
            expandedSize = RADAR_EXPANDED,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

/** One "LABEL ......... VALUE" row inside a glass panel. */
@Composable
private fun HudLine(label: String, value: String, color: Color = JarvisCyan) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.width(6.dp).weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val SCAN_DURATION_MS = 4_000L
private const val GLOBE_FRACTION = 0.62f
private val RADAR_COMPACT = 110.dp
private val RADAR_EXPANDED = 170.dp
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
