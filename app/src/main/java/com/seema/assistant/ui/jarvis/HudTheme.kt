package com.seema.assistant.ui.jarvis

import androidx.compose.ui.graphics.Color
import com.seema.assistant.AgentState

/**
 * Deep Red & Black HUD design system.
 *
 * Readability first: this screen is used outdoors and in dim rooms, so the palette is tuned so
 * every label is legible at arm's length on a 6.6" panel. Muted text is deliberately much
 * lighter than a typical "dim" token — a dark-on-dark HUD looks broken, not sophisticated.
 */
object HUD {
    /** Outermost background. Not pure black: a red-black base gives the glow something to sit on. */
    val Black = Color(0xFF241016)

    /** Base surface for panels and pages. */
    val Void = Color(0xFF33141C)

    /** Primary signal. Bright enough to bloom against the dark base. */
    val Blood = Color(0xFFFF4D5E)

    /** Dimmed signal for chrome and secondary labels. Still readable. */
    val BloodDim = Color(0xFFF07A8A)

    /** Hot core of the reactor. */
    val Ember = Color(0xFFFFB08A)

    /** Numbers the user must read. */
    val Gold = Color(0xFFFFE0A3)

    val Online = Color(0xFF5CFFB0)
    val Warn = Color(0xFFFFC24D)
    val Danger = Color(0xFFFF3B4E)

    /** Body text: near-white, no grey cast. */
    val TextPrimary = Color(0xFFFFFFFF)

    /** Secondary text: light enough to read at 12sp on the dark base. */
    val TextMuted = Color(0xFFF5E4E8)

    /** Frosted glass fill, strong enough to separate a panel from the code stream behind it. */
    val Glass = Color(0x3DFFFFFF)

    /** Glass border: the edge light that makes a pane read as glass. */
    val GlassEdge = Color(0xCCFF4D5E)

    val Hairline = Color(0x99FF4D5E)
    val HairlineSoft = Color(0x4DFF4D5E)

    /** Panel gradient sheen, top to bottom. */
    val SheenTop = Color(0x26FF4D5E)
}

/** Colour of the reactor / status for each agent state. */
fun AgentState.hudColor(): Color = when (this) {
    AgentState.IDLE -> HUD.BloodDim
    AgentState.SLEEPING -> Color(0xFFB03A50)
    AgentState.LISTENING -> HUD.Blood
    AgentState.HEARING -> HUD.Ember
    AgentState.THINKING -> HUD.Warn
    AgentState.SPEAKING -> HUD.Online
    AgentState.ERROR -> HUD.Danger
}

/** Short uppercase status word for the reactor caption. */
fun AgentState.hudLabel(): String = when (this) {
    AgentState.IDLE -> "STANDBY"
    AgentState.SLEEPING -> "DORMANT"
    AgentState.LISTENING -> "LISTENING"
    AgentState.HEARING -> "CAPTURING"
    AgentState.THINKING -> "PROCESSING"
    AgentState.SPEAKING -> "RESPONDING"
    AgentState.ERROR -> "FAULT"
}

/** Which HUD protocol is on screen. */
enum class HudProtocol(val title: String, val subtitle: String) {
    REACTOR("MARK-I", "ARC REACTOR"),
    TACTICAL("MARK-II", "TACTICAL ORBIT"),
    MATRIX("MARK-III", "DATA MATRIX")
}

/**
 * Accent for the active colour profile.
 *
 * These are the three selectable signals. The theme itself stays red-black; the profile only
 * changes what the live elements glow with, so switching profiles never costs readability.
 */
enum class ColourProfile(val label: String, val accent: Color, val ember: Color) {
    DEEP_RED("Deep Red", Color(0xFFFF4D5E), Color(0xFFFFB08A)),
    CRIMSON("Crimson Pulse", Color(0xFFFF1F5E), Color(0xFFFF7AA8)),
    OBSIDIAN("Obsidian Ember", Color(0xFFFF6B2C), Color(0xFFFFB27A));

    companion object {
        fun from(label: String): ColourProfile =
            entries.firstOrNull { it.label == label } ?: DEEP_RED
    }
}

/** Two-digit clock helper. */
fun Int.pad2(): String = if (this < 10) "0$this" else toString()