package com.seema.assistant.ui.jarvis

import androidx.compose.ui.graphics.Color
import com.seema.assistant.AgentState

/**
 * Design tokens for the SEEMA main interface.
 *
 * The look is deliberate: near-black instrument glass, one signal colour, thin hairlines,
 * and monospaced telemetry.  Nothing here is decorative — every colour maps to a state the
 * user can read at a glance, which is what makes the screen feel like a machine that is
 * actually running rather than an animation.
 */
object Jarvis {
    /** Background void, close to black but with a blue bias so the glow has something to sit on. */
    val Void = Color(0xFF02050A)

    /** Signal colour used for idle chrome, hairlines and secondary data. */
    val Signal = Color(0xFF5CE1FF)

    /** Dimmed signal: labels that must be readable but never compete with live data. */
    val SignalDim = Color(0xFF1B6070)

    /** Warm accent for values the user should actually read (clock, battery, numbers). */
    val Gold = Color(0xFFFFC46B)

    val Amber = Color(0xFFFFB020)
    val Danger = Color(0xFFFF4258)
    val Mint = Color(0xFF5FF2B0)
    val Ice = Color(0xFFB4F5FF)

    val TextPrimary = Color(0xFFCFEFFF)
    val TextMuted = Color(0xFF5E8391)

    /** 1-pixel separators. */
    val Hairline = Color(0x1FA8E7FF)

    /** Faint panel fill for the transcript well. */
    val Glass = Color(0x0DFFFFFF)

    /** Corner brackets and reticles. */
    val Reticle = Color(0x66A8E7FF)
}

/**
 * The colour of the core for each agent state.
 *
 * Idle is dim on purpose: a machine that is merely awake should not shout.  Colour rises
 * only while SEEMA is doing something, which makes the transition itself the feedback.
 */
fun AgentState.jarvisColor(): Color = when (this) {
    AgentState.IDLE -> Jarvis.SignalDim
    AgentState.SLEEPING -> Color(0xFF2C6B7A)
    AgentState.LISTENING -> Jarvis.Signal
    AgentState.HEARING -> Jarvis.Ice
    AgentState.THINKING -> Jarvis.Amber
    AgentState.SPEAKING -> Jarvis.Mint
    AgentState.ERROR -> Jarvis.Danger
}

/** Short, uppercase status word shown under the core. */
fun AgentState.jarvisLabel(): String = when (this) {
    AgentState.IDLE -> "STANDBY"
    AgentState.SLEEPING -> "DORMANT"
    AgentState.LISTENING -> "LISTENING"
    AgentState.HEARING -> "CAPTURING"
    AgentState.THINKING -> "PROCESSING"
    AgentState.SPEAKING -> "RESPONDING"
    AgentState.ERROR -> "FAULT"
}

/** Two-digit helper used by the clock and counters. */
fun Int.two(): String = if (this < 10) "0$this" else toString()