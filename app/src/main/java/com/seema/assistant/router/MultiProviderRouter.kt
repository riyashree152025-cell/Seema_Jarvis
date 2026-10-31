package com.seema.assistant.router

import android.content.Context
import android.util.Log
import com.seema.assistant.apikeys.data.ApiKeyVault
import com.seema.assistant.apikeys.data.ApiProvider
import com.seema.assistant.apikeys.data.EncryptedApiKeyVault
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Log tag for the router; kept free of key material so it stays safe to grep. */
private const val ROUTER_TAG = "MultiProviderRouter"

/**
 * Where the router narrates what it did.
 *
 * It is a seam rather than a direct [Log] call because a plain JVM unit test runs against a stubbed
 * `android.jar`, where every [Log] method throws `RuntimeException("Stub!")`. Without this, exercising
 * the fallback policy — including the blank-prompt path, which logs — would need Robolectric or a
 * device. Android code passes [RouterLogger.LOGCAT] (the default); a test passes [RouterLogger.NONE]
 * or collects the lines to assert on.
 */
fun interface RouterLogger {

    /** Records one routing decision. Must never throw. */
    fun log(message: String)

    companion object {

        /** Discards everything: the right choice for a test. */
        val NONE: RouterLogger = RouterLogger { }

        /**
         * Writes to Logcat at debug level.
         *
         * The [Log] call is wrapped because the same code has to survive a JVM unit test, where
         * `android.util.Log` is a stub that throws. A swallowed log line is the correct trade there;
         * a failed log must never take a turn down.
         */
        val LOGCAT: RouterLogger = RouterLogger { message ->
            runCatching { Log.d(ROUTER_TAG, message) }
        }
    }
}

/**
 * The Smart Router: one conversational turn, automatically routed to whichever provider can serve it.
 *
 * ## Why it exists
 *
 * The app's Gemini key is a free-tier key with a small daily allowance. When it runs out, Gemini
 * answers nothing at all — not even "good morning" — and before this class the app had a hard-coded
 * special case for exactly one fallback. This generalises that into a policy: an ordered list of
 * providers, a key per provider from [ApiKeyVault], and a turn that moves down the list the moment a
 * provider refuses it.
 *
 * ## The policy
 *
 * 1. **Priority** — [DEFAULT_PRIORITY] is Gemini, then Groq, then Cerebras. Any Custom Providers the
 *    user has added are appended after them, in the order they were saved, so a provider the user
 *    added is a real fallback rather than a decoration.
 * 2. **Automatic fallback** — [executeChat] tries each target in order. A 429 (quota), a 402 (out of
 *    credit), a 401/403, a 5xx, an empty 200, a missing key and a thrown exception all mean the same
 *    thing to the router: *try the next one*. Only a real answer stops the sweep.
 * 3. **Manual switch** — pass a `providerId` to [executeChat] and that target is tried first; the
 *    rest of the list stays behind it, so a manual choice cannot strand the user on a dead provider.
 *    The id may name a built-in provider (`"groq"`) or a custom one (`"custom:3"`).
 * 4. **State** — [state] and [activeTarget] are [StateFlow]s the HUD binds to, so the badge reads
 *    `Brain: Groq` the moment Groq takes over — or the custom provider's own name.
 *
 * ## Custom Providers
 *
 * A Custom Provider is an OpenAI-compatible endpoint the user entered on the Custom Provider screen.
 * The router learns about them through [customSources] and treats every one it can read as the last
 * link in the fallback chain. Two consequences are worth stating because they are the reason the
 * design looks the way it does:
 *
 * - The router does **not** read a custom provider's credential from [ApiKeyVault]. The target the
 *   source hands over already carries its key, joined to the row by the store that owns both. That is
 *   what lets a runtime-created provider be routed at all, and it means deleting a custom provider
 *   leaves nothing behind in the built-in store.
 * - A store that cannot be read is **not** silently treated as "no custom providers". Swallowing the
 *   error would quietly turn a user's configured fallback into a turn that fails, with nothing in the
 *   log to explain it; the failure propagates instead, exactly as an unreadable built-in vault does.
 *
 * ## Usage
 *
 * ```kotlin
 * val router = MultiProviderRouter.of(context)          // or inject vault + transport in a test
 * val result = router.executeChat("good morning")       // automatic
 * val forced = router.executeChat("good morning", "groq") // manual, still falls back
 * ```
 *
 * ## Threading and lifetime
 *
 * The class holds no coroutine scope and starts no coroutine of its own: [executeChat] is a plain
 * `suspend` function, so the caller's scope owns cancellation and the router cannot leak. The
 * credential read hops to [Dispatchers.IO] because opening the encrypted store can build an
 * AndroidKeyStore key and touch the disk, so [executeChat] is safe to call straight from a screen.
 * A cancelled turn rethrows [CancellationException] rather than being mistaken for a provider
 * failure, and resets [RouterState.status] so the HUD cannot stick on "routing".
 *
 * Overlapping turns are supported. [of] hands out one process-wide router and both the HUD and the
 * voice service can call [executeChat], so sweeps are numbered: an overtaken sweep stops publishing
 * and the newest sweep owns the state. That is what keeps [activeTarget] and [state]'s `activeTarget`
 * from ever describing two different providers.
 *
 * @param vault the credential store for the built-in providers; the router reads keys and never
 *   writes them. A custom provider's key never comes from here.
 * @param transport the network seam. Production passes [RetrofitChatTransport]; a test passes a fake.
 * @param priority built-in providers in the order they should be tried. Duplicates are collapsed, and
 *   an empty list falls back to [DEFAULT_PRIORITY].
 * @param customSources where the user's own providers are read from. Each is asked on every turn, so
 *   a provider added on the Custom Provider screen is routable on the very next turn with no restart
 *   and no cache to invalidate; the default is [NO_CUSTOM_PROVIDERS], which is what a unit test that
 *   does not care about the feature should pass.
 * @param logger where routing decisions are narrated.
 */
