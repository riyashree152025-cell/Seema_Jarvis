package com.seema.assistant.apikeys.data

/**
 * Outcome of the last live probe against a provider.
 *
 * Three of these carry the captions the screen was specified with — Active, Invalid, QuotaLow.
 * The rest exist because collapsing them into those three would lie to the user: "no key
 * entered", "the phone has no network" and "the provider returned a 500" are not the same
 * failure as "your key was rejected", and the fix for each is different.
 */
sealed interface ProviderStatus {

    /** Never probed since the app started. */
    data object Untested : ProviderStatus

    /** A probe is in flight. */
    data object Testing : ProviderStatus

    /** The field is empty and nothing is stored, so there is nothing to probe. */
    data object Missing : ProviderStatus

    /** HTTP 2xx — the key authenticated. */
    data class Active(val code: Int = 200) : ProviderStatus

    /** HTTP 400/401/403 — the provider rejected the credential. */
    data class Invalid(val code: Int = 401) : ProviderStatus

    /** HTTP 429, or 402 (out of credit): the key is valid but has nothing left to spend. */
    data class QuotaLow(val code: Int = 429) : ProviderStatus

    /** Any other HTTP status, e.g. a 5xx from the provider. */
    data class ProviderError(val code: Int) : ProviderStatus

    /** No HTTP status at all: DNS failure, TLS failure, timeout. */
    data class Unreachable(val reason: String) : ProviderStatus
}

/**
 * Caption for the status chip.
 *
 * The first three strings are the specified contract; [ProviderStatus.Active],
 * [ProviderStatus.Invalid] and [ProviderStatus.QuotaLow] must render exactly as written.
 */
val ProviderStatus.chipLabel: String
    get() = when (this) {
        ProviderStatus.Untested -> "— Not tested"
        ProviderStatus.Testing -> "… Testing"
        ProviderStatus.Missing -> "— No key"
        is ProviderStatus.Active -> "✅ Active"
        is ProviderStatus.Invalid -> "❌ Invalid"
        is ProviderStatus.QuotaLow -> "⚠️ Quota Low"
        is ProviderStatus.ProviderError -> "⚠️ HTTP $code"
        is ProviderStatus.Unreachable -> "⚠️ Unreachable"
    }

/** The HTTP status behind the chip, when the probe got far enough to receive one. */
val ProviderStatus.httpCode: Int?
    get() = when (this) {
        is ProviderStatus.Active -> code
        is ProviderStatus.Invalid -> code
        is ProviderStatus.QuotaLow -> code
        is ProviderStatus.ProviderError -> code
        else -> null
    }

/** Optional one-line detail for the row: the rejection reason, or why the call never landed. */
val ProviderStatus.detail: String?
    get() = when (this) {
        is ProviderStatus.Unreachable -> reason
        else -> null
    }
