package com.seema.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Suspending wrapper around Android's TextToSpeech. Create on the main thread. */
class SeemaTextToSpeech(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    private val tts = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = complete(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS error $errorCode")
                complete(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
        })
    }

    /** Speaks [text] and suspends until playback finishes (or fails). */
    suspend fun speak(text: String) {
        val initialized = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!initialized) {
            Log.e(TAG, "TextToSpeech engine not available")
            return
        }

        if (!applyVoiceSettings(text)) {
            Log.e(TAG, "No usable TTS language (not even English); skipping speech")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[utteranceId] = done

        audioManager.requestAudioFocus(focusRequest)
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TextToSpeech.speak() rejected the utterance")
                return
            }
            val maxDuration = maxOf(MIN_SPEAK_TIMEOUT_MS, text.length * MS_PER_CHAR)
            withTimeoutOrNull(maxDuration) { done.await() }
        } finally {
            // Also runs on cancellation, so speech never outlives the request.
            if (!done.isCompleted) tts.stop()
            pending.remove(utteranceId)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    /**
     * The chosen voice for English replies, a Hindi voice for Hindi (Devanagari text, or Hindi
     * picked as the listening language), plus pitch and speed. Read on every reply, so changes in
     * Settings apply immediately. Returns false only if no language at all can be used.
     */
    private fun applyVoiceSettings(text: String): Boolean {
        val settings = VoiceSettings(appContext)
        val hindi = text.any { it in '\u0900'..'\u097F' } || settings.speechLanguage.startsWith("hi")
        tts.setPitch(settings.pitch)
        tts.setSpeechRate(settings.speechRate)
        if (!hindi) {
            val chosen = settings.voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
            // A network voice cannot speak offline; fall back to the built-in voice for the language.
            val usable = chosen?.takeUnless { it.isNetworkConnectionRequired && !NetworkStatus.isOnline(appContext) }
            if (usable != null && tts.setVoice(usable) == TextToSpeech.SUCCESS) return true
        }
        return setLanguageWithFallback(if (hindi) HINDI else INDIAN_ENGLISH)
    }

    /** Tries [preferred], then Indian English, then US English; never crashes on missing voice data. */
    private fun setLanguageWithFallback(preferred: Locale): Boolean {
        for (locale in listOf(preferred, INDIAN_ENGLISH, Locale.US).distinct()) {
            val availability = tts.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS voice for $locale unavailable (${describe(availability)}), falling back")
                if (locale == HINDI) offerVoiceDownload()
                continue
            }
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return true
            Log.w(TAG, "setLanguage($locale) failed (${describe(result)}), falling back")
            if (locale == HINDI) offerVoiceDownload()
        }
        return false
    }

    /** Once per app run: opens the TTS engine's voice download screen and tells the user why. */
    private fun offerVoiceDownload() {
        if (!downloadOffered.compareAndSet(false, true)) return
        SeemaState.setResponse(
            SeemaState.lastResponse.value + "\n\n⚠ Hindi voice data missing — speaking in English. Download it: Settings → System → " +
                "Languages → Text-to-speech → Google → Install voice data → Hindi."
        )
        try {
            appContext.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // No installer, or Android blocked starting an activity from the background.
            Log.w(TAG, "Could not open TTS voice download", e)
        }
    }

    private fun describe(code: Int): String = when (code) {
        TextToSpeech.LANG_MISSING_DATA -> "missing data"
        TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"
        else -> "code $code"
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun complete(utteranceId: String?) {
        utteranceId?.let { pending.remove(it)?.complete(Unit) }
    }

    private companion object {
        const val TAG = "SeemaTTS"
        const val INIT_TIMEOUT_MS = 5_000L
        const val MIN_SPEAK_TIMEOUT_MS = 10_000L
        const val MS_PER_CHAR = 150L
        val HINDI: Locale = Locale("hi", "IN")
        val INDIAN_ENGLISH: Locale = Locale.forLanguageTag("en-IN")
        private val downloadOffered = AtomicBoolean(false)
    }
}
