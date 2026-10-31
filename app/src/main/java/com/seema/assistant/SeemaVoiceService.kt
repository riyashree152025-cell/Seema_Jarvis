package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.seema.assistant.apikeys.data.ApiProvider
import com.seema.assistant.router.ChatRequest
import com.seema.assistant.router.ChatResult
import com.seema.assistant.router.MultiProviderRouter
import com.seema.assistant.router.ProviderTarget
import com.seema.assistant.router.asTarget
import com.seema.assistant.router.failureSummary
import com.seema.assistant.router.outOfQuota
import com.seema.assistant.ui.jarvis.SeemaConfig
import com.seema.assistant.ui.jarvis.SeemaConfigStore
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on SEEMA voice loop:
 * wake word (openWakeWord) → command (SpeechRecognizer) → agent (ADK) → reply (TextToSpeech).
 */
class SeemaVoiceService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            // AudioRecord failures (mic held by another app, etc.) surface here.
            Log.e(TAG, "Voice pipeline failed", error)
            scheduleRestart()
        }
    )

    @Volatile private var engine: WakeWordEngine? = null
    @Volatile private var listeningSince = 0L
    /** True when no wake word model is installed; SEEMA then works through TALK, tile and widget. */
    @Volatile private var wakeWordUnavailable = false
    /**
     * What the resolved wake word model actually hears. Set in [resolveWakeWordModel] and used in
     * every notification, so the phone never asks for a phrase that cannot trigger SEEMA.
     */
    @Volatile private var wakeWordPhrase: String = DEFAULT_WAKE_WORD_PHRASE
    private var engineInitJob: Job? = null
    private var restartJob: Job? = null
    @Volatile private var wakeWordJob: Job? = null
    private var inactivityJob: Job? = null
    /** True after [INACTIVITY_TIMEOUT_MS] without voice input; only the wake word engine runs. */
    @Volatile private var sleeping = false
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var speechRecognizer: SeemaSpeechRecognizer
    private lateinit var textToSpeech: SeemaTextToSpeech
    private lateinit var callAnnouncer: CallAnnouncer
    private lateinit var powerTriggers: PowerTriggerMonitor
    private lateinit var offlineBrain: OfflineBrain
    /**
     * The conversational fallback chain, shared process-wide so the HUD badge binds to the same
     * [MultiProviderRouter.state] the voice service writes.
     *
     * Only conversational turns go through it: the router cannot call the phone's tools, so anything
     * that has to touch the device still goes to [SeemaAgent].
     */
    private val router by lazy { MultiProviderRouter.of(this) }
    /** Read live, so changes saved in Settings apply without restarting the service. */
    private val voiceSettings by lazy { VoiceSettings(this) }
    private lateinit var audioManager: AudioManager

    /** Held for a whole conversation so music pauses while SEEMA listens, then resumes. */
    private val conversationFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The user turned SEEMA on: remember it, so SeemaBootReceiver can restore it after a reboot.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ENABLED, true)
            .apply()
        SeemaAgent.init(this)
        SeemaReminders.init(this)
        SeemaPlaces.init(this)
        // Geofences are dropped when Location is toggled; re-adding on start keeps them alive.
        SeemaGeofences.registerAll(this)
        ProactiveScheduler.ensureScheduled(this)
        RoutineScheduler.ensureScheduled(this)
        audioManager = getSystemService(AudioManager::class.java)
        speechRecognizer = SeemaSpeechRecognizer(this)
        // If the app was killed mid-listen, the beep-muted streams could still be muted.
        speechRecognizer.restoreAudio(force = true)
        textToSpeech = SeemaTextToSpeech(this)
        offlineBrain = OfflineBrain(this)
        // Stay quiet about callers while a conversation is using the mic and speaker.
        callAnnouncer = CallAnnouncer(this, serviceScope, textToSpeech) { wakeWordJob?.isActive != true }
        callAnnouncer.register()
        SeemaTriggers.init(this)
        powerTriggers = PowerTriggerMonitor(this).also { it.register() }

        serviceScope.launch {
            // One collector, so a reminder and a chat announcement never talk over each other.
            merge(SeemaNotificationListener.announcements, SeemaState.alerts)
                .collect { text -> announce(text) }
        }
        serviceScope.launch {
            SeemaState.commands.collect { command ->
                when (command) {
                    SeemaCommand.START_CONVERSATION -> launchConversation()
                    SeemaCommand.DAILY_BRIEFING -> launchConversation(initialCommand = BRIEFING_QUERY)
                    SeemaCommand.STOP -> stopConversation()
                    SeemaCommand.RELOAD_CONFIG -> reloadConfig()
                }
            }
        }
        serviceScope.launch {
            // Typed commands (new UI text field) run through the same pipeline as speech, so they
            // get the same agent, tools and spoken reply.
            SeemaState.textCommands.collect { text ->
                val typed = text.trim()
                if (typed.isEmpty()) return@collect
                // Typed input is deliberate: it replaces whatever turn is in flight.
                stopConversation()
                launchConversation(initialCommand = typed)
            }
        }
        serviceScope.launch {
            SeemaState.routineRequests.collect { name -> launchConversation(initialCommand = "Run my $name routine.") }
        }
        SeemaState.setServiceRunning(true)
        startWatchdog()

        serviceScope.launch {
            // Routines are part of the widget too (shortcut buttons), so redraw when they change.
            combine(SeemaState.state, SeemaState.serviceRunning, SeemaRoutines.routinesFlow) { state, running, _ ->
                state to running
            }.collect { (state, running) -> SeemaWidgetProvider.updateAll(this@SeemaVoiceService, state, running) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // The wake word can be switched off in the configuration screen. The ONNX detector costs
        // roughly two cores continuously, so honouring that switch here is the single biggest
        // battery saving the app has.
        if (SeemaConfigStore(this).load().wakeWord) {
            if (engineInitJob == null) initEngine()
        } else {
            wakeWordUnavailable = true
            startListening()
        }
        when (intent?.action) {
            // Headset button / assistant gesture started us: talk right away.
            ACTION_TALK -> launchConversation()
            // "Stop" in the ongoing notification: cut the reply short, end the conversation.
            ACTION_STOP -> serviceScope.launch {
                val busy = wakeWordJob?.isActive == true
                stopConversation()
                // stopConversation() re-arms listening (and its notification) only if a turn was
                // running; otherwise replace the "Initializing..." line enterForeground() just set.
                if (!busy) restoreWakeWordNotification()
            }
        }
        return START_STICKY
    }

    /**
     * Re-reads the configuration and re-arms (or tears down) the wake word engine.
     *
     * Called when the configuration screen saves. Tearing the engine down is not cosmetic:
     * the detector runs three ONNX sessions over a live AudioRecord, so leaving it alive while
     * the user believes the wake word is off would keep burning power for nothing.
     */
    private fun reloadConfig() {
        val enabled = SeemaConfigStore(this).load().wakeWord
        if (enabled) {
            wakeWordUnavailable = false
            if (engine == null && engineInitJob == null) initEngine() else startListening()
            return
        }
        engineInitJob?.cancel()
        engineInitJob = null
        val current = engine
        engine = null
        current?.stop()
        wakeWordUnavailable = true
        SeemaState.setResponse("Wake word OFF · tap TALK or type a command")
        updateNotification("Wake word off · tap TALK to speak")
        if (current != null) {
            serviceScope.launch {
                delay(ENGINE_RELEASE_DELAY_MS)
                current.release()
            }
        }
    }

    /**
     * Swiping the app out of recents must not kill the always-on listener.
     *
     * This is a foreground service with a persistent notification, so it is allowed to keep
     * running; returning normally (rather than calling stopSelf) is what makes "Hey Jarvis" keep
     * working with the app closed. The engine is rebuilt if it died while the task was gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; keeping the voice service alive")
        if (engine == null && engineInitJob?.isActive != true && SeemaConfigStore(this).load().wakeWord) {
            engineInitJob = null
            initEngine()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Watchdog.
     *
     * A microphone foreground service on an aggressive OEM build can lose its AudioRecord without
     * reporting an error. This checks every 30 seconds that the engine is either alive or
     * deliberately switched off, and rebuilds it when it is neither. It also clears a stalled
     * work-stream flag so the code stream can never be left running by a tool that died.
     */
    private fun startWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (SeemaWorkStream.active.value && SeemaWorkStream.stalled()) {
                    Log.w(TAG, "Clearing a stalled work stream")
                    SeemaWorkStream.reset()
                }
                val wanted = SeemaConfigStore(this@SeemaVoiceService).load().wakeWord
                if (wanted && engine == null && engineInitJob?.isActive != true && !wakeWordUnavailable) {
                    Log.w(TAG, "Wake word engine is not running; rebuilding it")
                    initEngine()
                }
            }
        }
    }

    override fun onDestroy() {
        // The user (or the system) stopped SEEMA: do not bring it back after the next reboot.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ENABLED, false)
            .apply()
        val current = engine
        engine = null
        current?.stop()
        SeemaWorkStream.reset()
        callAnnouncer.unregister()
        powerTriggers.unregister()
        serviceScope.cancel()
        textToSpeech.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        SeemaState.update(AgentState.IDLE)
        SeemaState.setServiceRunning(false)
        SeemaWidgetProvider.updateAll(this, AgentState.IDLE, running = false)
        // Give in-flight inference a moment to finish before closing ONNX sessions.
        if (current != null) {
            CoroutineScope(Dispatchers.Default).launch {
                delay(ENGINE_RELEASE_DELAY_MS)
                current.release()
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- engine

    private fun initEngine() {
        engineInitJob = serviceScope.launch {
            val modelPath = resolveWakeWordModel() ?: return@launch

            // Loads three ONNX sessions; kept off the main thread.
            val newEngine = WakeWordEngine(
                context = applicationContext,
                models = listOf(
                    WakeWordModel(
                        // The library only uses the name for logging and cooldowns; the resolved
                        // asset is what decides which phrase is announced to the user.
                        name = modelPath,
                        modelPath = modelPath,
                        // The library's own detection is effectively off; SEEMA applies the
                        // threshold from Settings to the live scores below, so it can change anytime.
                        threshold = LIBRARY_THRESHOLD_OFF
                    )
                ),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = DETECTION_COOLDOWN_MS,
                scope = serviceScope
            )
            engine = newEngine
            Log.i(TAG, "Wake word engine ready with model '$modelPath'")

            launch {
                newEngine.scores.collect { score ->
                    SeemaState.setWakeScore(score.score)
                    if (score.score >= voiceSettings.wakeThreshold) dispatchWakeWord(score.score)
                }
            }
            // A conversation may already hold the mic (started by the headset button); it re-arms when done.
            if (wakeWordJob?.isActive != true) startListening()
        }
    }

    private fun startListening() {
        val current = engine
        if (current == null) {
            // No wake word model: stay usable through TALK, the tile, the widget and the headset button.
            if (wakeWordUnavailable) {
                SeemaState.update(if (sleeping) AgentState.SLEEPING else AgentState.LISTENING)
                updateNotification(wakeWordStatusText())
                if (!sleeping) resetInactivityTimer()
            }
            return
        }
        try {
            current.start()
            listeningSince = SystemClock.elapsedRealtime()
            if (sleeping) {
                SeemaState.update(AgentState.SLEEPING)
                updateNotification(wakeWordStatusText())
            } else {
                SeemaState.update(AgentState.LISTENING)
                updateNotification(wakeWordStatusText())
                resetInactivityTimer()
            }
            Log.i(TAG, "Listening for wake word${if (sleeping) " (sleep mode)" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start listening", e)
            scheduleRestart()
        }
    }

    /** The notification line for the current wake word state (no conversation running). */
    private fun wakeWordStatusText(): String = when {
        wakeWordUnavailable -> "Wake word off · tap TALK to speak"
        sleeping -> "Sleeping · say \"$wakeWordPhrase\" to wake me"
        else -> "Listening for \"$wakeWordPhrase\""
    }

    /**
     * Rewrites that line without touching the engine, for when a control action (the notification's
     * "Stop") arrives while nothing is speaking. Does nothing while the engine is still loading.
     */
    private fun restoreWakeWordNotification() {
        if (engine == null && !wakeWordUnavailable) return
        updateNotification(wakeWordStatusText())
    }

    private fun scheduleRestart() {
        if (restartJob?.isActive == true) return
        engine?.stop()
        SeemaState.update(AgentState.IDLE)
        updateNotification("Microphone unavailable — retrying…")
        restartJob = serviceScope.launch {
            delay(RESTART_DELAY_MS)
            startListening()
        }
    }

    /** Stops the wake word engine and waits until its AudioRecord has been released. */
    private suspend fun releaseMicrophone() {
        engine?.stop()
        delay(MIC_HANDOFF_DELAY_MS)
    }

    /** Picks seema.onnx, falling back to the stock "hey jarvis" model for testing. */
    private fun resolveWakeWordModel(): String? {
        val available = assets.list("")?.toSet().orEmpty()

        val missingFeatures = FEATURE_MODELS.filterNot { it in available }
        if (missingFeatures.isNotEmpty()) {
            reportFatal("Missing in assets: ${missingFeatures.joinToString()}")
            return null
        }

        val model = WAKE_WORD_MODELS.firstOrNull { it in available }
        if (model == null) {
            // Not fatal: everything except the spoken wake word still works.
            Log.w(TAG, "No wake word model in assets")
            wakeWordUnavailable = true
            SeemaState.setResponse(
                "Wake word off: add seema.onnx or hey_jarvis_v0.1.onnx to app/src/main/assets. " +
                    "Until then, tap TALK."
            )
            startListening()
            return null
        }
        if (model != WAKE_WORD_MODELS.first()) {
            Log.w(TAG, "${WAKE_WORD_MODELS.first()} not found, using fallback model '$model'")
        }
        // Announce what this model really listens for (seema.onnx is not bundled today).
        wakeWordPhrase = WAKE_WORD_PHRASES[model] ?: DEFAULT_WAKE_WORD_PHRASE
        Log.i(TAG, "Wake word phrase: \"$wakeWordPhrase\" ($model)")
        return model
    }

    private fun reportFatal(message: String) {
        Log.e(TAG, message)
        SeemaState.update(AgentState.IDLE)
        SeemaState.setResponse("ERROR: $message")
        updateNotification(message)
        stopSelf()
    }

    // ------------------------------------------------------------ voice loop

    /** Restarts the 2-minute countdown to sleep mode. Called on every voice interaction. */
    @Synchronized
    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            goToSleep()
        }
    }

    /**
     * No voice input for [INACTIVITY_TIMEOUT_MS]: stop any SpeechRecognizer still waiting for
     * speech and show SLEEPING. The wake word engine keeps running, so the wake word still wakes SEEMA.
     */
    private fun goToSleep() {
        val conversation = wakeWordJob
        if (conversation?.isActive == true) {
            val state = SeemaState.state.value
            // Busy thinking or speaking is not inactivity: check again later.
            if (state != AgentState.HEARING && state != AgentState.ERROR) {
                resetInactivityTimer()
                return
            }
            sleeping = true
            Log.i(TAG, "😴 Inactive — stopping speech recognition, sleep mode")
            conversation.cancel() // its finally block re-arms the wake word in SLEEPING state
            return
        }
        sleeping = true
        Log.i(TAG, "😴 Inactive for ${INACTIVITY_TIMEOUT_MS / 1000}s — sleep mode")
        if (engine != null || wakeWordUnavailable) {
            SeemaState.update(AgentState.SLEEPING)
            updateNotification(
                if (wakeWordUnavailable) "Sleeping · tap TALK to speak"
                else "Sleeping · say \"$wakeWordPhrase\" to wake me"
            )
        }
    }

    private fun dispatchWakeWord(score: Float) {
        // Ignore new triggers while the previous one is still being handled.
        if (wakeWordJob?.isActive == true) return
        // The engine keeps ~1.3 s of audio features across restarts; ignore re-triggers from them.
        if (SystemClock.elapsedRealtime() - listeningSince < REARM_GUARD_MS) {
            Log.d(TAG, "Ignoring stale detection right after re-arming")
            return
        }
        Log.i(
            TAG,
            "🎤 WAKE WORD DETECTED!${if (sleeping) " Waking up." else ""} " +
                "(score=${"%.3f".format(score)}, threshold=${voiceSettings.wakeThreshold})"
        )
        launchConversation()
    }

    /**
     * Starts a conversation unless one is already running. Triggered by the wake word, the
     * Quick Settings tile or the in-app buttons; [initialCommand] skips listening for the first turn.
     */
    @Synchronized
    private fun launchConversation(initialCommand: String? = null) {
        if (wakeWordJob?.isActive == true) return
        // Any way of starting a conversation wakes SEEMA up and restarts the inactivity timer.
        sleeping = false
        resetInactivityTimer()
        wakeWordJob = serviceScope.launch {
            audioManager.requestAudioFocus(conversationFocus)
            try {
                releaseMicrophone()
                runConversation(initialCommand)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave the UI stuck on PROCESSING/LISTENING after an unexpected failure.
                Log.e(TAG, "Conversation failed", e)
                showError("ERROR: ${e.message ?: e::class.java.simpleName}")
            } finally {
                // Back to always-on wake word listening, whatever happened above.
                startListening()
                audioManager.abandonAudioFocusRequest(conversationFocus)
            }
        }
    }

    /**
     * Fake barge-in: ends the current turn right away, even in the middle of a spoken reply.
     *
     * [SeemaTextToSpeech] has no public stop, but cancelling the conversation job resumes its
     * `speak()` suspension with a [CancellationException], and the `finally` block there flushes
     * TextToSpeech. The conversation's own `finally` then re-arms the wake word, so the next wake
     * word works as usual. Never call this from inside [wakeWordJob] (it joins that job).
     */
    private suspend fun stopConversation() {
        SeemaState.requestConversationEnd()
        wakeWordJob?.cancelAndJoin()
    }

    /**
     * One wake-word command, then follow-up commands without the wake word until the user
     * stays silent, says goodbye, an error occurs, a tool such as a phone call ends it, or
     * [MAX_CONVERSATION_TURNS] is reached.
     */
    private suspend fun runConversation(initialCommand: String? = null) {
        SeemaState.consumeConversationEndRequest()
        var command = initialCommand ?: captureCommand(followUp = false) ?: return
        var turns = 0
        while (true) {
            if (isExitPhrase(command)) {
                SeemaState.setResponse("YOU: $command\n\nSEEMA: $GOODBYE_REPLY")
                speak(GOODBYE_REPLY)
                return
            }
            if (!handleCommand(command)) return
            if (SeemaState.consumeConversationEndRequest()) return
            if (++turns >= MAX_CONVERSATION_TURNS) return

            delay(FOLLOW_UP_GAP_MS)
            command = captureCommand(followUp = true) ?: return
        }
    }

    /**
     * Returns the user's spoken command, or null. After the wake word, failures are spoken;
     * in follow-up mode silence simply ends the conversation.
     */
    private suspend fun captureCommand(followUp: Boolean): String? {
        if (!speechRecognizer.isAvailable()) {
            if (!followUp) {
                SeemaState.setResponse("ERROR: No speech recognition service. Enable the Google app.")
                speak("Speech recognition is not available on this phone.")
            }
            return null
        }

        val conversation = if (followUp) SeemaState.lastResponse.value else ""
        SeemaState.update(AgentState.HEARING)
        showTranscript(conversation, "🎙 …")
        updateNotification(if (followUp) "Anything else?" else "Listening to you…")

        val result = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                speechRecognizer.listenOnce(
                    languageTag = voiceSettings.speechLanguage,
                    muteBeep = voiceSettings.muteRecognitionBeep,
                    speechStartTimeoutMs = if (followUp) FOLLOW_UP_WINDOW_MS else null,
                    offline = !NetworkStatus.isOnline(this@SeemaVoiceService) ||
                        VoiceSettings(this@SeemaVoiceService).preferOfflineRecognition,
                    onLevel = SeemaState::setAudioLevel
                ) { partial ->
                    showTranscript(conversation, "🎙 $partial")
                }
            } ?: SpeechResult.NoSpeech
        } finally {
            SeemaState.setAudioLevel(0f)
        }

        return when (result) {
            is SpeechResult.Text -> {
                resetInactivityTimer()
                Log.i(TAG, "👤 Command${if (followUp) " (follow-up)" else ""}: ${result.text}")
                result.text
            }
            SpeechResult.NoSpeech -> {
                if (followUp) {
                    Log.d(TAG, "Follow-up window closed")
                    SeemaState.setResponse(conversation)
                } else {
                    SeemaState.setResponse("No command heard.")
                    speak(NO_SPEECH_REPLY)
                }
                null
            }
            is SpeechResult.Error -> {
                if (followUp) {
                    Log.w(TAG, "Follow-up recognition failed: ${result.message}")
                    SeemaState.setResponse(conversation)
                } else {
                    SeemaState.setResponse("ERROR: Speech recognition — ${result.message}")
                    speak(STT_ERROR_REPLY)
                    holdErrorState()
                }
                null
            }
        }
    }

    /**
     * Runs [command] through the brain chain and speaks the reply.
     *
     * The chain is owned by [MultiProviderRouter] now. Gemini Flash is still tried first unless the
     * user pinned another engine, because [SeemaAgent] is the only brain with the phone's tools;
     * everything behind it (Groq, Cerebras, OpenRouter) is a conversational fallback — exactly what
     * `GroqBrain` used to be, minus the single-provider limit and the `"ERROR:"` prose sniffing.
     *
     * Returns false only when nothing could answer and the turn has to end with an error.
     */
    private suspend fun handleCommand(command: String): Boolean {
        SeemaState.update(AgentState.THINKING)
        SeemaState.setResponse("YOU: $command")
        updateNotification("Thinking...")

        val config = SeemaConfigStore(this).load()
        // A stored Groq key is the legacy signal that a second, conversational brain exists: it lets
        // the router hand a failed Gemini turn over in seconds instead of waiting out the 25 s
        // no-fallback budget, and it is also what the pinned-Groq engine needs to be usable at all.
        val groqReady = SeemaConfigBridge.groqKey(this).isNotBlank()
        SeemaAgent.fallbackAvailable = groqReady

        // The HUD's code stream stays hidden unless real work is running, so the flag is raised
        // only for turns that actually generate code, scrape a page or write a file.
        val autonomous = isAutonomousWork(command)
        if (autonomous) SeemaWorkStream.begin("AUTONOMOUS TASK")

        // No internet, or the user chose the local brain: answer basic commands locally. The same
        // path is reused below when every provider fails, because the basics should not stop working
        // just because a quota ran out.
        val localBrain = config.localBrain
        val localOnly = localBrain || !NetworkStatus.isOnline(this)
        var turn: SpokenTurn? = null
        var exhausted: ChatResult.AllFailed? = null
        var geminiQuota = false

        if (localOnly) {
            turn = SpokenTurn(
                text = offlineBrain.handle(command),
                note = if (localBrain) LOCAL_BRAIN_NOTE else OFFLINE_NOTE,
            )
        } else if (config.aiEngine == SeemaConfig.ENGINE_GROQ && groqReady) {
            // The user pinned Groq as the conversational brain, and a Groq key is actually stored.
            // The router still falls back through the other configured providers; the tool agent
            // stays the last resort, as before. Without a key this branch is skipped so a
            // tool-carrying turn still reaches Gemini rather than a tool-less conversation.
            when (val routed = router.executeChat(conversationalRequest(command), ApiProvider.GROQ.id)) {
                is ChatResult.Success -> turn = routedTurn(routed, requested = ApiProvider.GROQ.asTarget())
                is ChatResult.AllFailed -> {
                    val agentReply = SeemaAgent.runAgent(command)
                    if (agentReply.startsWith(ERROR_PREFIX)) {
                        exhausted = routed
                        geminiQuota = SeemaAgent.isQuotaError(agentReply)
                    } else {
                        turn = SpokenTurn(agentReply, ApiProvider.GEMINI.asTarget())
                    }
                }
            }
        } else {
            // Gemini Flash keeps the phone's tools, so it is tried first.
            val agentReply = SeemaAgent.runAgent(command)
            if (!agentReply.startsWith(ERROR_PREFIX)) {
                turn = SpokenTurn(agentReply, ApiProvider.GEMINI.asTarget())
            } else {
                Log.w(TAG, "Gemini tool agent failed: $agentReply")
                geminiQuota = SeemaAgent.isQuotaError(agentReply)
                // A quota refusal or a missing key is a verdict on the credential, so the sweep
                // starts at the next provider. Anything else may be transient, so Gemini stays in
                // the sweep and gets one conversational retry with a fresh request.
                val geminiDead = geminiQuota || AiSettings(this).effectiveApiKey.isBlank()
                val first = if (geminiDead) {
                    // The configured rotation, minus Gemini and minus any custom provider: the sweep
                    // the router is about to run already appends the user's custom providers, and this
                    // id only decides which *built-in* goes first.
                    router.priority.firstOrNull { it.builtIn != ApiProvider.GEMINI }
                } else {
                    null
                }
                when (val routed = router.executeChat(conversationalRequest(command), first?.id)) {
                    is ChatResult.Success -> turn = routedTurn(routed, requested = first)
                    is ChatResult.AllFailed -> exhausted = routed
                }
            }
        }

        if (autonomous) SeemaWorkStream.end()

        // Nothing remote answered. Decide between "the basics still work" and a real outage; the
        // router's typed failures carry the verdict, so no prose parsing is needed here any more.
        var answer = turn
        if (answer == null) {
            val quota = geminiQuota || exhausted?.outOfQuota() == true
            val onlineNow = NetworkStatus.isOnline(this)
            if (quota || !onlineNow) {
                answer = SpokenTurn(
                    text = offlineBrain.handle(command),
                    note = if (quota) QUOTA_NOTE else OFFLINE_NOTE,
                )
            } else {
                val summary = exhausted?.failureSummary().orEmpty()
                Log.w(TAG, "Every brain failed: ${summary.ifBlank { "nothing was attempted" }}")
                SeemaState.setResponse(
                    buildString {
                        append("YOU: $command\n\n⚠ $NO_BRAIN_TEXT")
                        if (summary.isNotBlank()) append("\n$summary")
                    }
                )
                speak(ALL_BRAINS_FAILED_REPLY)
                holdErrorState()
                return false
            }
        }

        // A tool turn never reaches the router, so the HUD badge has to be told who answered or it
        // would keep naming the provider of the last *routed* turn.
        answer.provider?.let(router::recordExternalReply)
        val localAnswer = localBrain || !NetworkStatus.isOnline(this)
        Log.i(TAG, "🤖 SEEMA${if (localAnswer) " (offline)" else ""}: ${answer.text}")
        SeemaState.setResponse(
            if (answer.note.isBlank()) "YOU: $command\n\nSEEMA: ${answer.text}"
            else "YOU: $command\n\n${answer.note}\n\nSEEMA: ${answer.text}"
        )
        speak(answer.text)
        return true
    }

    /**
     * The turn as the router should send it.
     *
     * The persona is [SeemaAgent.conversationalPrompt] — the same tool-less prompt the old Groq path
     * used — because a fallback brain must be told plainly that it cannot touch the phone, or it
     * will cheerfully promise to turn on the torch and then do nothing.
     */
    private fun conversationalRequest(command: String): ChatRequest =
        ChatRequest(prompt = command, systemPrompt = SeemaAgent.conversationalPrompt())

    /**
     * A router success as a spoken turn, with a note naming the brain when it was not the one that
     * was asked first.
     *
     * The note is only set on a hand-over: repeating "BRAIN: GROQ" on every turn the pinned brain
     * answered would be noise, but when a later provider takes over the user should be able to see
     * why the voice changed.
     *
     * Both sides of the comparison are [ProviderTarget]s, not enum constants, so a turn that a
     * user-added custom provider rescued still reports which provider actually answered — comparing a
     * custom target against a built-in one is simply unequal, which is exactly the hand-over the note
     * is for.
     */
    private fun routedTurn(result: ChatResult.Success, requested: ProviderTarget?): SpokenTurn =
        SpokenTurn(
            text = result.completion.text,
            provider = result.completion.target,
            note = if (requested == null || result.completion.target != requested) {
                "$BRAIN_NOTE_PREFIX${result.completion.target.displayName.uppercase(Locale.ROOT)}"
            } else {
                ""
            },
        )

    /** Shows [message] in the red ERROR state for a moment (used when nothing will be spoken). */
    private suspend fun showError(message: String) {
        SeemaState.setResponse(message)
        holdErrorState()
    }

    /** Red, static reactor for [ERROR_HOLD_MS]; the conversation's finally block then re-arms listening. */
    private suspend fun holdErrorState() {
        SeemaState.update(AgentState.ERROR)
        updateNotification(NETWORK_ERROR_TEXT)
        delay(ERROR_HOLD_MS)
    }

    /** True when this command asks for code, a page, a file or a scrape. */
    private fun isAutonomousWork(command: String): Boolean =
        AUTONOMOUS_HINTS.any { command.contains(it, ignoreCase = true) }

    private suspend fun speak(text: String) {
        speechRecognizer.restoreAudio()
        SeemaState.update(AgentState.SPEAKING)
        updateNotification("Speaking…")
        textToSpeech.speak(text)
    }

    /** Speaks a chat announcement or reminder unless SEEMA is busy or the phone is in a call. */
    private suspend fun announce(text: String) {
        if (wakeWordJob?.isActive == true) return
        if (audioManager.mode != AudioManager.MODE_NORMAL) return
        // Respect Do Not Disturb: the notification still appears, SEEMA just stays quiet.
        val filter = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        Log.i(TAG, "💬 $text")
        SeemaState.setResponse("💬 $text")
        textToSpeech.speak(text)
    }

    private fun showTranscript(conversation: String, line: String) {
        SeemaState.setResponse(if (conversation.isEmpty()) line else "$conversation\n\n$line")
    }

    private fun isExitPhrase(command: String): Boolean {
        val normalized = command.lowercase(Locale.ROOT)
            .replace(NON_WORD_CHARS, " ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in WAKE_WORD_MENTIONS }
            .joinToString(" ")
        return normalized in EXIT_PHRASES
    }

    // ------------------------------------------------------ foreground/system

    private fun enterForeground(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.e(TAG, "RECORD_AUDIO not granted; cannot start")
            return false
        }
        // Location type lets the weather tool read the location while SEEMA runs in the background.
        // It may only be declared once location permission is granted, or startForeground throws.
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (locationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        return try {
            createChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Initializing…"),
                serviceTypes
            )
            true
        } catch (e: Exception) {
            // Android 14+ only allows microphone FGS to start while the app is visible.
            Log.e(TAG, "Cannot enter foreground — open SEEMA to start listening", e)
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Seema::VoiceService")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SEEMA Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Always-on wake word listener" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SEEMA ONLINE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            // Fake barge-in: stops the reply that is playing, without opening the app.
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    PendingIntent.getService(
                        this,
                        STOP_INTENT_REQUEST_CODE,
                        Intent(this, SeemaVoiceService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            )
            .build()

    companion object {
        private const val TAG = "SeemaVoiceService"

        const val CHANNEL_ID = "seema_voice"
        /** Start intent action: begin a conversation immediately (headset button, assistant gesture). */
        const val ACTION_TALK = "com.seema.assistant.action.TALK"
        /** Stop intent action: end the current turn and silence the reply (notification "Stop"). */
        const val ACTION_STOP = "com.seema.assistant.action.STOP"
        const val NOTIFICATION_ID = 1001
        private const val STOP_INTENT_REQUEST_CODE = 1

        /** SharedPreferences file and flag telling [SeemaBootReceiver] whether SEEMA was running. */
        const val PREFS_NAME = "seema_voice"
        const val KEY_SERVICE_ENABLED = "service_enabled"

        /** Above any real score: the library's own detections never fire. */
        private const val LIBRARY_THRESHOLD_OFF = 2f
        private const val DETECTION_COOLDOWN_MS = 2000L
        private const val RESTART_DELAY_MS = 3000L
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** Words that mean the user is asking for machine work the HUD can visualise. */
        private val AUTONOMOUS_HINTS = listOf(
            "code", "script", "html", "css", "kotlin", "python", "json", "sql",
            "file", "folder", "save", "write", "create", "generate", "build",
            "scrape", "crawl", "download", "website", "web page", "deploy",
            "banao", "bana do", "likho", "likh do", "file banao", "code likho"
        )
        private const val ENGINE_RELEASE_DELAY_MS = 500L

        /** Time for the engine's AudioRecord loop to exit and release the mic. */
        private const val MIC_HANDOFF_DELAY_MS = 400L
        /** Covers the engine's 16-frame (~1.3 s) feature window after re-arming. */
        private const val REARM_GUARD_MS = 2000L
        /** Upper bound on one spoken command, in case the recognizer never calls back. */
        private const val COMMAND_TIMEOUT_MS = 15_000L
        /** Follow-up mode: how long SEEMA waits for you to start speaking after a reply. */
        private const val FOLLOW_UP_WINDOW_MS = 6_000L
        /** Short pause after speaking so the end of the reply is not picked up by the mic. */
        private const val FOLLOW_UP_GAP_MS = 250L
        /** Safety cap on wake-word-free turns in one conversation. */
        private const val MAX_CONVERSATION_TURNS = 10
        /** No voice input for this long puts SEEMA to sleep (wake word only). */
        private const val INACTIVITY_TIMEOUT_MS = 2 * 60_000L
        /** How long the red ERROR state stays on screen before listening again. */
        private const val ERROR_HOLD_MS = 3_000L
        private const val NETWORK_ERROR_TEXT = "Network Error. Please try again."

        /** Every [SeemaAgent] failure starts with this; it is the one place that convention is read. */
        private const val ERROR_PREFIX = "ERROR:"

        private const val LOCAL_BRAIN_NOTE = "⚠ LOCAL BRAIN"
        private const val OFFLINE_NOTE = "⚠ OFFLINE"

        /** Shown with a reply when a later provider took the turn, so the voice change is visible. */
        private const val BRAIN_NOTE_PREFIX = "⚡ BRAIN: "

        /** Set when the free tiers are spent: the basics still answer, so the conversation can go on. */
        private const val QUOTA_NOTE = "⚠ ALL BRAINS OUT OF QUOTA — BASIC MODE (free tiers reset daily)"

        /** The one-line verdict when every provider failed and the basics cannot cover the turn. */
        private const val NO_BRAIN_TEXT = "ALL BRAINS FAILED"

        private const val NO_SPEECH_REPLY = "I didn't catch that."
        private const val STT_ERROR_REPLY = "Sorry, I couldn't hear you properly."
        private const val ALL_BRAINS_FAILED_REPLY =
            "I can't reach any of my brains right now. Check the API keys in settings, or the connection."
        private const val GOODBYE_REPLY = "Anytime. I'll be right here."
        private const val BRIEFING_QUERY = "Good morning! Give me my daily briefing."

        private val NON_WORD_CHARS = Regex("[^\\p{L}\\p{M}\\p{N}' ]")
        private val WAKE_WORD_MENTIONS = setOf("seema", "सीमा")

        /** Whole-utterance phrases that end follow-up mode (wake word and punctuation ignored). */
        private val EXIT_PHRASES = setOf(
            "stop", "stop it", "no", "nope", "nothing", "nothing else", "that's all", "that is all",
            "thanks", "thank you", "thank you so much", "no thanks", "no thank you",
            "never mind", "bye", "goodbye", "ok bye", "okay bye", "ok thanks", "okay thanks",
            "bas", "bas karo", "bas itna hi", "ruko", "theek hai bas", "shukriya",
            "dhanyavad", "dhanyawad", "बस", "बस करो", "रुको", "शुक्रिया", "धन्यवाद"
        )

        /** Loaded internally by the library under these exact names. */
        private val FEATURE_MODELS = listOf("melspectrogram.onnx", "embedding_model.onnx")

        /**
         * The phrase each bundled model actually detects. The notifications show the phrase of the
         * model that resolved, so SEEMA never tells the user to say something that cannot work
         * (`seema.onnx` is not shipped today; `hey_jarvis_v0.1.onnx` is).
         */
        private val WAKE_WORD_PHRASES = mapOf(
            "seema.onnx" to "Seema",
            "hey_jarvis_v0.1.onnx" to "Hey Jarvis"
        )

        /** First match wins. */
        private val WAKE_WORD_MODELS = listOf("seema.onnx", "hey_jarvis_v0.1.onnx")

        /** Used until [resolveWakeWordModel] picks a model, and for a model without a phrase above. */
        private const val DEFAULT_WAKE_WORD_PHRASE = "Hey Jarvis"
    }
}

/**
 * One finished turn: what to say, who said it, and the one-line provenance shown with it.
 *
 * A small value type rather than three parallel variables, because a reply attributed to the wrong
 * brain is worse than no attribution: the transcript note and the HUD badge both read these fields.
 * [provider] is null only when the local/offline brain answered, which has no provider identity.
 *
 * It is a [ProviderTarget] rather than an [ApiProvider] so a user-added custom provider that answered
 * a turn can be named here too; `provider?.builtIn` is how the built-in-only call sites still ask
 * their question, and it is null for a custom provider.
 */
private data class SpokenTurn(
    val text: String,
    val provider: ProviderTarget? = null,
    val note: String = "",
)
