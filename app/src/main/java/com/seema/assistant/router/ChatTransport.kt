package com.seema.assistant.router

import com.seema.assistant.apikeys.data.ApiProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The one seam between the router and the network.
 *
 * [MultiProviderRouter] owns *policy* — which provider to try, in what order, and when to give up.
 * The transport owns *protocol* — URLs, dialects, JSON shapes and status codes. Keeping them apart is
 * what makes the routing policy testable: a unit test hands the router a fake transport and can make
 * any provider answer, refuse with a 429, or throw, with no network and no Android runtime.
 */
interface ChatTransport {

    /**
     * Runs one completion on exactly one target.
     *
     * Implementations must not fall back to another provider — that is the router's job — but they may
     * try several *models* of the same provider, because a retired model is not an availability
     * problem for the provider.
     *
     * [target] carries everything provider-specific the transport needs: a built-in target resolves
     * its endpoint, model chain, dialect and auth scheme from [ProviderEndpoints] and [ApiProvider],
     * while a [CustomProviderTarget] carries its own base URL, model id and credential. The router
     * therefore does not hand over a credential at all — it hands over the target, and the target
     * knows how to be called.
     *
     * The returned [ChatCall] is the contract: a refusal comes back as [ChatCall.Failure] with its
     * HTTP status, not as an exception. A genuine transport exception may still be thrown, and the
     * router catches it. [CancellationException] must always propagate.
     */
    suspend fun complete(
        target: ProviderTarget,
        request: ChatRequest,
    ): ChatCall
}

/**
 * The single HTTP call every provider exposes.
 *
 * [url] arrives as an argument because [ProviderEndpoints] already owns each provider's endpoint, so
 * a Retrofit interface per provider would declare the same call four times. The credential travels in
 * [headers], never in the URL, so it cannot end up in a URL log or a cache key.
 *
 * No converter factory is registered on purpose: [ResponseBody] is served by Retrofit's built-in
 * converter, and both dialects are parsed with `org.json`, which is already on the platform. That
 * avoids adding Gson/Moshi to the app for two small objects — and, more importantly, avoids a
 * per-provider DTO hierarchy for shapes that disagree about almost everything.
 */
interface ProviderChatApi {

