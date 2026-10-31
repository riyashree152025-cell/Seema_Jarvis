package com.seema.assistant.apikeys.data

/**
 * The model providers SEEMA can route a turn through.
 *
 * Every field here exists to answer one question on the API Key Manager screen: "does this key
 * actually work right now?" [probeUrl] is therefore deliberately the cheapest authenticated
 * endpoint each provider offers — a model listing or a key-introspection call — so that pressing
 * TEST can never spend generation quota.
 */
enum class ApiProvider(
    /** Stable key used inside the encrypted preference file. Never change it: it would orphan
     *  every key already stored on a user's device. */
    val id: String,
    /** Name shown on the screen. */
    val displayName: String,
    /** Format hint for the field, so a pasted key is recognisable at a glance. */
    val keyHint: String,
    /** Cheap authenticated endpoint; the response body is discarded. */
    val probeUrl: String,
    /** How this provider wants the credential presented. */
    val authScheme: AuthScheme,
    /** Where the user creates or rotates the key. */
    val consoleUrl: String,
) {
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
        keyHint = "AIza…",
        // pageSize=1 keeps the payload tiny; listing models does not touch generate quota.
        probeUrl = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1",
        authScheme = AuthScheme.ApiKeyHeader("x-goog-api-key"),
        consoleUrl = "https://aistudio.google.com/apikey",
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        keyHint = "gsk_…",
        probeUrl = "https://api.groq.com/openai/v1/models",
        authScheme = AuthScheme.Bearer,
        consoleUrl = "https://console.groq.com/keys",
    ),
    CEREBRAS(
        id = "cerebras",
        displayName = "Cerebras",
        keyHint = "csk-…",
        probeUrl = "https://api.cerebras.ai/v1/models",
        authScheme = AuthScheme.Bearer,
        consoleUrl = "https://cloud.cerebras.ai",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        keyHint = "sk-or-…",
        // /models is public, so it would return 200 for any string at all; /key is the endpoint
        // that actually validates the credential and reports the credit balance.
        probeUrl = "https://openrouter.ai/api/v1/key",
        authScheme = AuthScheme.Bearer,
        consoleUrl = "https://openrouter.ai/keys",
    );

    /** Header pair this provider expects for an authenticated probe. */
    fun authHeaders(apiKey: String): Map<String, String> = authScheme.headers(apiKey)

    companion object {
        /** Resolves a stored [id] back to its provider, for keys read out of the vault. */
        fun fromId(id: String): ApiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a credential is carried on the wire.
 *
 * Kept as a type rather than a boolean because the two shapes are not interchangeable: Google
 * takes the bare key in its own header, the OpenAI-compatible providers want an OAuth-style
 * bearer token. Getting it wrong produces a 401 that looks exactly like a revoked key.
 */
sealed interface AuthScheme {

    fun headers(apiKey: String): Map<String, String>

    /** e.g. Google's `x-goog-api-key`, which also keeps the key out of request URLs and logs. */
    data class ApiKeyHeader(val headerName: String) : AuthScheme {
        override fun headers(apiKey: String): Map<String, String> =
            mapOf(headerName to apiKey.trim())
    }

    /** `Authorization: Bearer <key>` — Groq, Cerebras and OpenRouter. */
    data object Bearer : AuthScheme {
        override fun headers(apiKey: String): Map<String, String> =
            mapOf("Authorization" to "Bearer ${apiKey.trim()}")
    }
}