class MultiProviderRouter(
    private val vault: ApiKeyVault,
    private val transport: ChatTransport,
    priority: List<ProviderTarget> = DEFAULT_PRIORITY,
    private val customSources: List<CustomProviderSource> = emptyList(),
    private val logger: RouterLogger = RouterLogger.LOGCAT,
) {

    /**
     * The state the HUD binds to. The backing field is private: every write goes through [publish], so
     * the badge and the status cannot drift apart.
     */
    private val _state = MutableStateFlow(
        RouterState(priority = cleanedUp(priority).ifEmpty { DEFAULT_PRIORITY }),
    )

    /** Status, attempt log, configured order and the active target. */
    val state: StateFlow<RouterState> = _state.asStateFlow()

    /**
     * The target that served the last successful turn, or null before the first one.
     *
     * Convenience for a UI that only needs the badge: it is always equal to
     * `state.value.activeTarget`, written by the same [publish].
     */
    private val _activeTarget = MutableStateFlow<ProviderTarget?>(null)

    /** Which provider is currently the brain — `null` until a provider succeeds. */
    val activeTarget: StateFlow<ProviderTarget?> = _activeTarget.asStateFlow()

    /**
     * The built-in provider that served the last successful turn, or null before the first one *or*
     * when a custom provider served it.
     *
     * Kept because a caller that stores a `SpokenTurn` has nowhere to put a custom provider yet; it is
     * derived from [activeTarget] on each read and never written separately, so the two cannot
     * disagree.
     */
    val activeBuiltIn: ApiProvider? get() = _activeTarget.value?.builtIn

    /**
     * The built-in providers, in the order they will be tried.
     *
     * Custom providers are deliberately **not** included: this is the configured rotation, which is
     * what `setPriority` controls, whereas [RouterState.targets] is the rotated *plus custom* order the
     * next sweep will actually walk.
     */
    val priority: List<ProviderTarget> get() = _state.value.priority

    /**
     * Monotonic id of the newest sweep.
     *
     * Read by [publish]: a sweep that a later turn has overtaken must not describe the state of that
     * later turn, and this counter is what tells the two apart.
     */
    private val sweeps = AtomicLong()

    /**
     * Replaces the routing order.
     *
     * An empty list is ignored rather than accepted, because "no providers" is never what the user
     * meant by reordering and silently disabling every brain would be worse than doing nothing. Custom
     * providers are unaffected: they are added to whatever order this sets, not part of it.
     */
    fun setPriority(providers: List<ProviderTarget>) {
        val requested = cleanedUp(providers)
        if (requested.isEmpty()) {
            logger.log("Ignoring an empty priority list; keeping ${_state.value.priority.size} providers")
            return
        }
        publish(
            status = _state.value.status,
            attempts = _state.value.lastAttempts,
            priority = requested,
        )
    }

    /**
     * Records that [provider] served a turn which did **not** come through [executeChat].
     *
     * The voice service sends tool-carrying turns straight to its ADK Gemini agent, because this
     * router is conversational and cannot call the phone's tools. Without this the HUD badge would
     * keep naming whichever provider served the last *routed* turn — a different question from "who
     * just answered". Nothing was routed here, so [RouterState.lastAttempts] is left untouched.
     *
     * The status becomes [RouterState.Status.READY]: a served turn is a served turn, and leaving a
     * previous sweep's EXHAUSTED status on screen would keep an old outage in front of the user.
     */
    fun recordExternalReply(provider: ApiProvider) {
        recordExternalReply(BuiltIn(provider))
    }

    /**
     * Records that [target] served a turn which did **not** come through [executeChat].
     *
     * The [ApiProvider] overload exists because the voice service knows the enum by name; this one
     * exists so a caller that has read its target from a store — a future non-routed custom provider
     * path — can report it without inventing an enum constant.
     */
    fun recordExternalReply(target: ProviderTarget) {
        publish(
            status = RouterState.Status.READY,
            attempts = _state.value.lastAttempts,
            active = target,
        )
    }

    /**
     * Runs one turn from a bare prompt with [DEFAULT_SYSTEM_PROMPT], falling back through the
     * provider list until somebody answers.
     *
     * This is the convenience form for a caller that has nothing but the user's words. A caller
     * that needs its own persona or earlier turns — the voice service, whose fallback brain must be
     * told it has no tools — passes a [ChatRequest] to the overload instead.
     *
     * @param prompt the user's current turn. A blank prompt is refused without touching the vault or
     *   the network, and comes back as [ChatResult.AllFailed] with **no** attempts — "nothing was
     *   tried" is distinguishable from "everything was tried and failed".
     * @param providerId a [ProviderTarget.id] to try first, or null/blank/unknown for the configured
     *   order. A built-in id (`"groq"`) and a custom provider's id (`"custom:3"`) are both accepted. An
     *   unknown id is not an error: it degrades to automatic routing rather than failing a turn over a
     *   stale preference — which is also what happens when a custom provider the user selected has
     *   since been deleted.
     * @return [ChatResult.Success] with the completion and the full attempt trail, or
     *   [ChatResult.AllFailed] with one entry per provider that was tried.
     */
    suspend fun executeChat(prompt: String, providerId: String? = null): ChatResult =
        executeChat(ChatRequest(prompt = prompt), providerId)

    /**
     * Runs one turn as [request], falling back through the provider list until somebody answers.
     *
     * [request] is forwarded to the transport as it was built: the router does not rewrite the
     * prompt, the persona or the history, so a caller that supplies a persona gets exactly that
     * persona on every provider in the sweep. Only the blank-prompt check is applied to it.
     *
     * The custom-provider store is read before the first attempt of every turn, so a provider the user
     * has just saved is routable immediately and one they have just deleted stops being routed. The
     * read is deliberately *not* cached: a turn already costs an encrypted-vault read per built-in
     * provider, and a stale route to a provider the user deleted is a worse failure than one extra
     * indexed query.
     *
     * @param request the turn to send: prompt, system prompt and earlier conversation.
     * @param providerId as in the string overload.
     * @return as in the string overload.
     */
    suspend fun executeChat(request: ChatRequest, providerId: String? = null): ChatResult {
        if (request.prompt.isBlank()) {
            logger.log("Ignoring a blank prompt; no provider was called")
            return ChatResult.AllFailed(emptyList())
        }

        val sweep = beginSweep()
        val custom = loadCustomTargets()
        val order = orderFor(providerId, custom)
        publish(
            status = RouterState.Status.ROUTING,
            attempts = emptyList(),
            custom = custom,
            sweep = sweep,
        )

        val attempts = ArrayList<ProviderAttempt>(order.size)

        try {
            for (target in order) {
                val usable = hasUsableCredential(target)
                if (!usable) {
                    // Never send a request with an empty credential: it would burn a round trip to
                    // earn a 401 that we already know the answer to. For a built-in provider this also
                    // covers the vault being unreadable, which is the same instruction to the user.
                    attempts += ProviderAttempt(target, ProviderFailure.NoKey(target))
                    logger.log("${target.id}: no key stored, skipping")
                    continue
                }

                val call = try {
                    transport.complete(target, request)
                } catch (e: CancellationException) {
                    // The caller went away — this is not a provider failure. Put the state back so a
                    // cancelled turn cannot leave the HUD stuck on "routing", then let it propagate.
                    publish(status = RouterState.Status.IDLE, attempts = attempts, sweep = sweep)
                    throw e
                } catch (e: Exception) {
                    // The whole point of the router: a thrown exception is a failed *attempt*, not a
                    // failed turn. Errors (OutOfMemoryError and friends) are deliberately not caught —
                    // swallowing those would hide a real crash behind a provider fallback.
                    ChatCall.Failure(null, e.message ?: e::class.java.simpleName)
                }

                when (call) {
                    is ChatCall.Reply -> {
                        val text = call.text.trim()
                        if (text.isEmpty()) {
                            // A reasoning model can spend its whole budget thinking and return no
                            // visible text, which is a reason to try the next provider, not to stop.
                            attempts += ProviderAttempt(target, ProviderFailure.EmptyReply(target))
                            logger.log("${target.id}: empty reply, falling through")
                            continue
                        }

                        attempts += ProviderAttempt(target, null)
                        val completion = ChatCompletion(target, text, call.model)
                        publish(
                            status = RouterState.Status.READY,
                            attempts = attempts,
                            active = target,
                            custom = custom,
                            sweep = sweep,
                        )
                        logger.log("${target.id}: served the turn on ${call.model}")
                        return ChatResult.Success(completion, attempts)
                    }

                    is ChatCall.Failure -> {
                        val failure = failureFor(target, call)
                        attempts += ProviderAttempt(target, failure)
                        logger.log("${target.id}: ${failure.detail} — falling through")
                    }
                }
            }

            // The active target is intentionally left alone: a transient outage must not wipe the badge.
            publish(
                status = RouterState.Status.EXHAUSTED,
                attempts = attempts,
                custom = custom,
                sweep = sweep,
            )
            logger.log("Every provider failed (${attempts.size} attempted)")
            return ChatResult.AllFailed(attempts)
        } finally {
            // A sweep can escape without reaching its terminal publish — a store that throws while a
            // credential is read, or an Error. Settling the state here is what stops the HUD spinning
            // on "routing" forever. Every normal path has already published, so this is a no-op for
            // them, and an overtaken sweep is a no-op because publish() ignores a stale id.
            if (_state.value.status == RouterState.Status.ROUTING) {
                publish(status = RouterState.Status.IDLE, attempts = attempts, sweep = sweep)
            }
        }
    }

    /**
     * Reads the user's Custom Providers, in a deliberate, stable order.
     *
     * Ordering by `createdAt` (then by row id, so two rows saved inside the same millisecond still
     * have one order) makes the fallback chain reproducible: a sweep does not depend on what order the
     * database happened to return rows in, and the user's oldest provider is tried before the newest.
     *
     * A source that throws is **not** swallowed. Treating an unreadable store as "no custom providers"
     * would turn a configured fallback into a silent gap in the chain, and the user would see a failed
     * turn with nothing in the log to explain why; letting it escape matches how an unreadable built-in
     * vault already behaves, and the sweep's `finally` still settles the HUD.
     */
    private suspend fun loadCustomTargets(): List<CustomProviderTarget> =
        customSources.flatMap { it.load() }.sortedWith(CUSTOM_PROVIDER_ORDER)

    /**
     * The order to try, given the caller's preference.
     *
     * A requested target goes first but is **removed** from the tail rather than left in it, so a
     * manual choice can never cause the same provider to be attempted twice in one sweep. A request for
     * a provider outside the configured order (OpenRouter, which is routable but not in the default
     * list; or any custom provider) is honoured and simply prepended.
     */
    private fun orderFor(
        providerId: String?,
        custom: List<CustomProviderTarget>,
    ): List<ProviderTarget> {
        val configured = _state.value.priority.ifEmpty { DEFAULT_PRIORITY }
        val tail = configured + custom
        val requestedId = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return tail

        // A built-in id is resolved in the enum, a custom one in what the store just returned. An id
        // that matches neither is "no manual choice" — see the executeChat KDoc for why that is not an
        // error.
        val requested = resolved(requestedId, custom) ?: return tail
        return listOf(requested) + tail.filterNot { it.id == requested.id }
    }

    /** The target [id] names, or null when it names nothing this turn can route to. */
    private fun resolved(
        id: String,
        custom: List<CustomProviderTarget>,
    ): ProviderTarget? =
        ApiProvider.fromId(id)?.asTarget()
            ?: custom.firstOrNull { it.id == id }

    /**
     * Whether [target] has a credential worth spending a request on.
     *
     * A built-in target is looked up in the vault; a [CustomProviderTarget] carries its own key, so
     * this is a blank check rather than a second read — reading the vault for a runtime-created
     * provider is exactly what this feature cannot do.
     */
    private suspend fun hasUsableCredential(target: ProviderTarget): Boolean = when (target) {
        is BuiltIn -> readKeyFor(target) != null
        is CustomProviderTarget -> target.apiKey.isNotBlank()
    }

    /**
     * The stored credential for [provider], trimmed, or null when there is nothing usable.
     *
     * The read hops to [Dispatchers.IO] because [EncryptedApiKeyVault] may build an AndroidKeyStore
     * key and open an encrypted preference file on first use, and the natural caller of
     * [executeChat] is a screen. A store that throws is reported as "no key" for that one provider
     * rather than failing the whole turn: the sweep can still reach a provider whose credential is
     * readable, and the user-facing fix (re-enter or reset the key) is the same either way.
     */
    private suspend fun readKeyFor(provider: ApiProvider): String? = try {
        withContext(Dispatchers.IO) { vault.read(provider).trim() }.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log("${provider.id}: key store unreadable (${e.message ?: e::class.java.simpleName})")
        null
    }

    /** Starts a new sweep and returns its id; see [publish]. */
    private fun beginSweep(): Long = sweeps.incrementAndGet()

    /** Maps a transport refusal onto the failure the HUD reasons about. */
    private fun failureFor(target: ProviderTarget, call: ChatCall.Failure): ProviderFailure {
        val code = call.code
        val detail = call.reason.ifBlank { code?.toString() ?: "Unknown failure" }

        return when {
            // No status at all: the request never landed.
            code == null -> ProviderFailure.Transport(target, detail)
            code == HTTP_TOO_MANY_REQUESTS || code == HTTP_PAYMENT_REQUIRED ->
                ProviderFailure.QuotaExceeded(target, code, detail)
            code == HTTP_BAD_REQUEST || code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                ProviderFailure.Rejected(target, code, detail)
            // A 2xx that carried no usable text — an empty completion or a Gemini safety block. A
            // response did arrive, so this is not a transport failure; it is simply not an answer.
            code in HTTP_OK -> ProviderFailure.EmptyReply(target, detail)
            else -> ProviderFailure.HttpError(target, code, detail)
        }
    }

    /**
     * The single writer of both flows, and the reason [activeTarget] can be trusted: no code path
     * updates the status without also updating the badge that is supposed to describe it.
     *
     * Synchronised because two writes to two flows are not one atomic action — a concurrent sweep
     * could otherwise interleave between them and leave the badge permanently naming a different
     * provider than the state does.
     *
     * [sweep] is the id of the sweep that is publishing. A sweep already overtaken by a newer turn is
     * dropped entirely, so a slow failure cannot overwrite a newer success. Configuration changes
     * (via [setPriority]) default to the current id and therefore always apply.
     *
     * [custom] defaults to the last published list rather than to empty: a caller reporting something
     * other than a sweep (a reorder, an externally served turn) must not silently erase the custom
     * providers the HUD is displaying.
     */
    @Synchronized
    private fun publish(
        status: RouterState.Status,
        attempts: List<ProviderAttempt>,
        active: ProviderTarget? = _activeTarget.value,
        priority: List<ProviderTarget> = _state.value.priority,
        custom: List<CustomProviderTarget> = _state.value.customProviders,
        sweep: Long = sweeps.get(),
    ) {
        if (sweep != sweeps.get()) return

        _activeTarget.value = active
        _state.value = RouterState(
            activeTarget = active,
            status = status,
            lastAttempts = attempts,
            priority = priority,
            customProviders = custom,
        )
    }

    companion object {

        /**
         * Provider order the router ships with: Gemini first because it is the app's configured brain,
         * then Groq and Cerebras as the separately metered fallbacks.
         *
         * OpenRouter is deliberately absent: it is routable by id but is not part of the default
         * rotation, so a user who has not configured it never pays for it with a wasted attempt.
         */
        val DEFAULT_PRIORITY: List<ProviderTarget> = listOf(
            ApiProvider.GEMINI,
            ApiProvider.GROQ,
            ApiProvider.CEREBRAS,
        ).map { it.asTarget() }

        /**
         * A `createdAt`-then-id ordering for custom providers.
         *
         * The id is the tiebreaker because two rows saved in the same millisecond are otherwise
         * unordered, which would make the fallback chain depend on the database's return order.
         */
        private val CUSTOM_PROVIDER_ORDER: Comparator<CustomProviderTarget> =
            compareBy({ it.origin.createdAt }, { it.origin.rowId })

        @Volatile
        private var shared: MultiProviderRouter? = null

        /**
         * The process-wide router, built from the encrypted vault, the live Retrofit transport and the
         * Custom Provider store.
         *
         * Shared on purpose: the HUD and the voice service must observe the *same* [state] flow, or the
         * badge would show a different brain than the one answering. Only the application context is
         * retained, so this cannot leak an Activity.
         *
         * The custom-provider source is resolved here, once, because it is what reads the database; a
         * store that cannot be opened degrades to [NO_CUSTOM_PROVIDERS] with a warning rather than
         * taking the whole assistant down.
         */
        fun of(context: Context): MultiProviderRouter =
            shared ?: synchronized(this) {
                val appContext = context.applicationContext
                shared ?: MultiProviderRouter(
                    vault = EncryptedApiKeyVault(appContext),
                    transport = RetrofitChatTransport(),
                    customSources = listOf(CustomProviderSource.of(appContext)),
                ).also { shared = it }
            }

        /** Collapses duplicates while preserving the caller's order. */
        private fun cleanedUp(providers: List<ProviderTarget>): List<ProviderTarget> =
            providers.distinct()
    }
}

