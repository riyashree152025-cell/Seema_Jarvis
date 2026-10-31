package com.seema.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** How SEEMA sounds and listens; edited in the in-app Settings dialog, read on every reply. */
class VoiceSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("seema_voice", Context.MODE_PRIVATE)

    /** TTS voice name (e.g. "en-gb-x-rjs-local"); null = automatic by language. */
    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(value) = prefs.edit().putString("voice_name", value).apply()

    /** 1.0 is normal; lower is deeper. */
    var pitch: Float
        get() = prefs.getFloat("pitch", 1.0f)
        set(value) = prefs.edit().putFloat("pitch", value.coerceIn(MIN, MAX)).apply()

    /** 1.0 is normal; higher is faster. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(value) = prefs.edit().putFloat("speech_rate", value.coerceIn(MIN, MAX)).apply()

    /** Use on-device speech recognition even when online (faster, private, needs the offline pack). */
    var preferOfflineRecognition: Boolean
        get() = prefs.getBoolean("prefer_offline_stt", false)
        set(value) = prefs.edit().putBoolean("prefer_offline_stt", value).apply()

    /** Language SEEMA listens in (see [SPEECH_LANGUAGES]). */
    var speechLanguage: String
        get() = prefs.getString("speech_language", DEFAULT_SPEECH_LANGUAGE) ?: DEFAULT_SPEECH_LANGUAGE
        set(value) = prefs.edit().putString("speech_language", value).apply()

    /**
     * Wake word score needed to wake SEEMA. This library's scores are small (its own sample
     * uses 0.03), so lower = more sensitive; raise it if she wakes up by mistake.
     */
    var wakeThreshold: Float
        get() = prefs.getFloat("wake_threshold", DEFAULT_WAKE_THRESHOLD)
        set(value) = prefs.edit().putFloat("wake_threshold", value.coerceIn(MIN_WAKE_THRESHOLD, MAX_WAKE_THRESHOLD)).apply()

    /** Silence the "ting" that Google's recognizer plays when it starts and stops listening. */
    var muteRecognitionBeep: Boolean
        get() = prefs.getBoolean("mute_recognition_beep", true)
        set(value) = prefs.edit().putBoolean("mute_recognition_beep", value).apply()

    companion object {
        const val MIN = 0.5f
        const val MAX = 2.0f

        /** Calm, slightly deep British delivery. */
        const val JARVIS_PITCH = 0.85f
        const val JARVIS_RATE = 1.05f

        /** Google TTS male en-GB voices, best first; the first installed one wins. */
        val JARVIS_VOICE_PREFIXES = listOf("en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb")

        const val DEFAULT_SPEECH_LANGUAGE = "en-IN"

        /** Language tag to label, as shown in Settings. */
        val SPEECH_LANGUAGES = listOf(
            "en-IN" to "Hinglish / Indian English",
            "hi-IN" to "हिंदी (Hindi)",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)"
        )

        const val DEFAULT_WAKE_THRESHOLD = 0.05f
        const val MIN_WAKE_THRESHOLD = 0.01f
        const val MAX_WAKE_THRESHOLD = 0.3f
    }
}

/**
 * Gemini key and model chosen in the app's Settings. Blank values fall back to what was built in
 * from local.properties (GEMINI_API_KEY / SEEMA_GEMINI_MODEL).
 */
class AiSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("seema_ai", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "").orEmpty()
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** Fallback city for weather when the phone's location is unavailable. */
    var homeCity: String
        get() = prefs.getString("home_city", "").orEmpty()
        set(value) = prefs.edit().putString("home_city", value.trim()).apply()

    /** The key SEEMA will use: the one entered in the app, else the built-in one. */
    val effectiveApiKey: String get() = apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }

    /** The model SEEMA will use: the one entered in the app, else the built-in default. */
    val effectiveModel: String get() = model.ifBlank { BuildConfig.SEEMA_GEMINI_MODEL }

    /** The weather fallback city: the one entered in the app, else the built-in one. */
    val effectiveHomeCity: String get() = homeCity.ifBlank { BuildConfig.SEEMA_HOME_CITY }
}

/** True when there is a network that actually reaches the internet. */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
