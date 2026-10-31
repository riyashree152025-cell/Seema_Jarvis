package com.seema.assistant

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fallback brain: plain conversation through Groq's OpenAI-compatible chat completions API.
 *
 * This is deliberately **not** the primary brain, and callers should only reach for it after
 * [SeemaAgent] has reported a quota failure. The app ships a Gemini free-tier key that allows about
 * 20 requests a day; once that is used up Gemini answers nothing at all — not even "good morning" —
 * because the same key drives every turn. A second, separately metered provider keeps the assistant
 * conversational when that happens.
 *
 * It has **no tool access**. Groq sees only a system prompt and the conversation text, so it can
 * chat but it cannot read the weather, send an SMS, set an alarm or touch the phone in any way.
 * Any request that needs a tool must go through [SeemaAgent]; this object is the safety net for the
 * conversational remainder, not a replacement for the tool-calling agent.
 *
 * The key is read straight from the `seema_ai` SharedPreferences file — the same file that
 * `AiSettings(context)` owns — under `groq_api_key`. Reading the preference directly, instead of
 * adding a property to [AiSettings], keeps this file self-contained: a setter can be added to the
 * settings UI later and this object will pick the value up without any change here.
 */
object GroqBrain {

    private const val TAG = "GroqBrain"

    /** Groq's general-purpose Llama model: strong enough for speech, fast enough for a reply. */
    /** Primary Groq model. Verified against the live /models endpoint of the user's key. */
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"

    /**
     * Model fallback chain, tried in order.
     *
     * Groq retires models without notice: llama-3.3-70b-versatile returns 404 today, which is
     * exactly the failure this list exists to absorb. The first model that answers wins.
     */
    val MODEL_CHAIN = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b")

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    /** Connect and read timeout, in milliseconds; a stalled call gives up instead of holding the turn. */
    private const val TIMEOUT_MS = 20_000

    /** Spoken replies are short; the caller truncates for speech anyway. */
    private const val MAX_REPLY_CHARS = 600

    /** An error line must stay readable when it is spoken or shown in a toast. */
    private const val MAX_ERROR_CHARS = 160

    private const val MAX_TOKENS = 1024
    private const val TEMPERATURE = 0.6

    /** The SharedPreferences file that also backs [AiSettings]. */
    private const val PREFS_FILE = "seema_ai"

    /** The preference a Groq key is expected under; written by the settings screen. */
    private const val KEY_GROQ_API_KEY = "groq_api_key"

    @Volatile
    private var lastErrorValue: String? = null

    /**
     * Why the last [ask] failed, or null after a successful call. Read-only outside this object;
     * a private backing field plus a getter keeps `GroqBrain.lastError` observable but not writable.
     */
    var lastError: String?
        get() = lastErrorValue
        private set(value) {
            lastErrorValue = value
        }

    /** The stored Groq key, or "" when the user has not entered one. */
    private fun groqKey(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_GROQ_API_KEY, "")
            .orEmpty()
            .trim()

    /** True when a Groq API key is stored, so this fallback can be attempted at all. */
    suspend fun available(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            groqKey(context).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the Groq key", e)
            false
        }
    }

    /**
     * Runs one conversational turn on Groq and returns the assistant's reply, capped at
     * [MAX_REPLY_CHARS] characters.
     *
     * [systemPrompt] sets the persona and [userMessage] is the current turn. [history] holds the
     * earlier turns as role-to-text pairs ("user" or "assistant"), oldest first, and is inserted
     * between the system prompt and [userMessage] so Groq can follow the conversation.
     *
     * Never throws and never hangs: both socket timeouts are [TIMEOUT_MS], and a missing key, a
     * dead network, unreadable JSON or an HTTP error all come back as a line starting with
     * "ERROR:" (also recorded in [lastError]). [SeemaAgent.runAgent] already uses that same
     * convention, so the caller can test one prefix for both brains.
     */
    suspend fun ask(
        context: Context,
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val key = try {
            groqKey(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the Groq key", e)
            ""
        }
        if (key.isEmpty()) {
            lastError = "No Groq API key configured"
            return@withContext "ERROR: No Groq API key. Add one in the app settings."
        }

        // Model fallback: a retired model degrades to the next entry instead of killing the
        // fallback brain entirely.
        for ((index, model) in MODEL_CHAIN.withIndex()) {
        try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages(systemPrompt, userMessage, history))
                    .put("temperature", TEMPERATURE)
                    .put("max_tokens", MAX_TOKENS)

                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                if (code >= 400) {
                    // Groq explains refusals (bad key, rate limit, unknown model) in this body.
                    val errorBody = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    val error = "ERROR: ${shortMessage(code, errorBody, connection.responseMessage)}"
                    lastError = error
                    Log.w(TAG, error)
                    if (modelGone(code, error) && index < MODEL_CHAIN.lastIndex) continue
                    return@withContext error
                }

                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val reply = JSONObject(responseText)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .trim()
                if (reply.isEmpty()) {
                    // A reasoning model can spend the whole budget thinking and return no
                    // visible text; that is worth retrying on the next model, not a failure.
                    lastError = "Groq returned an empty reply"
                    Log.w(TAG, "Groq returned no message content")
                    if (index < MODEL_CHAIN.lastIndex) continue
                    return@withContext "ERROR: Groq returned an empty reply"
                }

                lastError = null
                Log.d(TAG, "🤖 $reply")
                reply.take(MAX_REPLY_CHARS)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            lastError = message
            Log.e(TAG, "Groq request failed", e)
            if (index < MODEL_CHAIN.lastIndex) continue
            return@withContext "ERROR: $message"
        }
        }
        "ERROR: Groq is not answering right now."
    }

    /** True when the failure is a retired model rather than a key or quota problem. */
    private fun modelGone(code: Int, error: String): Boolean =
        code == 404 || error.contains("does not exist", ignoreCase = true) ||
            error.contains("no longer", ignoreCase = true)

    /** System prompt, then the earlier conversation, then the current user turn. */
    private fun messages(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>>
    ): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { (role, text) ->
            if (text.isNotBlank()) {
                put(
                    JSONObject()
                        .put("role", if (role.equals("assistant", ignoreCase = true)) "assistant" else "user")
                        .put("content", text)
                )
            }
        }
        put(JSONObject().put("role", "user").put("content", userMessage))
    }

    /** "429 Rate limit reached for model ..." from an error body, tidied up for a one-line message. */
    private fun shortMessage(code: Int, errorBody: String, reason: String?): String {
        val detail = runCatching {
            JSONObject(errorBody).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty()
        val text = detail.ifBlank { errorBody.ifBlank { reason.orEmpty() } }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_ERROR_CHARS)
        return if (text.isEmpty()) code.toString() else "$code $text"
    }

    private val WHITESPACE = Regex("\\s+")
}
