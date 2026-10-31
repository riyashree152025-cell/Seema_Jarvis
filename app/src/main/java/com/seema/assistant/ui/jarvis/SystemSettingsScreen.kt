package com.seema.assistant.ui.jarvis

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seema.assistant.AiSettings
import com.seema.assistant.SeemaAccessibilityService
import com.seema.assistant.SeemaConfigBridge
import java.util.Locale

/**
 * Everything the user can reconfigure, laid out as a scrolling stack of instrument panes.
 *
 * Design intent: this is the maintenance hatch of the machine, not a phone settings list. It
 * keeps the same one-accent vocabulary as the HUD (hairlines, frosted panes, monospaced
 * telemetry) and avoids every Material control that would import a white surface or a rounded
 * pill into the middle of it: the switches are drawn by hand and the pickers are plain
 * dropdowns over a void-coloured popup.
 *
 * Edits are staged in [draft] and only leave the screen through SAVE, so flipping a switch can
 * never re-skin the live interface while the user is still deciding. RESET is delegated to the
 * caller; when the caller publishes the restored config, [LaunchedEffect] copies it back into
 * the draft so the controls snap to the new values.
 */
@Composable
fun SystemSettingsScreen(
    config: SeemaConfig,
    onSave: (SeemaConfig) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Local staging copy: every control below edits this, nothing else.
    var draft by remember(config) { mutableStateOf(config) }
    val context = LocalContext.current

    // External reset (or any other source of truth change) refreshes what the controls show.
    LaunchedEffect(config) { draft = config }

    // Built once and reused for every frame: a gradient created inside drawBehind would
    // allocate a shader per draw during scrolling.
    val backdrop = remember {
        Brush.verticalGradient(
            colors = listOf(
                HUD.Blood.copy(alpha = 0.22f),
                Color.Transparent,
                Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HUD.Black)
            .drawBehind { drawRect(brush = backdrop) }
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            HeaderRow(onClose = onClose)
            Spacer(Modifier.height(14.dp))

            HudGroup(title = "CORE AI ENGINE") {
                DropdownRow(
                    label = "AI Engine",
                    selected = draft.aiEngine,
                    options = SeemaConfig.ENGINES,
                    onSelect = { draft = draft.copy(aiEngine = it) }
                )
                Spacer(Modifier.height(10.dp))
                DropdownRow(
                    label = "Voice",
                    selected = draft.voiceEngine,
                    options = SeemaConfig.VOICES,
                    onSelect = { draft = draft.copy(voiceEngine = it) }
                )
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    label = "Local Brain (Ollama / Llama 3)",
                    checked = draft.localBrain,
                    onCheckedChange = { draft = draft.copy(localBrain = it) }
                )
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(12.dp))
                GroqKeyRow()
                ApiKeyRow()
            }

            Spacer(Modifier.height(12.dp))

            HudGroup(title = "VOICE & ACTIVATION") {
                ToggleRow(
                    label = "'Hey Jarvis' Wake Word",
                    checked = draft.wakeWord,
                    onCheckedChange = { draft = draft.copy(wakeWord = it) }
                )
                Spacer(Modifier.height(2.dp))
                SliderRow(
                    label = "Sensitivity",
                    value = draft.wakeSensitivity,
                    onValueChange = { draft = draft.copy(wakeSensitivity = it) }
                )
                Spacer(Modifier.height(2.dp))
                ToggleRow(
                    label = "Gesture Activation",
                    checked = draft.gestureActivation,
                    onCheckedChange = { draft = draft.copy(gestureActivation = it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            HudGroup(title = "MOBILE CONTROL") {
                ToggleRow(
                    label = "Deep Accessibility Access",
                    checked = draft.accessibilityAccess,
                    onCheckedChange = {
                        draft = draft.copy(accessibilityAccess = it)
                        if (it) SeemaAccessibilityService.openSettings(context)
                    }
                )
                ActionRow(label = "Open screen-access settings", action = "OPEN") {
                    SeemaAccessibilityService.openSettings(context)
                }
                ToggleRow(
                    label = "Screen Context Awareness",
                    checked = draft.screenContext,
                    onCheckedChange = { draft = draft.copy(screenContext = it) }
                )
                ToggleRow(
                    label = "Automation Scripts",
                    checked = draft.automationScripts,
                    onCheckedChange = { draft = draft.copy(automationScripts = it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            HudGroup(title = "CODING AGENT") {
                ToggleRow(
                    label = "Autonomous Web Search",
                    checked = draft.webSearch,
                    onCheckedChange = { draft = draft.copy(webSearch = it) }
                )
                ToggleRow(
                    label = "Code Generation (HTML / Kotlin)",
                    checked = draft.codeGeneration,
                    onCheckedChange = { draft = draft.copy(codeGeneration = it) }
                )
                Spacer(Modifier.height(10.dp))
                DropdownRow(
                    label = "Website Deployment",
                    selected = draft.deployment,
                    options = SeemaConfig.DEPLOYMENTS,
                    onSelect = { draft = draft.copy(deployment = it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            HudGroup(title = "INTERFACE THEME") {
                DropdownRow(
                    label = "Colour Profile",
                    selected = draft.colorProfile,
                    options = SeemaConfig.PROFILES,
                    onSelect = { draft = draft.copy(colorProfile = it) }
                )
                Spacer(Modifier.height(2.dp))
                SliderRow(
                    label = "Glow Intensity",
                    value = draft.glowIntensity,
                    onValueChange = { draft = draft.copy(glowIntensity = it) }
                )
                Spacer(Modifier.height(2.dp))
                SliderRow(
                    label = "Transparency",
                    value = draft.transparency,
                    onValueChange = { draft = draft.copy(transparency = it) }
                )
                Spacer(Modifier.height(2.dp))
                ToggleRow(
                    label = "Arc Reactor Animation (smooth)",
                    checked = draft.smoothReactor,
                    onCheckedChange = { draft = draft.copy(smoothReactor = it) }
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HudButton(
                    text = "RESET DEFAULTS",
                    filled = false,
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                )
                HudButton(
                    text = "SAVE CONFIGURATION",
                    filled = true,
                    onClick = { onSave(draft) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------

/** Back chevron, screen title and build stamp, sharing one baseline. */
@Composable
private fun HeaderRow(onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(color = HUD.Blood.copy(alpha = 0.30f)),
                    onClickLabel = "Close configuration",
                    role = Role.Button,
                    onClick = onClose
                )
                .semantics { contentDescription = "Close system configuration" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u2039",
                fontSize = 22.sp,
                fontFamily = HUD_FONT,
                color = HUD.BloodDim
            )
        }

        Spacer(Modifier.width(6.dp))

        Text(
            text = "SYSTEM CONFIGURATION",
            fontSize = 17.sp,
            fontFamily = HUD_FONT,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = HUD.TextPrimary,
            maxLines = 1
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "v1.0",
            fontSize = 12.sp,
            fontFamily = HUD_FONT,
            letterSpacing = 1.sp,
            color = HUD.TextMuted
        )
    }
}

/**
 * Frosted pane with the section caption and its hairline. The border is what makes the fill read
 * as an edge-lit pane; the fill alone is nearly invisible over true black.
 */
@Composable
private fun HudGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(HUD.Glass)
            .border(1.dp, HUD.GlassEdge, RoundedCornerShape(4.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontFamily = HUD_FONT,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            color = HUD.BloodDim,
            maxLines = 1
        )
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HUD.Hairline)
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

/** Label on the left, ON/OFF telemetry on the right, hand-drawn toggle at the end. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = HUD.Blood.copy(alpha = 0.22f)),
                onClickLabel = if (checked) "Turn off $label" else "Turn on $label",
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontFamily = HUD_FONT,
            color = HUD.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (checked) "ON" else "OFF",
            modifier = Modifier.padding(end = 10.dp),
            fontSize = 14.sp,
            fontFamily = HUD_FONT,
            color = HUD.TextMuted,
            textAlign = TextAlign.End
        )
        HudToggle(checked = checked)
    }
}

/**
 * The HUD switch.
 *
 * Material's Switch is a filled pill with its own colour roles and an overshoot thumb; neither
 * survives contact with a black-and-red instrument panel, so this is a 44x22 track with a
 * bordered dim edge and a thumb that slides linearly.
 */
@Composable
private fun HudToggle(checked: Boolean) {
    val trackShape = RoundedCornerShape(11.dp)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 0.dp,
        animationSpec = tween(durationMillis = 160),
        label = "hudToggleThumb"
    )

    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 22.dp)
            .clip(trackShape)
            .background(if (checked) HUD.Blood.copy(alpha = 0.30f) else HUD.Void)
            .border(1.dp, HUD.BloodDim, trackShape)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) HUD.Blood else HUD.BloodDim)
        )
    }
}

/** Caption over a glass row; tapping the row opens a void-coloured dropdown. */
@Composable
private fun DropdownRow(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val rowShape = RoundedCornerShape(3.dp)

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontFamily = HUD_FONT,
            color = HUD.TextPrimary,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(HUD.Glass)
                    .border(1.dp, HUD.Hairline, rowShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = HUD.Blood.copy(alpha = 0.22f)),
                        onClickLabel = "Change $label",
                        role = Role.DropdownList,
                        onClick = { expanded = true }
                    )
                    .semantics {
                        contentDescription = label
                        stateDescription = selected
                    }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontFamily = HUD_FONT,
                    color = HUD.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "\u25BE",
                    fontSize = 15.sp,
                    fontFamily = HUD_FONT,
                    color = HUD.Blood
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(4.dp),
                containerColor = HUD.Void,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, HUD.GlassEdge)
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontSize = 16.sp,
                                fontFamily = HUD_FONT,
                                color = if (isSelected) HUD.Blood else HUD.TextPrimary,
                                maxLines = 1
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        modifier = Modifier.background(
                            if (isSelected) HUD.Blood.copy(alpha = 0.10f) else Color.Transparent
                        )
                    )
                }
            }
        }
    }
}

/** Caption and live numeric read-out over a recoloured Material slider. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val clamped = value.coerceIn(0f, 1f)
    val shown = formatUnit(clamped)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontFamily = HUD_FONT,
                color = HUD.TextPrimary,
                maxLines = 1
            )
            Text(
                text = shown,
                fontSize = 14.sp,
                fontFamily = HUD_FONT,
                color = HUD.TextMuted,
                textAlign = TextAlign.End
            )
        }

        Slider(
            value = clamped,
            onValueChange = { onValueChange(it.coerceIn(0f, 1f)) },
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = shown
                },
            colors = SliderDefaults.colors(
                thumbColor = HUD.Blood,
                activeTrackColor = HUD.Blood,
                inactiveTrackColor = HUD.Hairline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

/** Footer action. [filled] is SAVE; the outlined variant is RESET. */
@Composable
private fun HudButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(if (filled) HUD.Blood.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (filled) HUD.Blood else HUD.BloodDim, shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = HUD.Blood.copy(alpha = 0.35f)),
                onClickLabel = text,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontFamily = HUD_FONT,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = if (filled) HUD.TextPrimary else HUD.BloodDim,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** Two-decimal read-out for the 0..1 controls, locale-stable so the dot never becomes a comma. */
private fun formatUnit(value: Float): String = String.format(Locale.US, "%.2f", value)

/** One monospaced family for the whole screen; the HUD has no proportional text. */
private val HUD_FONT = FontFamily.Monospace

/**
 * Gemini API key entry.
 *
 * This lives outside [SeemaConfig] on purpose: the key is a secret, it is already owned by
 * [AiSettings] (which also falls back to the build-time key), and it must not be copied into a
 * draft object that could be written to logs or to the HUD preference file. Saving is explicit
 * so a half-typed key is never committed.
 */
@Composable
private fun ApiKeyRow() {
    val context = LocalContext.current
    val ai = remember { AiSettings(context) }
    var stored by remember { mutableStateOf(ai.apiKey) }
    var editing by remember { mutableStateOf(false) }
    var draftKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "GEMINI API KEY",
            color = HUD.TextMuted,
            fontSize = 14.sp,
            fontFamily = HUD_FONT,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(6.dp))

        if (!editing) {
            // The stored key is never rendered: only a fixed-length mask. Showing the real value
            // would put a live credential on screen for any screenshot or shoulder-surfer, and
            // the mask carries the same information (configured or not).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, HUD.Hairline, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (stored.isBlank()) "NOT CONFIGURED" else "••••••••••••••••",
                    color = if (stored.isBlank()) HUD.TextMuted.copy(alpha = 0.6f) else HUD.TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = HUD_FONT,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (saved) "SAVED" else "CHANGE",
                    color = if (saved) HUD.Online else HUD.Blood,
                    fontSize = 12.sp,
                    fontFamily = HUD_FONT,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable {
                            editing = true
                            draftKey = ""
                            saved = false
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, HUD.Blood, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                if (draftKey.isBlank()) {
                    Text(
                        text = "paste new key from aistudio.google.com",
                        color = HUD.TextMuted.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        fontFamily = HUD_FONT
                    )
                }
                BasicTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    textStyle = TextStyle(color = HUD.TextPrimary, fontSize = 14.sp, fontFamily = HUD_FONT),
                    cursorBrush = SolidColor(HUD.Blood),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "CANCEL",
                    color = HUD.TextMuted,
                    fontSize = 12.sp,
                    fontFamily = HUD_FONT,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable {
                            editing = false
                            draftKey = ""
                        }
                        .padding(8.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "SAVE KEY",
                    color = HUD.Blood,
                    fontSize = 12.sp,
                    fontFamily = HUD_FONT,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, HUD.Blood, RoundedCornerShape(3.dp))
                        .clickable {
                            if (draftKey.isNotBlank()) {
                                ai.apiKey = draftKey.trim()
                                stored = draftKey.trim()
                            }
                            draftKey = ""
                            editing = false
                            saved = true
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}
/**
 * Optional second brain key.
 *
 * Groq's API is OpenAI-compatible and costs a fraction of the Gemini paid tier, and its free
 * allowance is per-minute rather than per-day, so it keeps the assistant conversational after
 * the Gemini free tier's 20 requests are gone. The key lives in the AI preference file next to
 * the Gemini key and is never rendered on screen.
 */
@Composable
private fun GroqKeyRow() {
    val context = LocalContext.current
    var stored by remember { mutableStateOf(SeemaConfigBridge.groqKey(context)) }
    var editing by remember { mutableStateOf(false) }
    var draftKey by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "GROQ API KEY (OPTIONAL FALLBACK)",
            color = HUD.TextMuted,
            fontSize = 11.sp,
            fontFamily = HUD_FONT,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(6.dp))
        if (!editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, HUD.Hairline, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (stored.isBlank()) "NOT CONFIGURED" else "••••••••••••••••",
                    color = if (stored.isBlank()) HUD.TextMuted.copy(alpha = 0.7f) else HUD.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = HUD_FONT,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "CHANGE",
                    color = HUD.Blood,
                    fontSize = 11.sp,
                    fontFamily = HUD_FONT,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { editing = true; draftKey = "" }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, HUD.Blood, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                if (draftKey.isBlank()) {
                    Text(
                        text = "paste key from console.groq.com",
                        color = HUD.TextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = HUD_FONT
                    )
                }
                BasicTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    textStyle = TextStyle(color = HUD.TextPrimary, fontSize = 11.sp, fontFamily = HUD_FONT),
                    cursorBrush = SolidColor(HUD.Blood),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "CANCEL",
                    color = HUD.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = HUD_FONT,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { editing = false; draftKey = "" }
                        .padding(8.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "SAVE KEY",
                    color = HUD.Blood,
                    fontSize = 11.sp,
                    fontFamily = HUD_FONT,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, HUD.Blood, RoundedCornerShape(3.dp))
                        .clickable {
                            if (draftKey.isNotBlank()) {
                                SeemaConfigBridge.setGroqKey(context, draftKey)
                                stored = draftKey.trim()
                            }
                            draftKey = ""
                            editing = false
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** A full-width action row: used for jumps into system settings the app cannot change itself. */
@Composable
private fun ActionRow(label: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = HUD.TextPrimary, fontSize = 13.sp, fontFamily = HUD_FONT)
        Text(
            action,
            color = HUD.Blood,
            fontSize = 11.sp,
            fontFamily = HUD_FONT,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp
        )
    }
}