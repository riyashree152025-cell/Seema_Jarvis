package com.seema.assistant.customprovider.data

import android.content.Context
import com.seema.assistant.router.CustomProviderOrigin
import com.seema.assistant.router.CustomProviderTarget
import com.seema.assistant.router.customProviderBrainId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * What a SAVE press did, as data.
 *
 * A sealed result rather than a boolean or a thrown exception because the screen has to render the
 * failure: "the key was rejected" and "the host did not answer" are different instructions to the
 * user, and the HTTP code is the only thing that separates them. It is also what lets the JVM test
 * suite assert the exact outcome without a Toast in sight.
 */
sealed interface CustomProviderSaveResult {

    /** The provider was tested successfully and is now stored. */
    data class Saved(val row: CustomProvider) : CustomProviderSaveResult

    /**
     * Nothing was stored.
     *
     * [httpCode] is the provider's status code when there was a response, and null when the request
     * never produced one (a blank field, a bad URL, a dead network, an unusable keystore).
     */
    data class Failed(val httpCode: Int?, val reason: String) : CustomProviderSaveResult
}

/**
 * One line the screen can hand straight to a Toast.
 *
 * It exists so the screen does not re-implement the "include the code when there is one" rule in
 * three places, and so a failure is always phrased by [CustomProviderRepository], which is the code
 * that actually knows what the code means.
 */
val CustomProviderSaveResult.message: String
    get() = when (this) {
        is CustomProviderSaveResult.Saved -> "${row.name} saved"
        is CustomProviderSaveResult.Failed ->
            if (httpCode == null) reason else "$reason (HTTP $httpCode)"
    }

/**
 * The only seam the Custom Provider screen and the router depend on.
 *
 * ## Why this class exists
 *
 * It is the one place that knows a custom provider is two things at once: a row in
 * [CustomProviderDatabase] and a credential in [CustomProviderKeyVault]. Every caller — the settings
 * screen, the router loading its targets — sees one object and cannot accidentally save a row
 * without its key or delete a row and leave the key behind.
 *
 * ## Why SAVE is test-then-save instead of save-then-test
 *
 * A stored provider whose credential was never checked is a trap: the user has no way to tell a typo
 * in the key from an outage, because the list shows the row as if it worked. Testing first means the
 * database only ever contains providers that answered a real request, and a failure leaves the
 * previous state untouched instead of overwriting a working provider with a broken one.
 *
 * ## Threading
 *
 * Every method is a `suspend` function or a [Flow], so callers never block a frame: Room dispatches
 * its own work, Retrofit's suspend calls run off the main thread, and the vault reads are the only
 * small blocking step (a keystore-backed map lookup).
 */
