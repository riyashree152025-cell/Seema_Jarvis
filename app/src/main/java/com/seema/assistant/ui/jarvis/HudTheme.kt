package com.seema.assistant.ui.jarvis

import androidx.compose.ui.graphics.Color
import com.seema.assistant.AgentState

/**
 * Deep Red & Black HUD design system.
 *
 * One accent, one void. Every colour below is a token the rest of the interface reads, so the
 * whole app can be re-skinned by editing this object. Glow is expressed as translucent versions
 * of [Blood] rather than as blur, because a real blur layer behind scrolling text is expensive
 * on mid-range hardware and this screen redraws continuously.
 */
object HUD {
    /** True black used for the outermost vignette. */
    val Black = Color(0xFF030202)

    /** Base surface: near-black with a faint red bias so glows have something to sit on. */
    val Void = Color(0xFF0B0406)

    /** Primary signal. Reserved for live state and active controls. */
    val Blood = Color(0xFFFF1E32)

    /** Dimmed signal for chrome, labels and inactive icons. */
    val BloodDim = Color(0xFF8C1220)

    /** Hot core of the reactor. */
    val Ember = Color(0xFFFF7A5C)

    /** Secondary highlight for numbers the user must read. */
    val Gold = Color(0xFFFFC46B)

    val Online = Color(0xFF3DFF9E)
    val Warn = Color(0xFFFFB020)
    val Danger = Color(0xFFFF1E32)

    val TextPrimary = Color(0xFFF3E4E6)
    val TextMuted = Color(0xFF8A6F73)

    /** Frosted glass fill. Deliberately weak so the code stream behind it stays readable. */
    val Glass = Color(0x12FFFFFF)

    /** Glass border: this is what makes a panel read as an edge-lit pane instead of a box. */
    val GlassEdge = Color(0x59FF1E32)

    val Hairline = Color(0x33FF1E32)
    val HairlineSoft = Color(0x14FF1E32)
}

/** Colour of the reactor / status for each agent state. */
fun AgentState.hudColor(): Color = when (this) {
    AgentState.IDLE -> HUD.BloodDim
    AgentState.SLEEPING -> Color(0xFF5A1018)
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

/** Two-digit clock helper. */
fun Int.pad2(): String = if (this < 10) "0$this" else toString()