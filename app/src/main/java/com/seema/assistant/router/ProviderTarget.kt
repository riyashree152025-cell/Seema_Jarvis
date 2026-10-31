package com.seema.assistant.router

import com.seema.assistant.apikeys.data.ApiKeyVault
import com.seema.assistant.apikeys.data.ApiProvider

/**
 * A place a conversational turn can be sent: either one of the app's built-in providers or a
 * "Custom Provider" the user added themselves.
 *
 * ## Why this exists
 *
 * The router, the HUD badge, the routing log and the transport all used to be typed directly to the
 * [ApiProvider] enum. That was fine while the provider list was a compile-time constant, and it is
 * exactly what has to change for the Custom Provider feature: a provider the user typed into a form
 * is a *row in a database*, not an enum constant, and no amount of care lets a database row become
 * an enum entry. Retrofitting a "synthetic" enum constant would compile and then lie — every
 * `when (provider)` in the app would have to invent an answer for a case that does not exist.
 *
 * So the router's vocabulary is this interface instead. [BuiltIn] wraps an enum constant and
 * preserves today's behaviour exactly; [CustomProviderTarget] carries the fields the user entered.
 * Everything downstream of the sweep — the attempt trail, the failure taxonomy, the badge label —
 * joins on [id] and [displayName] and no longer needs to know which of the two it is looking at.
 *
 * ## Identity, and the one rule that matters
 *
 * [id] is the stable public identity of a target: it is what a caller passes to
 * `MultiProviderRouter.executeChat(prompt, providerId)`, what appears in the attempt log, and what
 * the state flow exposes. Two targets with the same [id] are the same target. For a built-in
 * provider the id is the [ApiProvider.id] (`"gemini"`); for a custom provider it is
 * [customProviderBrainId] — prefixed, so a user can never name their own provider `"gemini"` and
 * have it silently shadow the built-in one.
 */
sealed interface ProviderTarget {

    /**
     * Stable identity. See the class KDoc: this is the value a caller routes by.
     *
     * It is deliberately **not** used as a map key for credentials — keys are keyed by
     * [credentialSlot], because a custom provider's id is a database primary key and those are
     * reused after a row is deleted.
     */
    val id: String

    /** The name shown to the user, e.g. the HUD's `Brain: Groq`. */
    val displayName: String

    /** True for an [ApiProvider] constant. The UI uses this to label user-added providers. */
    val isBuiltIn: Boolean

    /**
     * The built-in provider behind this target, or null for a custom one.
     *
     * This is the compatibility seam for the parts of the app that genuinely only understand the
     * enum — the voice service pinning Groq, the API Key Manager screen. A custom provider returns
     * null, and a caller that has no answer for that must decide what to do rather than be handed a
     * plausible-looking wrong enum constant.
     */
    val builtIn: ApiProvider?

    /**
     * Where this target's credential lives.
     *
     * Built-in targets read the [ApiKeyVault] slot named by their provider id. A custom target
     * carries its own key, so routing a custom provider never touches the vault at all — which is
     * also why a user who deletes a custom provider cannot leave a stale credential behind in the
     * built-in store.
     */
    val credentialSlot: String
        get() = id
}

/**
 * One of the app's own providers, wrapping an [ApiProvider] constant.
 *
 * A data class so two sweeps referring to Gemini compare equal and so the value is safe to publish
 * into a [RouterState] — the HUD compares states to decide whether to recompose.
 */
data class BuiltIn(val provider: ApiProvider) : ProviderTarget {

    override val id: String get() = provider.id

    override val displayName: String get() = provider.displayName

    override val isBuiltIn: Boolean get() = true

    override val builtIn: ApiProvider get() = provider

    /** Delegates to the enum so the two can never disagree about the wire format. */
    fun authHeaders(apiKey: String): Map<String, String> = provider.authHeaders(apiKey)
}

/** Every built-in provider as a target, in enum declaration order. */
val BUILT_IN_TARGETS: List<ProviderTarget> = ApiProvider.entries.map { BuiltIn(it) }

/** The built-in target for [provider]. */
fun ApiProvider.asTarget(): ProviderTarget = BuiltIn(this)

/**
 * The built-in provider behind [target], or null when it is a custom one.
 *
 * Exists as a property rather than a plain field so read sites read as a question
 * (`target.builtIn`) instead of a cast.
 */
