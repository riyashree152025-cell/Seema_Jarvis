package com.seema.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checks a Gemini API key by listing the models it can use. Listing models does not count
 * against the daily generate-content quota.
 */
object GeminiKeyCheck {

    private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
    private const val TIMEOUT_MS = 10_000

    /** A short result for the Settings screen, starting with ✓, ⚠ or ✗. */
    suspend fun check(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "✗ Enter an API key first."
        val connection = URL(MODELS_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // Sent as a header so the key never appears in a URL.
            connection.setRequestProperty("x-goog-api-key", apiKey.trim())

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                return@withContext "✗ Key rejected (HTTP $code): ${message ?: "unknown error"}"
            }

            val models = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("models")
            val names = (0 until (models?.length() ?: 0))
                .map { models!!.getJSONObject(it).optString("name").removePrefix("models/") }
                .toSet()
            val wanted = model.trim()
            when {
                wanted.isEmpty() -> "✓ Key works."
                wanted in names -> "✓ Key works · model \"$wanted\" is available."
                else -> "⚠ Key works, but \"$wanted\" is not in this key's model list " +
                    "(\"-latest\" aliases can still work). Flash models: " +
                    names.filter { "flash" in it }.sorted().take(5).joinToString()
            }
        } catch (e: IOException) {
            "✗ Could not reach Google: ${e.message ?: "no connection"}"
        } finally {
            connection.disconnect()
        }
    }
}
