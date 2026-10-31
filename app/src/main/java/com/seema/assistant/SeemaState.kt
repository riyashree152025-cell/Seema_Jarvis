package com.seema.assistant

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AgentState(val label: String) {
    IDLE("STANDBY"),

    /** No voice input for a while: only the wake word engine runs. */
    SLEEPING("SLEEPING · SAY HEY JARVIS"),
    LISTENING("ONLINE & LISTENING"),
    HEARING("YES? I'M LISTENING"),
    THINKING("PROCESSING..."),
    SPEAKING("RESPONDING"),

    /** The last request failed (network, Gemini or speech); shown briefly, then back to listening. */
    ERROR("ERROR · TRY AGAIN")
}

/** Requests from the UI and the Quick Settings tile to SeemaVoiceService. */
enum class SeemaCommand {
    /** Start listening for a command right away, without the wake word. */
    START_CONVERSATION,

    /** Speak the daily briefing, then stay in follow-up mode. */
    DAILY_BRIEFING,

    /** Fake barge-in: stop speaking now and end the turn (notification "Stop", in-app button). */
    STOP
}

/** Shared state between SeemaVoiceService and the UI. */
object SeemaState {
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)

    /** Live microphone level from 0 (silence) to 1 (loud) while SEEMA is hearing a command. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _wakeScore = MutableStateFlow(0f)

    /** Latest wake word score (shown live in Settings to tune sensitivity). */
    val wakeScore: StateFlow<Float> = _wakeScore.asStateFlow()

    fun setWakeScore(score: Float) {
        _wakeScore.value = score
    }

    private val _serviceRunning = MutableStateFlow(false)

    /** True while SeemaVoiceService is alive in this process. */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _commands = MutableSharedFlow<SeemaCommand>(extraBufferCapacity = 4)
    val commands: SharedFlow<SeemaCommand> = _commands.asSharedFlow()

    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short lines (e.g. reminders) for SeemaVoiceService to speak when it is free. */
    val alerts: SharedFlow<String> = _alerts.asSharedFlow()

    fun announce(text: String) {
        _alerts.tryEmit(text)
    }

    private val _routineRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Routine names to run now, from the routine tile, the widget or the in-app chips. */
    val routineRequests: SharedFlow<String> = _routineRequests.asSharedFlow()

    fun requestRoutine(name: String) {
        _routineRequests.tryEmit(name)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    /** Delivers [command] to the voice service; dropped if the service is not running. */
    fun send(command: SeemaCommand) {
        _commands.tryEmit(command)
    }

    private val _textCommands = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Typed commands, handled exactly like a spoken one (same agent, same speech). */
    val textCommands: SharedFlow<String> = _textCommands.asSharedFlow()

    /** Delivers typed [text] to the voice service; dropped if the service is not running. */
    fun sendText(text: String) {
        _textCommands.tryEmit(text)
    }

    fun update(newState: AgentState) {
        _state.value = newState
    }

    fun setResponse(text: String) {
        _lastResponse.value = text
    }

    fun setAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    private val conversationEndRequested = AtomicBoolean(false)

    /** Called by tools (e.g. a phone call) that need the mic back; ends follow-up mode. */
    fun requestConversationEnd() {
        conversationEndRequested.set(true)
    }

    /** Returns true once per request and clears it. */
    fun consumeConversationEndRequest(): Boolean = conversationEndRequested.getAndSet(false)
}
