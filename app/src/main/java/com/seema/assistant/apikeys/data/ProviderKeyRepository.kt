package com.seema.assistant.apikeys.data

/**
 * The only credential seam [com.seema.assistant.apikeys.ui.ApiKeyManagerViewModel] depends on.
 *
 * It exists so the UI layer holds no [ApiKeyVault] and no Retrofit stack: the screen can then be
 * driven in a test by a fake [ProviderApiTester] and a fake vault, with no device keystore and no
 * network. It also keeps the two verbs that touch storage in one place, so "TEST implies SAVE"
 * cannot drift apart.
 */
class ProviderKeyRepository(
    private val vault: ApiKeyVault,
    private val tester: ProviderApiTester,
) {

    /** The stored key for [provider], or "" when nothing is stored. */
    fun read(provider: ApiProvider): String = vault.read(provider)

    /** Every stored key, for the one-shot load when the screen opens. */
    fun readAll(): Map<ApiProvider, String> = vault.readAll()

    /** Persists [apiKey] for [provider] in the encrypted store. */
    fun write(provider: ApiProvider, apiKey: String) {
        vault.write(provider, apiKey)
    }

    /** Forgets [provider]'s key in every store the vault owns. */
    fun clear(provider: ApiProvider) {
        vault.clear(provider)
    }

    /**
     * Runs a live probe. Trimming and the blank-key shortcut live in [ProviderApiTester], so this
     * is a pure pass-through and the UI never needs to reach the tester itself.
     */
    suspend fun test(provider: ApiProvider, apiKey: String): ProviderStatus =
        tester.test(provider, apiKey)
}

/**
 * Maps a probe's HTTP status onto the screen's verdict.
 *
 * 402 (out of credit) and 429 (rate limited) mean the credential is valid but cannot spend right
 * now — a different instruction to the user than "your key is wrong", hence
 * [ProviderStatus.QuotaLow]. 400 sits with 401/403 because Google answers an *invalid* Gemini key
 * with `400 INVALID_ARGUMENT` rather than 401; treating 400 as a generic error would tell a user
 * with a typo'd key to retry later instead of fixing the key.
 *
 * The probe endpoints are model-list / key-introspection URLs ([ApiProvider.probeUrl]), so no
 * outcome here consumes generation quota. It lives in this file rather than beside the probe stack
 * so that the file defining it imports no Android, OkHttp or Retrofit type at all, which is what
 * lets a plain JVM unit test call it directly.
 */
internal fun providerStatusFor(httpCode: Int): ProviderStatus = when {
    httpCode in 200..299 -> ProviderStatus.Active(httpCode)
    httpCode == 400 || httpCode == 401 || httpCode == 403 -> ProviderStatus.Invalid(httpCode)
    httpCode == 402 || httpCode == 429 -> ProviderStatus.QuotaLow(httpCode)
    else -> ProviderStatus.ProviderError(httpCode)
}
