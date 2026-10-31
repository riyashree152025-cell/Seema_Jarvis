package com.seema.assistant.ui.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.seema.assistant.AgentState
import com.seema.assistant.ui.components.VoiceSettingsDialog
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * The main interface.
 *
 * Design intent: a live instrument, not a dashboard.  Everything on screen is either a real
 * running value (clock, battery, link, audio level) or a real process (the core's state), and
 * the layout is built from hairlines and negative space rather than cards, so opening the app
 * reads as "a machine just came online" instead of "a phone app with a circle".
 */
@Composable
fun JarvisScreen(
    state: AgentState,
    lastResponse: String,
    audioLevel: Float,
    micGranted: Boolean,
    serviceRunning: Boolean,
    routineNames: List<String>,
    onGrantClick: () -> Unit,
    onTalk: () -> Unit,
    onBriefing: () -> Unit,
    onRunRoutine: (String) -> Unit,
    onAddTile: (() -> Unit)?,
    onAddWidget: (() -> Unit)?,
    onSendText: (String) -> Unit
) {
    var showBoot by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Jarvis.Void)) {
        Backdrop()
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Header(state = state, serviceRunning = serviceRunning)
            CoreStage(
                state = state,
                level = audioLevel,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Transcript(lastResponse = lastResponse)
            Chips(
                routineNames = routineNames,
                onBriefing = onBriefing,
                onRunRoutine = onRunRoutine,
                menuExpanded = showMenu,
                onMenuExpandedChange = { showMenu = it },
                onAddTile = onAddTile,
                onAddWidget = onAddWidget
            )
            Dock(
                state = state,
                micGranted = micGranted,
                onGrantClick = onGrantClick,
                onTalk = onTalk,
                onType = { showInput = true },
                onSettings = { showSettings = true }
            )
        }
        if (showBoot) BootSequence { showBoot = false }
        if (showSettings) VoiceSettingsDialog(onDismiss = { showSettings = false })
        if (showInput) {
            CommandInputDialog(
                onDismiss = { showInput = false },
                onSend = { text -> showInput = false; onSendText(text) }
            )
        }
    }
}

// --------------------------------------------------------------------------------- backdrop

/** Vignette + faint engineering grid + scanlines.  Cheap to draw, and it kills the "flat app" look. */
@Composable
private fun Backdrop() {
    // drawWithCache: the gradient shader and the grid are built once per size change instead
    // of once per frame. A per-frame Brush.radialGradient was the main source of the app's
    // allocation churn (the GC was running twice a second while the screen was open).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val background = Brush.radialGradient(
                    colors = listOf(Color(0xFF0B1B26), Jarvis.Void, Color(0xFF000000)),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.height * 0.78f
                )
                val step = 64.dp.toPx()
                onDrawBehind {
                    drawRect(background)
                    var x = 0f
                    while (x < size.width) {
                        drawLine(Jarvis.Hairline.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, size.height), 1f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Jarvis.Hairline.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), 1f)
                        y += step
                    }
                }
            }
    )
}

// ------------------------------------------------------------------------------------ header

@Composable
private fun Header(state: AgentState, serviceRunning: Boolean) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            delay(1000)
        }
    }
    val battery = rememberBattery()
    var link by remember { mutableStateOf(networkLabel(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            link = networkLabel(context)
            delay(5000)
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "SEEMA",
                        color = Jarvis.Signal,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 6.sp
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (serviceRunning) "CORE ONLINE" else "CORE OFFLINE",
                    color = if (serviceRunning) Jarvis.TextMuted else Jarvis.Danger.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.4.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = clock,
                    color = Jarvis.Gold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "BAT ${if (battery.first < 0) "--" else "${battery.first}%"}${if (battery.second) " +" else ""}  $link",
                    color = Jarvis.TextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp
                )
            }
        }
        Hairline()
    }
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Jarvis.Hairline, Color.Transparent)
                )
            )
    )
}

@Composable
private fun StatusDot(state: AgentState) {
    val color = state.jarvisColor()
    Canvas(modifier = Modifier.size(7.dp)) {
        drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension)
        drawCircle(color, radius = size.minDimension * 0.45f)
    }
}

// --------------------------------------------------------------------------------- core stage

@Composable
private fun CoreStage(state: AgentState, level: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        JarvisCore(
            state = state,
            level = level,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = state.jarvisLabel(),
                color = state.jarvisColor().copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 7.sp
            )
            Spacer(Modifier.height(10.dp))
            AudioBars(level = level, state = state)
        }
    }
}