class CustomProviderRepository(
    private val dao: CustomProviderDao,
    private val vault: CustomProviderKeyVault,
    private val probe: CustomProviderProbeApi,
    /**
     * Insert time source, injectable so a test can prove that a re-save keeps the original
     * `createdAt` — the routing order — without sleeping.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * The stored providers, re-emitted on every change, oldest first.
     *
     * The screen renders this directly; the router uses [loadRouterTargets] because it needs the
     * decrypted key joined in.
     */
    fun observeAll(): Flow<List<CustomProvider>> = dao.observeAll()

    /** One-shot snapshot of the same list, for a caller that cannot collect a flow. */
    suspend fun readAll(): List<CustomProvider> = dao.getAll()

    /**
     * Removes [row] and the credential stored for it.
     *
     * The row goes first: the list the user sees is the database, so once the row is gone the
     * provider is gone from the UI, and a vault slot with no row left to name it is unreachable.
     * [CustomProviderKeyVault.clear] is best-effort and never throws, so a keystore problem cannot
     * turn a confirmed delete into an error after the row is already removed.
     */
    suspend fun delete(row: CustomProvider) {
        dao.delete(row)
        vault.clear(row.apiKeyAlias)
    }

    /**
     * Every stored provider as a router target, with its credential decrypted.
     *
     * One target per row, in the DAO's `createdAt` order — including rows whose key is missing
     * (a device restore can lose the encrypted store while the plaintext-free database survives).
     * Such a target carries an empty `apiKey`, and the router is the layer that knows what to do
     * with a keyless provider; dropping the row here would make the provider silently disappear
     * from the HUD with nothing to explain why.
     */
    suspend fun loadRouterTargets(): List<CustomProviderTarget> =
        dao.getAll().map { row ->
            CustomProviderTarget(
                id = customProviderBrainId(row.id),
                displayName = row.name,
                baseUrl = row.baseUrl,
                // The credential is read here and nowhere else. It is never logged, and the target's
                // toString() redacts it.
                apiKey = vault.read(row.apiKeyAlias).trim(),
                modelId = row.modelId,
                origin = CustomProviderOrigin(
                    rowId = row.id,
                    apiKeyAlias = row.apiKeyAlias,
                    createdAt = row.createdAt,
                ),
            )
        }

    /**
     * Probes the provider described by the arguments and stores it only if the probe answered 2xx.
     *
     * The order of operations is the contract:
     * 1. Validate every field locally. A blank name, base URL, key or model id is a failure and
     *    costs no network call — this is what keeps a stray SAVE press from spending the user's
     *    quota on a request that could not have been valid.
     * 2. Normalize the base URL, rejecting anything that is not http(s) before OkHttp sees it.
     * 3. Probe `POST <baseUrl>/chat/completions` with `Authorization: Bearer <key>`.
     * 4. On 2xx, upsert by unique name and write the key to the vault. On anything else, write
     *    nothing and report the code.
     *
     * Nothing here ever logs or embeds the key: the failure paths report the provider's behaviour,
     * never the credential that was sent.
     */
    suspend fun testAndSave(
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): CustomProviderSaveResult {
        val providerName = name.trim()
        if (providerName.isEmpty()) return CustomProviderSaveResult.Failed(null, "Name is required")

        if (baseUrl.isBlank()) return CustomProviderSaveResult.Failed(null, "Base URL is required")
        // Normalization is a pure function so it can be unit-tested without a network stack; see
        // normalizeBaseUrl below this class.
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
            ?: return CustomProviderSaveResult.Failed(null, INVALID_BASE_URL_REASON)

        val key = apiKey.trim()
        if (key.isEmpty()) return CustomProviderSaveResult.Failed(null, "API key is required")

        val model = modelId.trim()
        if (model.isEmpty()) return CustomProviderSaveResult.Failed(null, "Model ID is required")

        val response: Response<ResponseBody> = try {
            probe.probe(
                url = "$normalizedBaseUrl$CHAT_COMPLETIONS_PATH",
                headers = mapOf(AUTHORIZATION_HEADER to "Bearer $key"),
                body = probeBody(model),
            )
        } catch (e: CancellationException) {
            // A cleared screen must cancel the request, not be told the provider is unreachable.
            throw e
        } catch (e: IOException) {
            // The transport itself failed (DNS, TLS, timeout, refused connection). The message is
            // the only diagnostic there is, and it describes the socket, not the credential.
            return CustomProviderSaveResult.Failed(null, e.message?.takeIf(String::isNotBlank) ?: NETWORK_FAILURE_REASON)
        } catch (e: Exception) {
            // A malformed @Url or a TLS failure surfacing as a RuntimeException is still a failed
            // test and never a crash; only the type is surfaced, because a stack message could
            // quote the request.
            return CustomProviderSaveResult.Failed(null, e::class.java.simpleName)
        }

        val httpCode = response.code()
        // The body is never parsed — the status code is the whole verdict — but it must still be
        // closed or the connection stays checked out. Exactly one of body/errorBody is non-null.
        response.body()?.close()
        response.errorBody()?.close()

        if (httpCode !in HTTP_SUCCESS_FIRST..HTTP_SUCCESS_LAST) {
            return CustomProviderSaveResult.Failed(httpCode, reasonForHttpCode(httpCode))
        }

        return save(providerName, normalizedBaseUrl, key, model)
    }

    /**
     * Persists a provider that has already been proven to answer.
     *
     * The vault write comes first so a failure cannot leave a row whose key was never stored, and
     * the row is then upserted by unique name: re-saving an existing name updates that row in place
     * (keeping its id and `createdAt`, so its routing position does not move) instead of colliding
     * with the unique index on `name`.
     */
    private suspend fun save(
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): CustomProviderSaveResult {
        val existing = dao.getAll().firstOrNull { it.name == name }
        val alias = existing?.apiKeyAlias?.takeIf { it.isNotBlank() } ?: customProviderKeyAlias(name)
        val row = CustomProvider(
            id = existing?.id ?: 0L,
            name = name,
            baseUrl = baseUrl,
            modelId = modelId,
            apiKeyAlias = alias,
            createdAt = existing?.createdAt ?: clock(),
        )

        return try {
            vault.write(alias, apiKey)
            val insertedId = dao.upsert(row)
            // A row that already had an id keeps it; a fresh insert adopts the key Room assigned.
            val rowId = if (row.id != 0L) row.id else insertedId
            if (rowId <= 0L) {
                CustomProviderSaveResult.Failed(null, SAVE_FAILURE_REASON)
            } else {
                CustomProviderSaveResult.Saved(row.copy(id = rowId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The message is deliberately not surfaced: a keystore or SQLite exception message can
            // quote the value it refused to handle, and that value is the user's credential.
            CustomProviderSaveResult.Failed(null, SAVE_FAILURE_REASON)
        }
    }

    companion object {

        /**
         * The production wiring: the app database's DAO, the encrypted vault and the shared probe
         * stack.
         *
         * Not a singleton itself — the database and the HTTP stack it delegates to already are, so
         * building a repository per screen is a handful of object allocations and keeps a stale
         * repository from outliving the configuration it was built for.
         */
        fun of(context: Context): CustomProviderRepository {
            val appContext = context.applicationContext
            return CustomProviderRepository(
                dao = CustomProviderDatabase.of(appContext).customProviders(),
                vault = EncryptedCustomProviderKeyVault(appContext),
                probe = CustomProviderHttp.probeApi(),
            )
        }
    }
}

/**
 * The one JSON body the probe sends: an OpenAI-compatible single-token chat request.
 *
 * `max_tokens = 1` because this is a credential check, not a conversation: the provider has to
 * accept a real chat request, and a one-token answer costs essentially nothing.
 */
private const val PING_PROMPT = "ping"

/** Path appended to the user's base URL; no trailing slash, and the base URL is stored without one. */
private const val CHAT_COMPLETIONS_PATH = "/chat/completions"

/** Header name; the value is `Bearer <key>` and is never logged. */
private const val AUTHORIZATION_HEADER = "Authorization"

/** OpenAI-compatible providers accept 200..299 as "the key works and the model exists". */
private const val HTTP_SUCCESS_FIRST = 200
private const val HTTP_SUCCESS_LAST = 299

private const val INVALID_BASE_URL_REASON = "Base URL must start with http:// or https://"
private const val NETWORK_FAILURE_REASON = "Network error"
private const val SAVE_FAILURE_REASON = "The provider could not be saved"

/**
 * A short, user-actionable reason for one refusal code.
 *
 * The code alone ("401") tells a user nothing; the reason is what turns the failure into a fix.
 * Codes 402/429 mean the credential is valid but cannot spend right now, so they are worded
 * differently from 401/403, which mean the key itself is the problem.
 */
private fun reasonForHttpCode(httpCode: Int): String = when (httpCode) {
    400 -> "The provider rejected the request"
    401 -> "Check the API key"
    403 -> "The API key is not allowed to use this model"
    404 -> "Check the base URL and model ID"
    408 -> "The provider timed out"
    429 -> "Rate limited; try again shortly"
    in 500..599 -> "The provider reported a server error"
    else -> "The provider refused the request"
}

/**
 * The OpenAI-compatible body for [modelId], with the model id escaped.
 *
 * The body is built by hand because this module carries no JSON converter; every field except the
 * user-typed model id is a constant, and [escapeForJsonString] makes the one variable part incapable
 * of breaking out of its string.
 */
private fun probeBody(modelId: String): RequestBody =
    buildString {
        append("{\"model\":\"")
        append(escapeForJsonString(modelId))
        append("\",\"messages\":[{\"role\":\"user\",\"content\":\"")
        append(PING_PROMPT)
        append("\"}],\"max_tokens\":1}")
    }.toRequestBody(JSON_MEDIA_TYPE)

/**
 * Escapes [raw] for use inside a JSON string literal.
 *
 * Pure Kotlin so a plain JVM test can prove that a model id containing a quote cannot forge a second
 * JSON field: this is the only place user input reaches the request body.
 */
internal fun escapeForJsonString(raw: String): String = buildString(raw.length) {
    for (char in raw) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
}

/**
 * The canonical form of a base URL, or null when [raw] cannot be one.
 *
 * Pure Kotlin, and internal rather than private, because "what counts as a valid base URL" is the
 * one rule a JVM unit test can pin down without a device, a database or a socket. It rejects:
 * - anything whose scheme is not exactly http or https, so a `javascript:`/`file:`/`ftp:` value
 *   never reaches OkHttp;
 * - a scheme with no host (`https://`, `https:///path`);
 * - any internal whitespace, which OkHttp would otherwise reject with an untranslatable
 *   IllegalArgumentException.
 *
 * Trailing slashes are removed because the caller appends `/chat/completions`; a base URL of
 * `https://api.example.com/v1/` must not produce a double slash.
 */
internal fun normalizeBaseUrl(raw: String): String? {
    val normalized = raw.trim().trimEnd('/')
    if (normalized.any { it.isWhitespace() }) return null

    val scheme = normalized.substringBefore("://", missingDelimiterValue = "")
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null

    val authority = normalized.substringAfter("://")
    if (authority.isEmpty() || authority.startsWith("/")) return null

    return normalized
}