/**
 * Where the router reads the user's Custom Providers from.
 *
 * An interface rather than a direct Room dependency because the routing package must keep importing no
 * Android, Room or Retrofit type — that is what lets the whole routing contract, custom providers
 * included, be exercised by a plain JVM unit test with no device and no database. The production
 * implementation is [CustomProviderSource.of], which adapts the `customprovider.data` repository onto
 * this shape; a test passes a fake that returns a fixed list.
 *
 * [load] is a `suspend` function that runs on the caller's dispatcher and may do real I/O (a database
 * query plus a decryption per row), so an implementation must not block the main thread on its own —
 * the router calls it from wherever [MultiProviderRouter.executeChat] was called, and the repository
 * already hops to [Dispatchers.IO].
 */
fun interface CustomProviderSource {

    /** Every custom provider that can be routed right now, in any order; the router sorts them. */
    suspend fun load(): List<CustomProviderTarget>

    companion object {

        /**
         * The production source: the Room-backed Custom Provider store.
         *
         * Resolved reflectively, and only when the router is first built, for one reason: the router is
         * constructed by a plain JVM unit test that has no Android runtime, and a hard reference to the
         * database class would make *loading this class* fail there. A test that cares about custom
         * providers passes its own [CustomProviderSource]; a test that does not gets an empty list.
         *
         * Reflection is a real cost, and it is paid once per process. It is the same trade the app
         * already makes for `EncryptedApiKeyVault`: prefer a slower construction over a class the
         * routing contract cannot be tested against.
         */
        fun of(context: Context): CustomProviderSource {
            val appContext = context.applicationContext
            val delegate = runCatching {
                val repositoryClass =
                    Class.forName("com.seema.assistant.customprovider.data.CustomProviderRepository")
                val factory = repositoryClass.getMethod("of", Context::class.java)
                val load = repositoryClass.getMethod("loadRouterTargets")
                val repository = factory.invoke(null, appContext)
                CustomProviderSource { loadTargets(load, repository) }
            }.onFailure { error ->
                Log.w(
                    ROUTER_TAG,
                    "Custom Provider store unavailable (${error::class.java.simpleName}); " +
                        "the router will run without custom providers",
                )
            }.getOrNull()

            return delegate ?: NO_CUSTOM_PROVIDERS
        }

        /**
         * Invokes the repository's loader and trusts its declared return type.
         *
         * The unchecked cast is the price of the reflective seam: the alternative is a compile-time
         * dependency from the routing package onto Room, which is the thing being avoided. It is
         * guarded by the same `runCatching` that built the source — a signature change on either side
         * degrades the router to "no custom providers" instead of crashing it.
         */
        @Suppress("UNCHECKED_CAST")
        private fun loadTargets(
            load: java.lang.reflect.Method,
            repository: Any,
        ): List<CustomProviderTarget> =
            load.invoke(repository) as? List<CustomProviderTarget> ?: emptyList()
    }
}

/**
 * A source with nothing in it: the right default for a unit test and the honest fallback when the
 * Custom Provider store cannot be opened at all.
 */
val NO_CUSTOM_PROVIDERS: CustomProviderSource = CustomProviderSource { emptyList() }

/** The success class: a response arrived, but it may still have carried no usable text. */
private val HTTP_OK = 200..299

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_TOO_MANY_REQUESTS = 429
