package com.seema.assistant.apikeys.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seema.assistant.apikeys.data.ApiKeyHttp
import com.seema.assistant.apikeys.data.ApiKeyVault
import com.seema.assistant.apikeys.data.ApiProvider
import com.seema.assistant.apikeys.data.EncryptedApiKeyVault
import com.seema.assistant.apikeys.data.ProviderApiTester
import com.seema.assistant.apikeys.data.ProviderKeyRepository
import com.seema.assistant.apikeys.data.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One provider's row on the API Key Manager screen.
 *
 * [key] is plaintext and lives only in memory: it is what the user is typing or what was just
 * decrypted for display. It is written to disk only by [ApiKeyManagerViewModel.onSave] and
 * [ApiKeyManagerViewModel.onTest], and never logged.
 */
data class ApiKeyEntry(
    val provider: ApiProvider,
    val key: String = "",
    val isRevealed: Boolean = false,
    val status: ProviderStatus = ProviderStatus.Untested,
) {
    val hasKey: Boolean get() = key.isNotBlank()

    /** The field is being probed right now; the Test button shows a spinner instead of a label. */
    val isTesting: Boolean get() = status == ProviderStatus.Testing

    /**
     * Redacted deliberately.
     *
     * This is a data class, so the generated `toString()` would print all four credentials in clear
     * text the moment anything logs the state — a crash report, a debug print, a future crash
     * reporter. It is the one way a vault screen can leak every key at once, so it is overridden
     * rather than left to the compiler.
     */
    override fun toString(): String {
        val shownKey = if (key.isEmpty()) "<empty>" else "<${key.length} chars>"
        return "ApiKeyEntry(provider=$provider, key=$shownKey, isRevealed=$isRevealed, status=$status)"
    }
}

/** Everything the screen renders. Immutable so recomposition is a cheap equality check. */
data class ApiKeyManagerUiState(
    val entries: List<ApiKeyEntry> = ApiProvider.entries.map { ApiKeyEntry(it) },
    /** Set for one beat after a successful save so the row can say "SAVED". */
    val savedProvider: ApiProvider? = null,
) {
    fun entry(provider: ApiProvider): ApiKeyEntry =
        entries.firstOrNull { it.provider == provider } ?: ApiKeyEntry(provider)
}

/**
 * State holder for the API Key Manager.
 *
 * The screen is a vault, not a text form: every keystroke updates memory only, and the encrypted
 * preference file is touched only when the user presses SAVE or TEST. TEST implies SAVE, because
 * checking a key the user then loses would be worse than useless.
 */
class ApiKeyManagerViewModel(
    private val repository: ProviderKeyRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiKeyManagerUiState())
    val uiState: StateFlow<ApiKeyManagerUiState> = _uiState.asStateFlow()

    init {
        // Opening the vault builds an AndroidKeyStore-backed cipher, which is far too slow to run
        // on the main thread — and this ViewModel is constructed there. Rows therefore start empty
        // and the stored keys are published when the read returns. The load only fills rows the
        // user has not typed into, so a fast editor can never have their keystrokes overwritten.
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { repository.readAll() }
            _uiState.update { state ->
                state.copy(
                    entries = state.entries.map { entry ->
                        val storedKey = stored[entry.provider].orEmpty()
                        if (entry.key.isNotBlank() || storedKey.isBlank()) {
                            entry
                        } else {
                            entry.copy(key = storedKey, status = ProviderStatus.Untested)
                        }
                    }
                )
            }
        }
    }

    /**
     * Edits the in-memory draft.
     *
     * Any previous verdict is dropped: it described the old key, and leaving a green chip next to
     * a freshly edited field is the one way this screen could actively mislead.
     */
    fun onKeyChange(provider: ApiProvider, value: String) {
        updateEntry(provider) { it.copy(key = value, status = ProviderStatus.Untested) }
        markSaved(null)
    }

    /** Flips one row between the masked and plaintext rendering of its key. */
    fun onToggleReveal(provider: ApiProvider) {
        updateEntry(provider) { it.copy(isRevealed = !it.isRevealed) }
    }

    /**
     * Commits the draft to the encrypted store.
     *
     * The write stays on the calling thread on purpose: it is a single short-string encryption
     * triggered by an explicit tap, and the SAVED indicator is only honest if the commit has
     * actually happened by the time it appears.
     */
    fun onSave(provider: ApiProvider) {
        val key = _uiState.value.entry(provider).key.trim()
        if (key.isEmpty()) {
            repository.clear(provider)
            updateEntry(provider) { it.copy(status = ProviderStatus.Missing) }
            return
        }
        repository.write(provider, key)
        // Re-seat the field on the trimmed value so trailing whitespace cannot be re-saved.
        updateEntry(provider) { it.copy(key = key) }
        markSaved(provider)
    }

    /** Forgets the key for one provider, in the vault and on screen. */
    fun onClear(provider: ApiProvider) {
        repository.clear(provider)
        updateEntry(provider) { it.copy(key = "", isRevealed = false, status = ProviderStatus.Missing) }
        markSaved(null)
    }

    /**
     * Saves the draft, then probes the provider for real.
     *
     * The probe runs on [viewModelScope]; if the user leaves the screen mid-flight the coroutine is
     * cancelled with the ViewModel, and no stale verdict can be written back afterwards.
     */
    fun onTest(provider: ApiProvider) {
        val key = _uiState.value.entry(provider).key.trim()
        if (key.isEmpty()) {
            updateEntry(provider) { it.copy(status = ProviderStatus.Missing) }
            return
        }

        repository.write(provider, key)
        updateEntry(provider) { it.copy(key = key, status = ProviderStatus.Testing) }
        markSaved(null)

        viewModelScope.launch {
            val status = repository.test(provider, key)
            // Only paint the verdict if the field still holds the key that was probed. A probe can
            // take up to 20s, and applying its result unconditionally would let a reply about key A
            // land on key B — the exact mislead onKeyChange otherwise prevents. If the ViewModel was
            // cleared, this coroutine is cancelled and nothing is written back at all.
            if (_uiState.value.entry(provider).key == key) {
                updateEntry(provider) { it.copy(status = status) }
            }
        }
    }

    private inline fun updateEntry(
        provider: ApiProvider,
        transform: (ApiKeyEntry) -> ApiKeyEntry,
    ) {
        _uiState.update { state ->
            state.copy(
                entries = state.entries.map { entry ->
                    if (entry.provider == provider) transform(entry) else entry
                }
            )
        }
    }

    private fun markSaved(provider: ApiProvider?) {
        _uiState.update { it.copy(savedProvider = provider) }
    }

    companion object {
        /**
         * Builds the production dependency graph.
         *
         * Only the application context is captured, so the factory can be called from a composable
         * without leaking an Activity into a ViewModel that outlives it.
         */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val vault: ApiKeyVault = EncryptedApiKeyVault(context.applicationContext)
                ApiKeyManagerViewModel(
                    ProviderKeyRepository(
                        vault = vault,
                        tester = ProviderApiTester(ApiKeyHttp.probeApi()),
                    )
                )
            }
        }
    }
}