    @POST
    suspend fun complete(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

/**
 * Which wire format a provider speaks.
 *
 * Two dialects, not four: Google's `generateContent` and the OpenAI-compatible `chat/completions`
 * that Groq, Cerebras and OpenRouter all implement. Modelling it as a type keeps the difference in one
 * `when` instead of scattered across four branches.
 */
enum class ChatDialect { GEMINI, OPENAI }

/**
 * Everything the transport needs to call one provider.
 *
 * [urlTemplate] contains a `%s` for [ChatDialect.GEMINI], whose model is part of the path
 * (`/models/{model}:generateContent`). [ChatDialect.OPENAI] carries the model in the JSON body
 * instead, so its template has no placeholder.
 */
data class ProviderEndpoint(
    val urlTemplate: String,
    val models: List<String>,
    val dialect: ChatDialect,
)

/**
 * Where each provider is called, and with which models.
 *
 * This lives beside the router rather than on [ApiProvider] because [ApiProvider] answers a different
 * question — "does this credential work right now?" — and its `probeUrl` is deliberately the cheapest
 * authenticated endpoint each provider offers. A generation endpoint is the opposite of that, and
 * putting it on the key-manager enum would tempt a future caller to probe with it and spend quota.
 *
 * The model lists are **chains**, tried in order. Models get retired without notice (Groq's
 * `llama-3.3-70b-versatile` answers 404 today), and a retired model must not take a provider out of
 * the rotation. Model ids must match what the user's own key is entitled to; both the transport and
 * this table can be overridden, and the list is never a claim that a model is free.
 *
 * A [CustomProviderTarget] resolves through [forCustomProvider] instead: its endpoint, model and
 * dialect all come from what the user entered, so there is no table to look up and no model chain to
 * fall through (the user named exactly one model, and quietly asking for a different one would
 * produce either a 404 or an answer from a model they did not choose).
 */
object ProviderEndpoints {

    /** The endpoint for [target], dispatching on whether it is built in or user-added. */
    fun forTarget(target: ProviderTarget): ProviderEndpoint = when (target) {
        is BuiltIn -> forProvider(target.provider)
        is CustomProviderTarget -> forCustomProvider(target)
    }

    fun forProvider(provider: ApiProvider): ProviderEndpoint = when (provider) {
        ApiProvider.GEMINI -> ProviderEndpoint(
            urlTemplate = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
            // Mirrors BuildConfig.SEEMA_GEMINI_MODEL's default, with a dated fallback behind it.
            models = listOf("gemini-flash-latest", "gemini-2.0-flash"),
            dialect = ChatDialect.GEMINI,
        )

        ApiProvider.GROQ -> ProviderEndpoint(
            urlTemplate = "https://api.groq.com/openai/v1/chat/completions",
            // Same chain GroqBrain shipped: the primary, then two models that are still live.
            models = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            dialect = ChatDialect.OPENAI,
        )

        ApiProvider.CEREBRAS -> ProviderEndpoint(
            urlTemplate = "https://api.cerebras.ai/v1/chat/completions",
            models = listOf("llama-3.3-70b", "llama3.1-8b"),
            dialect = ChatDialect.OPENAI,
        )

        ApiProvider.OPENROUTER -> ProviderEndpoint(
            urlTemplate = "https://openrouter.ai/api/v1/chat/completions",
            models = listOf("google/gemini-2.0-flash-exp:free"),
            dialect = ChatDialect.OPENAI,
        )
    }

    /**
     * The endpoint described by a user's own provider row.
     *
     * The stored base URL is whatever the user typed for the *API root* (`https://host/v1`), and the
     * OpenAI-compatible completion path is appended here rather than being stored, so a user who
     * pastes the full `/chat/completions` URL by mistake still gets one appended path instead of two.
     * Trailing slashes are dropped first for the same reason.
     *
     * A blank base URL cannot reach this point — the repository refuses to save one and the router
     * refuses to route a target without one — so this does not carry a fallback host: inventing one
     * would send the user's credential somewhere they did not name.
     */
    fun forCustomProvider(target: CustomProviderTarget): ProviderEndpoint = ProviderEndpoint(
        urlTemplate = "${target.baseUrl.trimEnd('/')}$OPENAI_COMPLETION_PATH",
        models = listOf(target.modelId),
        dialect = ChatDialect.OPENAI,
    )
}

/** The OpenAI-compatible completion path, relative to a provider's API root. */
private const val OPENAI_COMPLETION_PATH: String = "/chat/completions"

/**
 * Placeholder base URL: a reserved TLD, so a mis-routed call fails fast instead of hitting someone
 * else's real host. Every call overrides it with `@Url`.
 */
private const val CHAT_BASE_URL: String = "https://api.seema.invalid/"

/**
 * The process-wide HTTP stack for chat completions.
 *
 * Deliberately **not** reusing `ApiKeyHttp.client()`: that client carries a 20 s call timeout, which
 * is right for a key probe and wrong for a generation, where a cold model can legitimately think for
 * longer. A separate stack also lets the chat path fail over more patiently than a "TEST" button
 * should.
 *
 * As in the probe stack, **no logging interceptor may ever be installed**: it would print the
 * `Authorization` / `x-goog-api-key` header — the user's credential — to Logcat.
 */
object ChatHttp {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Generous: a long answer on a loaded free tier is slow, not broken.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            // OkHttp only re-routes a *transport* failure here; it never re-sends an HTTP error
            // response, so enabling this cannot silently spend a rate-limited call twice.
            .retryOnConnectionFailure(true)
            .build()
    }

    private val api: ProviderChatApi by lazy {
        Retrofit.Builder()
            .baseUrl(CHAT_BASE_URL)
            .client(client)
            .build()
            .create(ProviderChatApi::class.java)
    }

    /** The shared client, exposed so a future caller can reuse the connection pool. */
    fun client(): OkHttpClient = client

    /** The shared chat interface. */
    fun api(): ProviderChatApi = api
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Reply budgets, matching the conversational limits the app already shipped with. */
private const val MAX_TOKENS = 1024
private const val TEMPERATURE = 0.6

/** An error line must stay readable when it is shown in the HUD. */
private const val MAX_ERROR_CHARS = 160

private val WHITESPACE = Regex("\\s+")

/**
 * [ChatTransport] over [ProviderChatApi], speaking both dialects and parsing with `org.json`.
 *
 * [models] overrides [ProviderEndpoints]' chain per built-in provider, so a caller whose key sees
 * different models can fix that without editing this file. A custom provider is never overridden
 * here: its model is the one the user typed, and silently substituting another would defeat the point
 * of naming a model at all.
 */
class RetrofitChatTransport(
    private val api: ProviderChatApi = ChatHttp.api(),
    private val models: Map<ApiProvider, List<String>> = emptyMap(),
) : ChatTransport {

    override suspend fun complete(
        target: ProviderTarget,
        request: ChatRequest,
    ): ChatCall {
        val endpoint = ProviderEndpoints.forTarget(target)
        val chain = builtInModels(target)?.takeIf { it.isNotEmpty() } ?: endpoint.models

        var lastFailure = ChatCall.Failure(null, "No model is configured for ${target.id}")
        for (model in chain) {
            val call = attempt(target, endpoint, model, request)
            if (call is ChatCall.Reply) return call

            lastFailure = call as ChatCall.Failure
            // Only a missing model is worth another model. Retrying a 429 or a 401 on the next model
            // would spend two more calls to learn the same thing about the account.
            if (!modelGone(lastFailure.code, lastFailure.reason)) return lastFailure
        }
        return lastFailure
    }

    /** The caller's model override for a built-in target; null for a custom one, which has no table. */
    private fun builtInModels(target: ProviderTarget): List<String>? =
        target.builtIn?.let { models[it] }

    /**
     * One request, one model, reduced to a [ChatCall].
     *
     * Only [CancellationException] is rethrown past this point: a cleared ViewModel must cancel rather
     * than be counted as a provider failure. Every other exception — [IOException] for a dead socket,
     * and anything else a malformed URL or TLS failure can raise — becomes a [ChatCall.Failure] with a
     * null code, because the router has to be able to move on and neither case is a verdict about the
     * key.
     *
     * The credential travels in the headers, never in the URL: a custom provider's base URL is user
     * input, so putting anything secret in the path would leak it into whatever logs or caches that
     * URL.
     */
    private suspend fun attempt(
        target: ProviderTarget,
        endpoint: ProviderEndpoint,
        model: String,
        request: ChatRequest,
    ): ChatCall = try {
        val url = when (endpoint.dialect) {
            ChatDialect.GEMINI -> endpoint.urlTemplate.format(model)
            ChatDialect.OPENAI -> endpoint.urlTemplate
        }
        val body = buildBody(endpoint.dialect, model, request).toString().toRequestBody(JSON_MEDIA_TYPE)
        reduce(api.complete(url, target.authHeaders(), body), endpoint.dialect, model)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ChatCall.Failure(null, e.message ?: e::class.java.simpleName)
    } catch (e: Exception) {
        ChatCall.Failure(null, e.message ?: e::class.java.simpleName)
    }

    /**
     * Turns a live [Retrofit] response into a [ChatCall].
     *
     * Both body handles are read through `use` and closed: exactly one of body/errorBody is non-null
     * for a given outcome, and an unclosed one keeps its connection checked out.
     */
    private fun reduce(
        response: Response<ResponseBody>,
        dialect: ChatDialect,
        model: String,
    ): ChatCall {
        val code = response.code()
        val payload = response.body()?.use { it.string() }
        val error = response.errorBody()?.use { it.string() }

        if (!response.isSuccessful) {
            return ChatCall.Failure(code, shortReason(code, error.orEmpty(), response.message()))
        }

        val json = try {
            JSONObject(payload.orEmpty())
        } catch (e: Exception) {
            // A 200 that is not JSON at all — a captive-portal page, a proxy. Worth falling through.
            return ChatCall.Failure(code, "Unreadable reply (${e::class.java.simpleName})")
        }

        return when (dialect) {
            ChatDialect.GEMINI -> {
                // A safety refusal is a 200 with no candidate text. It is reported with its real
                // status, never with a null code: a null code means "the request never landed", and
                // calling a content-policy refusal a network failure would mis-advise the user.
                val blocked = json.optJSONObject("promptFeedback")
                    ?.takeIf { !it.isNull("blockReason") }
                    ?.optString("blockReason")
                    ?.takeIf { it.isNotBlank() }
                if (blocked != null) {
                    ChatCall.Failure(code, "Blocked by the provider's safety filter ($blocked)")
                } else {
                    ChatCall.Reply(geminiText(json), model)
                }
            }

            ChatDialect.OPENAI -> ChatCall.Reply(openAiText(json), model)
        }
    }

    /** True when the failure means "this model is gone", the only case worth another model id. */
    private fun modelGone(code: Int?, reason: String): Boolean =
        code == 404 ||
            reason.contains("does not exist", ignoreCase = true) ||
            reason.contains("no longer", ignoreCase = true)

    /** System prompt, then the earlier conversation, then the current user turn. */
    private fun buildBody(dialect: ChatDialect, model: String, request: ChatRequest): JSONObject =
        when (dialect) {
            ChatDialect.OPENAI -> openAiBody(model, request)
            ChatDialect.GEMINI -> geminiBody(request)
        }

    /**
     * OpenAI-compatible request.
     *
     * `max_tokens` is the OpenAI spelling; Cerebras and OpenRouter accept it, and Groq's
     * OpenAI-compatible endpoint does too.
     */
    private fun openAiBody(model: String, request: ChatRequest): JSONObject = JSONObject().apply {
        put("model", model)
        put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", request.systemPrompt))
                request.history.forEach { message ->
                    // SYSTEM belongs in systemPrompt, which each dialect has its own field for; a
                    // SYSTEM turn in history is dropped rather than demoted to a user utterance.
                    if (message.role != ChatRole.SYSTEM && message.content.isNotBlank()) {
                        put(
                            JSONObject()
                                .put("role", if (message.role == ChatRole.ASSISTANT) "assistant" else "user")
                                .put("content", message.content),
                        )
                    }
                }
                put(JSONObject().put("role", "user").put("content", request.prompt))
            },
        )
        put("temperature", TEMPERATURE)
        put("max_tokens", MAX_TOKENS)
    }

