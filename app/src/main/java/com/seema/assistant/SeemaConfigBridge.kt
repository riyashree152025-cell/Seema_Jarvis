package com.seema.assistant

import android.content.Context
import com.seema.assistant.ui.jarvis.SeemaConfig

/**
 * Applies the HUD configuration to the parts of the assistant that actually behave differently.
 *
 * The configuration screen is a presentation-layer object; the voice service, the brain and the
 * wake word engine each read their own stores. Rather than let every component reach into the
 * HUD preferences, the values that have real behaviour are translated here once, on save.
 */
object SeemaConfigBridge {

    /**
     * Wake-word sensitivity (0 = strict, 1 = eager) to the detector's score threshold
     * (higher threshold = harder to trigger). The default 0.55 lands on the shipped default of
     * 0.05, so a fresh install and a reset behave the same.
     */
    fun sensitivityToThreshold(sensitivity: Float): Float =
        (0.15f - sensitivity.coerceIn(0f, 1f) * 0.18f).coerceIn(0.01f, 0.30f)

    /** Writes everything that another component reads at runtime. */
    fun apply(context: Context, config: SeemaConfig) {
        val app = context.applicationContext
        VoiceSettings(app).wakeThreshold = sensitivityToThreshold(config.wakeSensitivity)
        // Engine, local-brain and wake-word on/off are read live from SeemaConfigStore by the
        // voice service, so only the threshold has to be mirrored into the voice store.
    }

    /**
     * The optional Groq key, kept beside the Gemini key in the AI preference file rather than in
     * [SeemaConfig], so the HUD's own preference file never contains a credential.
     */
    fun groqKey(context: Context): String =
        context.applicationContext
            .getSharedPreferences("seema_ai", Context.MODE_PRIVATE)
            .getString("groq_api_key", "")
            .orEmpty()

    fun setGroqKey(context: Context, key: String) {
        context.applicationContext
            .getSharedPreferences("seema_ai", Context.MODE_PRIVATE)
            .edit()
            .putString("groq_api_key", key.trim())
            .apply()
    }
}