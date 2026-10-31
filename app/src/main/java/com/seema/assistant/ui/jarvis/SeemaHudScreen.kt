package com.seema.assistant.ui.jarvis

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seema.assistant.AgentState
import com.seema.assistant.SeemaMemory
import com.seema.assistant.SeemaRoutines
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.delay

/** Which page the bottom navigation currently shows. HOME is the HUD itself and is not a nav item. */
private enum class Tab { HOME, SETTINGS, FILES, CODING, TERMINAL, PROFILE }

/**
 * Root of the new interface.
 *
 * The HUD (HOME) is always the surface the app opens on; the navigation rail opens management
 * pages over it and a "‹ CORE" affordance brings the user back, so the reactor is never more
 * than one tap away. Protocol switching only changes what HOME renders, never the chrome, which
 * keeps the three views feeling like modes of one machine rather than three separate apps.
 */
@Composable
fun SeemaHudScreen(
    state: AgentState,
    lastResponse: String,
    audioLevel: Float,
    serviceRunning: Boolean,
    micGranted: Boolean,
    routineNames: List<String>,
    onGrantClick: () -> Unit,
    onTalk: () -> Unit,
    onSendText: (String) -> Unit
) {
    val context = LocalContext.current
    val configState = remember { SeemaConfigState(context) }
    val config = configState.config

    var tab by remember { mutableStateOf(Tab.HOME) }
    var protocol by remember { mutableStateOf(HudProtocol.REACTOR) }
    val logs = remember { mutableStateListOf<String>() }

    // Transcript lines become the command log that the reactor card and MARK-III terminal read.
    LaunchedEffect(lastResponse) {
        val fresh = lastResponse.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (fresh.isEmpty()) return@LaunchedEffect
        fresh.takeLast(3).forEach { line ->
            val text = line.take(88)
            if (logs.lastOrNull() != text) logs.add(text)
        }
        while (logs.size > 16) logs.removeAt(0)
    }

    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockText()
            delay(1000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(HUD.Black)) {
        // Layer 0: the live code stream. It speeds up while the agent is thinking.
        CodeStreamBackground(
            fast = state == AgentState.THINKING,
            transparency = config.transparency,
            modifier = Modifier.fillMaxSize()
        )

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                tab = tab,
                protocol = protocol,
                clock = clock,
                state = state,
                onBack = { tab = Tab.HOME },
                onProtocol = { protocol = it }
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    Tab.HOME -> when (protocol) {
                        HudProtocol.REACTOR -> Box(Modifier.fillMaxSize()) {
                            ReactorHud(
                                state = state,
                                level = audioLevel,
                                logs = logs,
                                config = config,
                                modifier = Modifier.fillMaxSize()
                            )
                            TalkPill(
                                micGranted = micGranted,
                                onClick = if (micGranted) onTalk else onGrantClick,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp)
                            )
                        }
                        HudProtocol.TACTICAL -> TacticalOrbitScreen(
                            state = state,
                            logLines = logs,
                            modifier = Modifier.fillMaxSize()
                        )
                        HudProtocol.MATRIX -> MatrixTerminalScreen(
                            logLines = logs,
                            telemetry = telemetry(state, serviceRunning, routineNames.size),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Tab.SETTINGS -> SystemSettingsScreen(
                        config = config,
                        onSave = { configState.commit(it) },
                        onReset = { configState.reset() },
                        onClose = { tab = Tab.HOME },
                        modifier = Modifier.fillMaxSize()
                    )
                    Tab.FILES -> FilesPage(modifier = Modifier.fillMaxSize())
                    Tab.CODING -> CodingPage(config = config, state = state, modifier = Modifier.fillMaxSize())
                    Tab.TERMINAL -> TerminalPage(
                        logs = logs,
                        onSend = onSendText,
                        modifier = Modifier.fillMaxSize()
                    )
                    Tab.PROFILE -> ProfilePage(
                        state = state,
                        serviceRunning = serviceRunning,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            NavBar(
                selected = when (tab) {
                    Tab.SETTINGS -> NavGlyph.SETTINGS
                    Tab.FILES -> NavGlyph.FILES
                    Tab.CODING -> NavGlyph.CODING
                    Tab.TERMINAL -> NavGlyph.TERMINAL
                    Tab.PROFILE -> NavGlyph.PROFILE
                    Tab.HOME -> null
                },
                onSelect = { glyph ->
                    tab = when (glyph) {
                        NavGlyph.SETTINGS -> Tab.SETTINGS
                        NavGlyph.FILES -> Tab.FILES
                        NavGlyph.CODING -> Tab.CODING
                        NavGlyph.TERMINAL -> Tab.TERMINAL
                        NavGlyph.PROFILE -> Tab.PROFILE
                    }
                }
            )
        }
    }
}

// -------------------------------------------------------------------------------------- chrome

@Composable
private fun TopBar(
    tab: Tab,
    protocol: HudProtocol,
    clock: String,
    state: AgentState,
    onBack: () -> Unit,
    onProtocol: (HudProtocol) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tab != Tab.HOME) {
                    Text(
                        text = "‹ CORE",
                        color = HUD.Blood,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable(onClick = onBack)
                            .padding(end = 12.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
                Text(
                    text = "SEEMA_JARVIS",
                    color = HUD.Blood,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.5.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = clock,
                    color = HUD.Gold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = protocol.title + " · " + protocol.subtitle,
                    color = HUD.TextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (tab == Tab.HOME) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudProtocol.entries.forEach { entry ->
                    ProtocolChip(
                        label = entry.title,
                        active = entry == protocol,
                        onClick = { onProtocol(entry) }
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = state.hudLabel(),
                    color = state.hudColor(),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HUD.Hairline))
    }
}

@Composable
private fun ProtocolChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) HUD.Black else HUD.BloodDim,
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) HUD.Blood else Color.Transparent)
            .border(1.dp, if (active) HUD.Blood else HUD.Hairline, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun TalkPill(micGranted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(HUD.Blood.copy(alpha = 0.16f))
            .border(1.dp, HUD.Blood, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (micGranted) "TALK" else "GRANT",
            color = HUD.Blood,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp
        )
    }
}

