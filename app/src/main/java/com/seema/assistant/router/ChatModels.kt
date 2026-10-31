package com.seema.assistant.router

import com.seema.assistant.apikeys.data.ApiProvider

/**
 * The provider-agnostic vocabulary of the Smart Router.
 *
 * Before this package existed, every brain spoke its own dialect: [com.seema.assistant.GroqBrain]
 * returned a reply string that began with `"ERROR:"` on failure, and `SeemaAgent` reported quota
 * problems out of band. A caller that needed to know *why* a turn failed had to parse prose.
 *
 * These types replace that convention. A failure is a sealed [ProviderFailure] carrying the HTTP
 * status where one exists, so the UI can tell "your key is wrong" apart from "Gemini is out of free
 * quota" — the same distinction [com.seema.assistant.apikeys.data.ProviderStatus] already draws for
 * the API Key Manager screen.
 *
 * Every "which provider" field is a [ProviderTarget] rather than an
 * [com.seema.assistant.apikeys.data.ApiProvider], because the app can now route a provider the user
 * added themselves as a database row — see [ProviderTarget] for why an enum cannot represent one.
 *
 * Nothing in this file imports Android, OkHttp, Retrofit or `org.json`, which is what lets the
 * routing contract be exercised by a plain JVM unit test with no device and no network.
 */

/** Who authored one turn of the conversation. */
enum class ChatRole { SYSTEM, USER, ASSISTANT }

/**
 * One conversation turn, independent of how a given provider spells it.
 *
 * The transport, not the caller, translates [ChatRole.ASSISTANT] to Gemini's `"model"` role and to
 * `"assistant"` everywhere else.
 */
data class ChatMessage(val role: ChatRole, val content: String)

/**
 * Everything one routing attempt needs to build a provider request.
 *
 * [prompt] is the current turn and is deliberately a required, non-defaulted property: a caller that
 * forgets the prompt should fail to compile, not silently send an empty conversation.
 *
 * [history] is oldest-first and excludes [prompt]. [systemPrompt] defaults to
 * [DEFAULT_SYSTEM_PROMPT] because every one of the app's call sites wants the same persona; a caller
 * that wants a different one — the code-writing tools, for example — passes it explicitly.
 *
 * Entries in [history] are expected to be [ChatRole.USER] or [ChatRole.ASSISTANT]. [ChatRole.SYSTEM]
 * is reserved for [systemPrompt], which both wire protocols represent as their own field rather than
 * as a message; a SYSTEM turn placed in [history] is ignored by the transports instead of being
 * silently demoted to a user utterance.
 */
data class ChatRequest(
    val prompt: String,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val history: List<ChatMessage> = emptyList(),
)

/**
 * The persona SEEMA answers with when a caller does not supply one.
 *
 * Kept short on purpose: it is prepended to every single request, so its length is a per-turn cost on
 * every provider, and it is also the text the fallback providers see when they have no tool access.
 */
const val DEFAULT_SYSTEM_PROMPT: String =
    "You are SEEMA, a concise Android voice assistant. Answer in the user's language and keep " +
        "replies short enough to be spoken aloud."

/**
 * A reply that arrived: which target produced it, the text, and the concrete model that served it.
 *
 * [provider] is the built-in provider behind [target], or null when a custom provider answered. It is
 * kept as a derived property rather than a constructor parameter so it can never disagree with
 * [target], and it exists because a few call sites — the voice service's "who just answered" badge —
 * genuinely only understand the enum and must be able to ask for it explicitly.
 */
data class ChatCompletion(
    val target: ProviderTarget,
    val text: String,
    val model: String,
) {
    /** The built-in provider that answered, or null for a custom provider. */
    val provider: ApiProvider? get() = target.builtIn
}

/**
 * What one call to one provider produced, as the transport saw it.
 *
 * [Failure] carries the HTTP status when the provider actually answered ([code] is null when the
 * request never landed — DNS, TLS, timeout, malformed body). It exists so the transport can report a
 * 429 as data instead of an exception, while a genuine transport exception still propagates and is
 * caught by the router. Both paths lead to the same fallback; keeping the status is what lets the
 * router label the attempt correctly.
 */
sealed interface ChatCall {

    /** The provider answered with usable text. */
    data class Reply(val text: String, val model: String) : ChatCall

    /**
     * The provider refused, or the call landed but produced nothing usable.
     *
     * [code] is the HTTP status, or null when there was no HTTP response at all.
     */
    data class Failure(val code: Int?, val reason: String) : ChatCall
}