val ProviderTarget.builtInOrNull: ApiProvider? get() = builtIn

/**
 * A user-added OpenAI-compatible provider, as read back from the Custom Provider store.
 *
 * ## Why [apiKey] is a property of the target and not a vault lookup
 *
 * Built-in credentials live in [ApiKeyVault], keyed by a provider id that is a compile-time
 * constant. A custom provider's credential is created at runtime by a form the user filled in, so
 * the store that owns the row is the store that owns the key; the router receives it already joined
 * rather than performing a second lookup that could disagree with the row it came from.
 *
 * ## [origin] is the row, not a copy of it
 *
 * The `id`/`createdAt` pair is all the router needs to name this provider in the log and to know that
 * two rows are different providers. Holding the whole entity would drag Room's generated code into
 * the routing layer and make a plain JVM unit test need a database.
 */
data class CustomProviderTarget(
    /** [customProviderBrainId] of the owning row. */
    override val id: String,
    /** The name the user gave it. */
    override val displayName: String,
    /** Base URL without a trailing slash, e.g. `https://api.example.com/v1`. */
    val baseUrl: String,
    /** The credential, already decrypted. Never logged, never included in a failure detail. */
    val apiKey: String,
    /** The model id sent in the request body. */
    val modelId: String,
    /** The database row this target was built from, for a caller that needs to act on it. */
    val origin: CustomProviderOrigin,
) : ProviderTarget {

    override val isBuiltIn: Boolean get() = false

    override val builtIn: ApiProvider? get() = null

    /**
     * Redacted deliberately, following the convention set by `ApiKeyEntry`.
     *
     * This is a data class holding a live credential; the generated `toString()` would print it the
     * moment anything logs a target — a routing trace, a crash report, a debug print. It is the one
     * way this refactor could turn every log line into a key leak, so it is overridden rather than
     * left to the compiler.
     */
    override fun toString(): String =
        "CustomProviderTarget(id=$id, displayName=$displayName, baseUrl=$baseUrl, " +
            "apiKey=<${apiKey.length} chars>, modelId=$modelId, origin=$origin)"
}

/**
 * The bits of a stored custom-provider row the router refers to.
 *
 * Declared here rather than importing the `customprovider.data` entity so that the routing package
 * keeps importing no Room, Android or Retrofit type: that is what lets the whole routing contract —
 * including custom providers — be exercised by a plain JVM unit test.
 */
data class CustomProviderOrigin(
    /** The `custom_providers` primary key, or 0 for a target that has not been saved yet. */
    val rowId: Long,
    /** The vault alias the credential was stored under. Never the credential itself. */
    val apiKeyAlias: String,
    /** When the row was inserted, so the routing order can be stable and explainable. */
    val createdAt: Long,
)

/**
 * Routing id for the custom provider stored in row [rowId].
 *
 * The id is prefixed rather than being the bare primary key so that a custom provider can never
 * collide with a built-in provider's id in the attempt log, in a manual `providerId` argument, or in
 * the user's own mental model. Row ids are reused after a delete, which is why this is an identity
 * for *routing* only; [ProviderTarget.credentialSlot] and the vault alias are what a credential is
 * keyed by.
 */
fun customProviderBrainId(rowId: Long): String = "$CUSTOM_PROVIDER_ID_PREFIX$rowId"

/**
 * Prefix of every custom provider's routing id.
 *
 * Chosen to be unusable as an [ApiProvider.id] (those are lowercase single words with no
 * punctuation), so [ProviderTarget.id] can be resolved unambiguously: a built-in id is looked up in
 * the enum, a prefixed one is looked up in the database.
 */
const val CUSTOM_PROVIDER_ID_PREFIX: String = "custom:"

/**
 * The row id encoded in [brainId], or null when [brainId] is not a custom provider's id.
 *
 * A malformed id (`"custom:"`, `"custom:abc"`) returns null rather than throwing: this value arrives
 * from a stored preference or a UI selection, and a stale string there must degrade to "no such
 * provider" — the same treatment an unknown built-in id already gets — instead of crashing a turn.
 */
fun customProviderRowIdOrNull(brainId: String): Long? =
    brainId.removePrefix(CUSTOM_PROVIDER_ID_PREFIX)
        .takeIf { brainId.startsWith(CUSTOM_PROVIDER_ID_PREFIX) && it.isNotEmpty() }
        ?.toLongOrNull()