// ---------------------------------------------------------------------------------------- pages

@Composable
private fun FilesPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val files = remember {
        (context.filesDir?.listFiles()?.toList().orEmpty() + context.getDir("hud", 0).listFiles()?.toList().orEmpty())
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
            .take(14)
    }
    PageScaffold(title = "FILE VAULT", subtitle = "${files.size} OBJECTS IN LOCAL STORE", modifier = modifier) {
        if (files.isEmpty()) {
            EmptyLine("no local objects yet")
        } else {
            files.forEach { file -> FileRow(file) }
        }
    }
}

@Composable
private fun FileRow(file: File) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = file.name,
            color = HUD.TextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = humanSize(file.length()),
            color = HUD.TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CodingPage(config: SeemaConfig, state: AgentState, modifier: Modifier = Modifier) {
    PageScaffold(title = "CODING AGENT", subtitle = "AUTONOMOUS BUILD PIPELINE", modifier = modifier) {
        StatusRow("WEB SEARCH", if (config.webSearch) "ENABLED" else "DISABLED", config.webSearch)
        StatusRow("CODE GENERATION", if (config.codeGeneration) "HTML / KOTLIN" else "OFF", config.codeGeneration)
        StatusRow("DEPLOYMENT", config.deployment.uppercase(), config.deployment == SeemaConfig.DEPLOY_AUTO)
        StatusRow("AGENT STATE", state.hudLabel(), state != AgentState.IDLE)
        Spacer(Modifier.height(16.dp))
        SectionLabel("RECENT BUILD LOG")
        listOf(
            "gradle assembleDebug .......... BUILD SUCCESSFUL",
            "kotlinc compile ................ 0 errors",
            "hud resources merged ........... 12 files",
            "deploy target .................. manual approval"
        ).forEach { line ->
            Text(
                text = line,
                color = HUD.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun TerminalPage(
    logs: List<String>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    PageScaffold(title = "COMMAND TERMINAL", subtitle = "DIRECT LINK TO CORE", modifier = modifier) {
        Column(Modifier.fillMaxWidth().height(210.dp).verticalScroll(rememberScrollState())) {
            if (logs.isEmpty()) {
                Text(
                    "› awaiting first command",
                    color = HUD.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            logs.forEach { line ->
                Text(
                    text = "› $line",
                    color = HUD.TextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HUD.Hairline))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, HUD.Hairline, RoundedCornerShape(3.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("›", color = HUD.Blood, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = HUD.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(HUD.Blood),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) {
                        onSend(input.trim())
                        input = ""
                    }
                }),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "TYPE A COMMAND AND PRESS ENTER",
            color = HUD.TextMuted.copy(alpha = 0.6f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp
        )
    }
}

@Composable
private fun ProfilePage(state: AgentState, serviceRunning: Boolean, modifier: Modifier = Modifier) {
    val memories = remember { SeemaMemory.all().size }
    val routines = remember { SeemaRoutines.all().size }
    PageScaffold(title = "OPERATOR PROFILE", subtitle = "PERSONAL NODE", modifier = modifier) {
        StatusRow("NODE", if (serviceRunning) "ONLINE" else "OFFLINE", serviceRunning)
        StatusRow("MEMORY BANK", "$memories FACTS", memories > 0)
        StatusRow("ROUTINES", "$routines ARMED", routines > 0)
        StatusRow("CORE STATE", state.hudLabel(), state != AgentState.IDLE)
        Spacer(Modifier.height(18.dp))
        SectionLabel("CLEARANCE")
        Text(
            text = "OWNER · FULL ACCESS\nLOCAL STORE ONLY · NO CLOUD SYNC",
            color = HUD.TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PageScaffold(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        SectionLabel(title)
        Text(
            text = subtitle,
            color = HUD.TextMuted,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.8.sp
        )
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = HUD.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
        Text(
            value,
            color = if (ok) HUD.Online else HUD.BloodDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = HUD.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

// ---------------------------------------------------------------------------------- utilities

private fun clockText(): String {
    val t = LocalTime.now()
    return "${t.hour.pad2()}:${t.minute.pad2()}:${t.second.pad2()}"
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun telemetry(state: AgentState, running: Boolean, routines: Int): List<Pair<String, String>> = listOf(
    "CORE" to if (running) "ONLINE" else "OFFLINE",
    "STATE" to state.hudLabel(),
    "PROTOCOL" to "MARK-III",
    "ROUTINES" to routines.toString(),
    "LINK" to "SECURE",
    "MEM" to "LOCAL",
    "UPTIME" to "STABLE"
)