/**
 * Why one provider did not produce the turn.
 *
 * Split into cases rather than collapsed into a message string because each one implies a different
 * instruction to the user, and the HUD acts on that difference:
 *
 * - [NoKey] — nothing to retry; the user must paste a key.
 * - [QuotaExceeded] — the key is valid but spent; waiting or topping up fixes it, re-entering it does not.
 * - [Rejected] — the credential or the request was refused (401/403, and 400, which is how Google
 *   answers an *invalid* Gemini key rather than 401).
 * - [HttpError] — the provider is broken (5xx and everything unassigned).
 * - [Transport] — the request never landed; the phone's network is the suspect.
 * - [EmptyReply] — HTTP 200 with no text, which a reasoning model that spent its whole budget
 *   thinking can legitimately produce. It is not a quota problem and must still fall through.
 */
sealed interface ProviderFailure {

    /** Which provider this failure belongs to. */
    val provider: ProviderTarget

    /**
     * The provider's user-facing name, for a line that has no room for the target itself.
     *
     * Derived rather than stored so a built-in and a custom provider are labelled by the same rule.
     */
    val providerName: String get() = provider.displayName

    /** One-line, user-safe explanation. Never contains key material. */
    val detail: String

    /** The HTTP status behind the failure, or null when no response was received. */
    val httpCode: Int?

    /** No credential was stored for this provider, so no request was made. */
    data class NoKey(override val provider: ProviderTarget) : ProviderFailure {
        override val detail: String = "No API key stored"
        override val httpCode: Int? = null
    }

    /** HTTP 429, or 402 for an account that is out of credit: valid key, nothing left to spend. */
    data class QuotaExceeded(
        override val provider: ProviderTarget,
        override val httpCode: Int,
        override val detail: String,
    ) : ProviderFailure

    /** HTTP 400/401/403: the provider refused the credential or the request. */
    data class Rejected(
        override val provider: ProviderTarget,
        override val httpCode: Int,
        override val detail: String,
    ) : ProviderFailure

    /** Any other HTTP status the provider returned, e.g. a 5xx. */
    data class HttpError(
        override val provider: ProviderTarget,
        override val httpCode: Int,
        override val detail: String,
    ) : ProviderFailure

    /** No HTTP status at all: the request never landed, or the exception escaped the transport. */
    data class Transport(
        override val provider: ProviderTarget,
        override val detail: String,
    ) : ProviderFailure {
        override val httpCode: Int? = null
    }

    /**
     * The provider answered with HTTP 2xx but produced nothing the user could be shown.
     *
     * Two shapes land here: a completion whose text is empty (a reasoning model that spent its whole
     * budget thinking), and a Gemini safety block, which arrives as a 200 with no candidate text
     * rather than as an error status. It is still a reason to fall through, but it is *not* a network
     * failure, which is why it is not reported as [Transport].
     */
    data class EmptyReply(
        override val provider: ProviderTarget,
        override val detail: String = "Provider returned an empty reply",
    ) : ProviderFailure {
        override val httpCode: Int? = null
    }
}

/**
 * One entry in the routing log: a provider that was tried, and what came of it.
 *
 * [failure] being null *is* the success signal. It is modelled this way rather than as a
 * provider-plus-optional-failure pair so that a caller cannot accidentally read a failure as a
 * success by ignoring a nullable field.
 */
data class ProviderAttempt(
    val provider: ProviderTarget,
    val failure: ProviderFailure?,
) {
    /** True when this attempt produced the turn. */
    val succeeded: Boolean get() = failure == null
}

/**
 * The outcome of one [MultiProviderRouter.executeChat] call.
 *
 * [attempts] is the full ordered trail, including the attempt that succeeded, so the UI can say
 * "Gemini was out of quota, Groq answered" without the router keeping a second log.
 */
sealed interface ChatResult {

    /** Every provider that was tried, in order. */
    val attempts: List<ProviderAttempt>

    /** True only for [Success]. */
    val isSuccess: Boolean

    /** A provider produced the turn. */
    data class Success(
        val completion: ChatCompletion,
        override val attempts: List<ProviderAttempt>,
    ) : ChatResult {
        override val isSuccess: Boolean get() = true
    }

    /**
     * Every provider in the routing order failed.
     *
     * [attempts] is empty when the router rejected the request before trying anyone (a blank prompt),
     * so it is also the "nothing was attempted" signal.
     */
    data class AllFailed(
        override val attempts: List<ProviderAttempt>,
    ) : ChatResult {
        override val isSuccess: Boolean get() = false
    }
}