    /**
     * Gemini `generateContent` request.
     *
     * The model is absent from the body because it is part of the URL path. Gemini also spells the
     * assistant's role `"model"`, and takes the system prompt in its own `systemInstruction` field
     * rather than as a message.
     */
    private fun geminiBody(request: ChatRequest): JSONObject = JSONObject().apply {
        put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", request.systemPrompt)),
            ),
        )
        put(
            "contents",
            JSONArray().apply {
                request.history.forEach { message ->
                    // SYSTEM belongs in systemPrompt, which each dialect has its own field for; a
                    // SYSTEM turn in history is dropped rather than demoted to a user utterance.
                    if (message.role != ChatRole.SYSTEM && message.content.isNotBlank()) {
                        put(
                            JSONObject()
                                .put("role", if (message.role == ChatRole.ASSISTANT) "model" else "user")
                                .put("parts", JSONArray().put(JSONObject().put("text", message.content))),
                        )
                    }
                }
                put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", request.prompt))),
                )
            },
        )
        put("generationConfig", JSONObject().put("temperature", TEMPERATURE).put("maxOutputTokens", MAX_TOKENS))
    }

    /**
     * Every `parts[].text` of the first candidate, concatenated.
     *
     * Returns "" rather than failing when there is no text: the router owns the decision that an empty
     * reply is a fallback-worthy outcome, and a safety block is already handled above.
     */
    private fun geminiText(json: JSONObject): String {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""

        val builder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            // `optString` answers the literal "null" for a JSON null, so a null part would otherwise
            // become a reply the user can hear.
            if (!part.isNull("text")) builder.append(part.optString("text"))
        }
        return builder.toString()
    }

    /** `choices[0].message.content`, falling back to the legacy `choices[0].text` shape. */
    private fun openAiText(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val message = choice.optJSONObject("message")
        if (message != null && !message.isNull("content")) {
            // A content-filtered or reasoning-only reply carries `"content": null`, which `optString`
            // would turn into the string "null" — a non-blank "answer" that would stop the fallback
            // and be spoken aloud. Reading the null explicitly makes it an empty reply instead.
            val content = message.optString("content")
            if (content.isNotBlank()) return content
        }
        return if (choice.isNull("text")) "" else choice.optString("text")
    }

    /** `"429 Rate limit reached for model ..."` from an error body, tidied up for a one-line message. */
    private fun shortReason(code: Int, errorBody: String, reason: String?): String {
        val detail = runCatching {
            JSONObject(errorBody).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty()

        val text = detail
            .ifBlank { errorBody.ifBlank { reason.orEmpty() } }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_ERROR_CHARS)

        return if (text.isEmpty()) code.toString() else "$code $text"
    }
}

/**
 * The headers a request to [this] target carries.
 *
 * A built-in target delegates to [ApiProvider.authHeaders] so its scheme — Google's bare
 * `x-goog-api-key` versus everyone else's OAuth-style bearer token — stays defined in exactly one
 * place. Every custom provider is OpenAI-compatible by construction, which is the premise of the
 * whole Custom Provider feature, so it takes a bearer token.
 *
 * File-level rather than a member of [RetrofitChatTransport] because it depends only on the target,
 * so a future second transport implementation can carry the same credential presentation instead of
 * re-deciding it. The key is trimmed here and never logged.
 */
private fun ProviderTarget.authHeaders(): Map<String, String> = when (this) {
    is BuiltIn -> authHeaders(apiKey)
    is CustomProviderTarget -> mapOf("Authorization" to "Bearer ${apiKey.trim()}")
}