/** Equalizer driven by the real microphone level: 32 bars, centre-weighted. */
@Composable
private fun AudioBars(level: Float, state: AgentState) {
    val color = state.jarvisColor()
    Canvas(modifier = Modifier.width(188.dp).height(18.dp)) {
        val bars = 32
        val gap = size.width / bars
        val mid = bars / 2f
        for (i in 0 until bars) {
            val distance = kotlin.math.abs(i - mid) / mid
            val shape = (1f - distance * 0.55f)
            val wobble = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((i * 0.7f) + level * 9f))
            val h = (size.height * (0.10f + level * shape * wobble)).coerceIn(1.2f, size.height)
            drawRect(
                color = color.copy(alpha = if (level < 0.02f) 0.22f else 0.55f + shape * 0.4f),
                topLeft = Offset(i * gap + gap * 0.25f, (size.height - h) / 2f),
                size = Size(gap * 0.5f, h)
            )
        }
    }
}

// ---------------------------------------------------------------------------------- transcript

@Composable
private fun Transcript(lastResponse: String) {
    val lines = remember(lastResponse) {
        lastResponse.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 8.dp)
            .height(72.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "SAY \"HEY JARVIS\" OR TAP TALK",
                color = Jarvis.TextMuted.copy(alpha = 0.55f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.2.sp
            )
        } else {
            lines.forEach { line ->
                val isUser = line.startsWith("YOU:")
                Text(
                    text = line.removePrefix("YOU:").removePrefix("SEEMA:").trim(),
                    color = if (isUser) Jarvis.Gold.copy(alpha = 0.72f) else Jarvis.TextPrimary,
                    fontSize = if (isUser) 11.sp else 13.sp,
                    fontFamily = if (isUser) FontFamily.Monospace else FontFamily.Default,
                    lineHeight = 18.sp,
                    maxLines = 2
                )
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

// -------------------------------------------------------------------------------- chips + dock

@Composable
private fun Chips(
    routineNames: List<String>,
    onBriefing: () -> Unit,
    onRunRoutine: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onAddTile: (() -> Unit)?,
    onAddWidget: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chip(label = "BRIEFING", onClick = onBriefing)
        routineNames.take(2).forEach { name ->
            Chip(label = name.uppercase().take(10), onClick = { onRunRoutine(name) })
        }
        Box {
            Chip(label = "···", onClick = { onMenuExpandedChange(true) })
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                if (onAddTile != null) {
                    DropdownMenuItem(text = { Text("Add Quick Settings tile", fontSize = 13.sp) }, onClick = { onMenuExpandedChange(false); onAddTile() })
                }
                if (onAddWidget != null) {
                    DropdownMenuItem(text = { Text("Add home screen widget", fontSize = 13.sp) }, onClick = { onMenuExpandedChange(false); onAddWidget() })
                }
                DropdownMenuItem(text = { Text("Daily briefing", fontSize = 13.sp) }, onClick = { onMenuExpandedChange(false); onBriefing() })
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Jarvis.TextMuted,
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, Jarvis.Hairline, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun Dock(
    state: AgentState,
    micGranted: Boolean,
    onGrantClick: () -> Unit,
    onTalk: () -> Unit,
    onType: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 18.dp, bottom = 26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockGlyph(kind = Glyph.GEAR, onClick = onSettings)
        TalkButton(
            state = state,
            micGranted = micGranted,
            onClick = if (micGranted) onTalk else onGrantClick
        )
        DockGlyph(kind = Glyph.KEYBOARD, onClick = onType)
    }
}

private enum class Glyph { GEAR, KEYBOARD }

@Composable
private fun DockGlyph(kind: Glyph, onClick: () -> Unit) {
    val color = Jarvis.TextMuted
    Canvas(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(11.dp)
    ) {
        val u = 1.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        when (kind) {
            Glyph.GEAR -> {
                drawCircle(color, radius = r * 0.42f, center = c, style = Stroke(1.2f * u))
                for (i in 0 until 8) {
                    val a = (i * 45f) * (Math.PI.toFloat() / 180f)
                    drawLine(
                        color,
                        Offset(c.x + kotlin.math.cos(a) * r * 0.62f, c.y + kotlin.math.sin(a) * r * 0.62f),
                        Offset(c.x + kotlin.math.cos(a) * r * 0.92f, c.y + kotlin.math.sin(a) * r * 0.92f),
                        1.2f * u
                    )
                }
            }
            Glyph.KEYBOARD -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.45f),
                    size = Size(r * 1.7f, r * 0.9f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
                    style = Stroke(1.2f * u)
                )
                for (i in -1..1) {
                    drawCircle(color, radius = 0.8f * u, center = Offset(c.x + i * r * 0.42f, c.y - r * 0.1f))
                }
            }
        }
    }
}

@Composable
private fun TalkButton(state: AgentState, micGranted: Boolean, onClick: () -> Unit) {
    val color = if (micGranted) state.jarvisColor() else Jarvis.Amber
    val active = state == AgentState.LISTENING || state == AgentState.HEARING
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            // Cheap layered glow: three flat circles, no per-frame shader.
            for (i in 3 downTo 1) {
                drawCircle(
                    color = color.copy(alpha = if (active) 0.055f else 0.028f),
                    radius = r * (1f - i * 0.12f),
                    center = c
                )
            }
            drawCircle(color.copy(alpha = 0.7f), radius = r * 0.94f, center = c, style = Stroke(1.2.dp.toPx()))
            drawCircle(color.copy(alpha = 0.25f), radius = r * 0.80f, center = c, style = Stroke(0.8.dp.toPx()))
        }
        Text(
            text = if (!micGranted) "GRANT" else "TALK",
            color = color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ----------------------------------------------------------------------------------- dialogs

@Composable
private fun CommandInputDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF071018))
                .border(1.dp, Jarvis.Hairline, RoundedCornerShape(6.dp))
                .padding(18.dp)
        ) {
            Text(
                "TYPE A COMMAND",
                color = Jarvis.Signal,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.4.sp
            )
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Jarvis.TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(Jarvis.Signal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text.trim()) }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Jarvis.Hairline))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CANCEL",
                    color = Jarvis.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(8.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "SEND",
                    color = Jarvis.Signal,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { if (text.isNotBlank()) onSend(text.trim()) }
                        .padding(8.dp)
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------- boot screen

/**
 * Power-on sequence.  Deliberately short (2.6 s) and skippable by tapping: it exists to make
 * the first frame feel like hardware coming up, not to delay the user.
 */
@Composable
private fun BootSequence(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(2_600, easing = LinearEasing))
        onFinished()
    }
    val p = progress.value
    val fadeOut = (1f - ((p - 0.88f) / 0.12f)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier.fillMaxSize().background(Jarvis.Void).alpha(fadeOut),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val span = size.minDimension * 0.28f
            when {
                p < 0.22f -> {
                    val half = span * (p / 0.22f)
                    drawLine(Jarvis.Signal, Offset(cx - half, cy), Offset(cx + half, cy), 1.6.dp.toPx())
                }
                p < 0.55f -> {
                    val t = (p - 0.22f) / 0.33f
                    val r = span * t
                    drawCircle(Jarvis.Signal.copy(alpha = 0.9f), r, Offset(cx, cy), style = Stroke(1.4.dp.toPx()))
                    drawCircle(Jarvis.Signal.copy(alpha = 0.35f), r * 1.18f, Offset(cx, cy), style = Stroke(0.8.dp.toPx()))
                }
                else -> {
                    val r = span
                    drawCircle(Jarvis.Signal.copy(alpha = 0.55f), r, Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                    for (i in 0 until 48) {
                        val a = (i * 7.5f) * (Math.PI.toFloat() / 180f)
                        drawLine(
                            Jarvis.Signal.copy(alpha = 0.22f),
                            Offset(cx + kotlin.math.cos(a) * r * 1.06f, cy + kotlin.math.sin(a) * r * 1.06f),
                            Offset(cx + kotlin.math.cos(a) * r * 1.14f, cy + kotlin.math.sin(a) * r * 1.14f),
                            1f
                        )
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val titleAlpha = ((p - 0.42f) / 0.18f).coerceIn(0f, 1f)
            Text(
                text = "S E E M A",
                color = Jarvis.Signal.copy(alpha = titleAlpha),
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 10.sp
            )
            Spacer(Modifier.height(14.dp))
            val subAlpha = ((p - 0.62f) / 0.18f).coerceIn(0f, 1f)
            Text(
                text = "SYSTEM ONLINE",
                color = Jarvis.TextMuted.copy(alpha = subAlpha),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// -------------------------------------------------------------------------------- system data

private fun currentClock(): String {
    val t = LocalTime.now()
    return "${t.hour.two()}:${t.minute.two()}:${t.second.two()}"
}

@Composable
private fun rememberBattery(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level to charging
}

private fun networkLabel(context: Context): String = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    when {
        caps == null -> "OFFLINE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
        else -> "LINK"
    }
}.getOrDefault("OFFLINE")