/**
 * What the UI binds to, so the HUD can render `Brain: Groq`.
 *
 * [activeTarget] is the provider that served the **last successful** turn. It is deliberately not
 * cleared when a later sweep fails: blanking the badge would make a transient outage look like the
 * router had forgotten its configuration, and [status] already carries the bad news.
 *
 * [activeProvider] is the built-in provider behind [activeTarget], or null when a custom provider
 * answered. It is a derived convenience for the call sites that only understand the enum.
 *
 * [priority] and [customProviders] are held apart rather than merged into one list because they answer
 * different questions: [priority] is the built-in rotation the user configured, while the custom
 * providers are whatever the database currently holds. [targets] is the two joined in the order the
 * next sweep will actually walk, and it is what a UI should render to explain the fallback chain.
 */
data class RouterState(
    val activeTarget: ProviderTarget? = null,
    val status: Status = Status.IDLE,
    val lastAttempts: List<ProviderAttempt> = emptyList(),
    val priority: List<ProviderTarget> = emptyList(),
    val customProviders: List<CustomProviderTarget> = emptyList(),
) {

    /** The built-in provider that answered last, or null when a custom provider did. */
    val activeProvider: ApiProvider? get() = activeTarget?.builtIn

    /**
     * Every target the next sweep will try, in order: the built-in rotation, then the user's custom
     * providers.
     *
     * This is the one place that ordering is expressed, so the HUD, a settings list and the router's
     * own sweep cannot disagree about what comes after what.
     */
    val targets: List<ProviderTarget> get() = priority + customProviders

    /** Where the router is in its lifecycle. */
    enum class Status {

        /** Nothing has been asked yet, or the last turn was cancelled. */
        IDLE,

        /** A sweep is in flight. */
        ROUTING,

        /** The last turn was served. */
        READY,

        /** The last sweep tried every provider and none of them answered. */
        EXHAUSTED,
    }

    /**
     * The exact string the HUD shows, e.g. `"Brain: Groq"`.
     *
     * It lives on the state rather than in Compose code so that the two screens that render it (the
     * main HUD and the settings screen) cannot drift apart, and so the em dash used when no provider
     * has answered yet is a single decision.
     */
    val brainLabel: String
        get() = activeTarget
            ?.let { "$BRAIN_PREFIX${it.displayName}" }
            ?: "$BRAIN_PREFIX$NO_BRAIN"
}

/** Prefix of [RouterState.brainLabel]. */
const val BRAIN_PREFIX: String = "Brain: "

/** What [RouterState.brainLabel] shows before any provider has answered. */
const val NO_BRAIN: String = "—"

/**
 * The short, user-facing reason behind a [ProviderFailure].
 *
 * [ProviderFailure.detail] is the transport's own explanation and is kept for logs: it can be a
 * 400-character HTTP body fragment, which is useless in a one-line HUD status. This is the phrase
 * that fits next to a provider's name, and it is deliberately phrased for the user's next action —
 * "no API key" means paste one, "out of quota" means wait or change brain.
 */
fun ProviderFailure.briefReason(): String = when (this) {
    is ProviderFailure.NoKey -> "no API key"
    is ProviderFailure.QuotaExceeded -> if (httpCode == HTTP_PAYMENT_REQUIRED) "out of credit" else "out of quota"
    is ProviderFailure.Rejected -> "key refused (HTTP $httpCode)"
    is ProviderFailure.HttpError -> "HTTP $httpCode error"
    is ProviderFailure.Transport -> "no connection"
    is ProviderFailure.EmptyReply -> "empty reply"
}

/**
 * Every provider that was tried and why, in order, e.g.
 * `"Gemini: out of quota · Groq: no API key"`.
 *
 * Empty when nothing was attempted at all — the blank-prompt case — so the caller can say "nothing
 * was tried" instead of rendering an empty line. This is what turns [ChatResult.AllFailed] into
 * something a user can act on without reading Logcat.
 */
fun ChatResult.AllFailed.failureSummary(): String =
    attempts.joinToString(ATTEMPT_SEPARATOR) { attempt ->
        "${attempt.provider.displayName}: ${attempt.failure?.briefReason() ?: "no answer"}"
    }

/**
 * True when at least one provider refused for lack of quota or credit rather than for a bad key or
 * a dead network.
 *
 * The voice service acts on this difference: "the free tier is spent, answer the basics offline" is
 * a different message from "something is broken". It used to infer that by searching an `"ERROR:"`
 * string for prose markers; the typed failure carries the verdict itself.
 */
fun ChatResult.AllFailed.outOfQuota(): Boolean =
    attempts.any { it.failure is ProviderFailure.QuotaExceeded }

/** Joins per-provider reasons in [failureSummary]; a middle dot keeps the line compact. */
private const val ATTEMPT_SEPARATOR: String = " · "

/** HTTP 402: the credential is valid but the account is out of credit. */
private const val HTTP_PAYMENT_REQUIRED = 402
