package com.seema.assistant.ui.jarvis

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything the configuration screen can change.
 *
 * Deliberately a single immutable-ish class held in one place: the HUD reads it for glow,
 * transparency and reactor behaviour every frame, so scattering these values across several
 * preference keys would mean several file reads per recomposition.
 */
data class SeemaConfig(
    // --- Core AI engine
    val aiEngine: String = ENGINE_GROQ,
    val voiceEngine: String = VOICE_KOKORO,
    val localBrain: Boolean = false,
    // --- Voice and activation
    val wakeWord: Boolean = true,
    val wakeSensitivity: Float = 0.55f,
    val gestureActivation: Boolean = false,
    // --- Mobile control
    val accessibilityAccess: Boolean = false,
    val screenContext: Boolean = false,
    val automationScripts: Boolean = false,
    // --- Coding agent
    val webSearch: Boolean = true,
    val codeGeneration: Boolean = true,
    val deployment: String = DEPLOY_MANUAL,
    // --- Interface
    val colorProfile: String = PROFILE_RED,
    val glowIntensity: Float = 0.85f,
    val transparency: Float = 0.60f,
    val smoothReactor: Boolean = true
) {
    companion object {
        const val ENGINE_GROQ = "Groq LPU (Alpha)"
        const val ENGINE_GEMINI = "Gemini Flash"
        const val ENGINE_LOCAL = "Local Brain"
        val ENGINES = listOf(ENGINE_GROQ, ENGINE_GEMINI, ENGINE_LOCAL)

        const val VOICE_KOKORO = "Kokoro-82M"
        const val VOICE_SYSTEM = "System TTS"
        val VOICES = listOf(VOICE_KOKORO, VOICE_SYSTEM)

        const val DEPLOY_MANUAL = "Manual Approval"
        const val DEPLOY_AUTO = "Auto"
        val DEPLOYMENTS = listOf(DEPLOY_MANUAL, DEPLOY_AUTO)

        const val PROFILE_RED = "Deep Red"
        const val PROFILE_CRIMSON = "Crimson Pulse"
        const val PROFILE_OBSIDIAN = "Obsidian Ember"
        val PROFILES = listOf(PROFILE_RED, PROFILE_CRIMSON, PROFILE_OBSIDIAN)
    }
}

/**
 * Persistence for [SeemaConfig].
 *
 * SharedPreferences is enough here: the object is small, written only when the user presses
 * SAVE CONFIGURATION, and read once at startup. A DataStore migration would add a dependency
 * and an async read in the middle of the first frame for no benefit.
 */
class SeemaConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("seema_hud_config", Context.MODE_PRIVATE)

    /** Pure load; never writes. Missing keys fall back to the documented defaults. */
    fun load(): SeemaConfig {
        val d = SeemaConfig()
        return SeemaConfig(
            aiEngine = prefs.getString(KEY_ENGINE, d.aiEngine) ?: d.aiEngine,
            voiceEngine = prefs.getString(KEY_VOICE, d.voiceEngine) ?: d.voiceEngine,
            localBrain = prefs.getBoolean(KEY_LOCAL, d.localBrain),
            wakeWord = prefs.getBoolean(KEY_WAKE, d.wakeWord),
            wakeSensitivity = prefs.getFloat(KEY_SENS, d.wakeSensitivity),
            gestureActivation = prefs.getBoolean(KEY_GESTURE, d.gestureActivation),
            accessibilityAccess = prefs.getBoolean(KEY_A11Y, d.accessibilityAccess),
            screenContext = prefs.getBoolean(KEY_CONTEXT, d.screenContext),
            automationScripts = prefs.getBoolean(KEY_AUTOMATION, d.automationScripts),
            webSearch = prefs.getBoolean(KEY_WEB, d.webSearch),
            codeGeneration = prefs.getBoolean(KEY_CODE, d.codeGeneration),
            deployment = prefs.getString(KEY_DEPLOY, d.deployment) ?: d.deployment,
            colorProfile = prefs.getString(KEY_PROFILE, d.colorProfile) ?: d.colorProfile,
            glowIntensity = prefs.getFloat(KEY_GLOW, d.glowIntensity),
            transparency = prefs.getFloat(KEY_ALPHA, d.transparency),
            smoothReactor = prefs.getBoolean(KEY_SMOOTH, d.smoothReactor)
        )
    }

    fun save(config: SeemaConfig) {
        prefs.edit()
            .putString(KEY_ENGINE, config.aiEngine)
            .putString(KEY_VOICE, config.voiceEngine)
            .putBoolean(KEY_LOCAL, config.localBrain)
            .putBoolean(KEY_WAKE, config.wakeWord)
            .putFloat(KEY_SENS, config.wakeSensitivity)
            .putBoolean(KEY_GESTURE, config.gestureActivation)
            .putBoolean(KEY_A11Y, config.accessibilityAccess)
            .putBoolean(KEY_CONTEXT, config.screenContext)
            .putBoolean(KEY_AUTOMATION, config.automationScripts)
            .putBoolean(KEY_WEB, config.webSearch)
            .putBoolean(KEY_CODE, config.codeGeneration)
            .putString(KEY_DEPLOY, config.deployment)
            .putString(KEY_PROFILE, config.colorProfile)
            .putFloat(KEY_GLOW, config.glowIntensity)
            .putFloat(KEY_ALPHA, config.transparency)
            .putBoolean(KEY_SMOOTH, config.smoothReactor)
            .apply()
    }

    fun resetDefaults(): SeemaConfig = SeemaConfig().also { save(it) }

    private companion object {
        const val KEY_ENGINE = "ai_engine"
        const val KEY_VOICE = "voice_engine"
        const val KEY_LOCAL = "local_brain"
        const val KEY_WAKE = "wake_word"
        const val KEY_SENS = "wake_sensitivity"
        const val KEY_GESTURE = "gesture_activation"
        const val KEY_A11Y = "accessibility_access"
        const val KEY_CONTEXT = "screen_context"
        const val KEY_AUTOMATION = "automation_scripts"
        const val KEY_WEB = "web_search"
        const val KEY_CODE = "code_generation"
        const val KEY_DEPLOY = "deployment"
        const val KEY_PROFILE = "color_profile"
        const val KEY_GLOW = "glow_intensity"
        const val KEY_ALPHA = "transparency"
        const val KEY_SMOOTH = "smooth_reactor"
    }
}

/** Compose-friendly holder so the HUD and the settings screen share one live source of truth. */
class SeemaConfigState(context: Context) {

    private val store = SeemaConfigStore(context)

    var config by mutableStateOf(store.load())
        private set

    /** Persists [next] and publishes it to every composable reading [config]. */
    fun commit(next: SeemaConfig) {
        config = next
        store.save(next)
    }

    fun reset() {
        config = store.resetDefaults()
    }
}