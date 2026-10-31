package com.seema.assistant.customprovider.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seema.assistant.customprovider.data.CustomProvider
import com.seema.assistant.customprovider.data.CustomProviderRepository
import com.seema.assistant.customprovider.data.CustomProviderSaveResult
import com.seema.assistant.customprovider.data.message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One screen's worth of state for the Custom Provider form.
 *
 * The four draft fields live here in memory only. [apiKey] is the plaintext the user is typing or
 * has just typed, and it is handed to the repository exactly once per Test; it is never written to
 * disk by this class and never rendered back from the stored rows, which do not carry it.
 *
 * Blank [`name`]/[`apiKey`]/[`modelId`] and a malformed [`baseUrl`] are reported as per-field
 * errors *without* blocking typing: the draft is free text and only the submit button reads these
 * verdicts. They are keyed on [hasAnyInput] rather than on a submit attempt because the submit
 * button is disabled while any field is blank — without a hint there would be no way to tell which
 * field is holding the button down.
 */
data class CustomProviderUiState(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    /** Whether the key field currently renders its plaintext. Defaults to masked. */
    val isKeyRevealed: Boolean = false,
    val savedProviders: List<CustomProvider> = emptyList(),
    /** A Test is in flight; the button is disabled and shows a spinner instead of its label. */
    val isTesting: Boolean = false,
    /** One-beat "SAVED" confirmation naming the provider that was just stored. */
    val savedName: String? = null,
    /** The last failure reason, kept on screen after the one-shot Toast has gone. */
    val error: String? = null,
    /** A message the screen shows exactly once as a Toast, then clears via [onMessageShown]. */
    val transientMessage: String? = null,
) {

    /** True once the user has typed anywhere, which is when the empty-field hints become useful. */
    val hasAnyInput: Boolean
        get() = name.isNotBlank() || baseUrl.isNotBlank() || apiKey.isNotBlank() || modelId.isNotBlank()

    /** Null while the name is fine, or while the form is still untouched and empty. */
    val nameError: String? get() = requiredError(name, "Name")

    /** Null unless the URL is malformed, or blank after the user has started filling the form in. */
    val baseUrlError: String?
        get() = when {
            baseUrl.isNotBlank() && !isSupportedBaseUrl(baseUrl) -> "Start with http:// or https://"
            baseUrl.isBlank() && hasAnyInput -> "Base URL is required"
            else -> null
        }

    /** Null while the key is fine, or while the form is still untouched and empty. */
    val apiKeyError: String? get() = requiredError(apiKey, "API key")

    /** Null while the model id is fine, or while the form is still untouched and empty. */
    val modelIdError: String? get() = requiredError(modelId, "Model ID")

    /**
     * Whether "Test & Save" may run.
     *
     * A malformed URL is part of this because a Test against it can only burn a round trip and come
     * back as a transport error; the field's own error text already says what to fix. The blank
     * checks here are also what the button's enabled state encodes, so the rule lives in one place.
     */
    val canSubmit: Boolean
        get() = !isTesting &&
            name.isNotBlank() &&
            baseUrl.isNotBlank() &&
            apiKey.isNotBlank() &&
            modelId.isNotBlank() &&
            isSupportedBaseUrl(baseUrl)

    private fun requiredError(value: String, label: String): String? =
        if (value.isBlank() && hasAnyInput) "$label is required" else null

    /**
     * Redacted deliberately.
     *
     * This class carries the plaintext key the user is typing. The generated `toString()` would
     * print it in full the moment anything logs the state — a debug print, an analytics event, a
     * future crash reporter — so it is overridden rather than left to the compiler. Stored rows are
     * summarised by count for the same reason: a log line is not the place to enumerate a user's
     * provider list.
     */
    override fun toString(): String {
        val shownKey = if (apiKey.isEmpty()) "<empty>" else "<${apiKey.length} chars>"
        return "CustomProviderUiState(name=$name, baseUrl=$baseUrl, apiKey=$shownKey, " +
            "modelId=$modelId, isKeyRevealed=$isKeyRevealed, " +
            "savedProviders=<${savedProviders.size} rows>, isTesting=$isTesting, " +
            "savedName=$savedName, error=$error, transientMessage=$transientMessage)"
    }
}

/**
 * State holder for the Custom Provider screen.
 *
 * The form is deliberately thin: the repository owns "test, then persist", and this class owns only
 * the draft and the visible list. That split keeps the network probe and the credential write in
 * one place instead of duplicating them behind a second button that could half-succeed.
 */
class CustomProviderViewModel(
    private val repository: CustomProviderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomProviderUiState())
    val uiState: StateFlow<CustomProviderUiState> = _uiState.asStateFlow()

    init {
        // The stored rows are the repository's truth, not a copy this class owns: a diagnostic
        // change that rewrites the store must be reflected here without the screen asking again.
        viewModelScope.launch {
            repository.observeAll()
                // A store read failure must not take the process down — a provider can still be
                // added — but the user needs to know why the saved list is empty.
                .catch { cause ->
                    val reason = cause.message ?: "Saved providers could not be read."
                    _uiState.update { it.copy(error = reason, transientMessage = reason) }
                }
                .collect { rows ->
                    _uiState.update { state -> state.copy(savedProviders = rows) }
                }
        }
    }

    /**
     * Edits the name draft.
     *
     * No keystroke is rejected, and no validation runs here: the field is free text and the verdicts
     * are derived from [CustomProviderUiState], so a half-typed URL is never punished.
     */
    fun onNameChange(value: String) = editDraft { it.copy(name = value) }

    /** Edits the base URL draft; see [onNameChange] for why nothing is rejected. */
    fun onBaseUrlChange(value: String) = editDraft { it.copy(baseUrl = value) }

    /**
     * Edits the API key draft.
     *
     * The value is held in state for exactly as long as the user needs it, and is dropped the
     * instant a Test succeeds; it is never logged, never persisted here and never put in a Toast.
     */
    fun onApiKeyChange(value: String) = editDraft { it.copy(apiKey = value) }

    /** Edits the model id draft; see [onNameChange] for why nothing is rejected. */
    fun onModelIdChange(value: String) = editDraft { it.copy(modelId = value) }

    /**
     * Flips the key field between masked and plaintext.
     *
     * Only a boolean moves, so revealing a key cannot put its characters anywhere this class does
     * not already hold them.
     */
    fun onRevealToggle() {
        _uiState.update { it.copy(isKeyRevealed = !it.isKeyRevealed) }
    }

    /**
     * Probes the provider with the current draft, then stores it only if the probe accepted it.
     *
     * The four values are captured before the suspend call because the reply describes the draft as
     * it was, not as it may be by the time the network answers: a probe can take seconds, and
     * applying it unconditionally could clear a field the user has since edited or stamp an error
     * onto a URL that is no longer the one that failed.
     */
    fun onTestAndSave() {
        val draft = _uiState.value
        if (!draft.canSubmit) return

        // Trimmed once, here, and used for both the request and the stale-response comparison. If
        // the comparison used the raw draft, trailing whitespace would make every reply look stale
        // and the spinner would never stop.
        val name = draft.name.trim()
        val baseUrl = draft.baseUrl.trim()
        val apiKey = draft.apiKey.trim()
        val modelId = draft.modelId.trim()

        _uiState.update {
            it.copy(isTesting = true, error = null, transientMessage = null, savedName = null)
        }

        viewModelScope.launch {
            val result = repository.testAndSave(name, baseUrl, apiKey, modelId)

            _uiState.update { state ->
                val stillCurrent = state.draftMatches(name, baseUrl, apiKey, modelId)
                when (result) {
                    is CustomProviderSaveResult.Saved -> {
                        // Upserted as well as collected: the repository's own emission is the
                        // authority, but writing the row here removes any window in which a
                        // successful save appears to have done nothing.
                        val rows = state.savedProviders.upsert(result.row)
                        if (stillCurrent) {
                            state.copy(
                                savedProviders = rows,
                                name = "",
                                baseUrl = "",
                                apiKey = "",
                                modelId = "",
                                isKeyRevealed = false,
                                isTesting = false,
                                savedName = result.row.name,
                            )
                        } else {
                            // The server accepted what was sent; a later edit must survive it.
                            state.copy(savedProviders = rows, isTesting = false)
                        }
                    }

                    is CustomProviderSaveResult.Failed -> {
                        val reason = result.message
                        if (stillCurrent) {
                            state.copy(isTesting = false, error = reason, transientMessage = reason)
                        } else {
                            // The verdict is dropped entirely: it describes a draft that no longer
                            // exists, and an inline error under the current fields would be a lie.
                            // The spinner still has to stop, which is the only thing carried over.
                            state.copy(isTesting = false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes a stored provider.
     *
     * The row leaves the visible list immediately so the tap feels answered, and the repository's
     * flow re-publishes the authoritative list right after; if the delete fails, that re-publish
     * brings the row back and the failure is reported rather than silently swallowed.
     */
    fun onDelete(row: CustomProvider) {
        _uiState.update { state -> state.copy(savedProviders = state.savedProviders.without(row)) }

        viewModelScope.launch {
            runCatching { repository.delete(row) }.onFailure { cause ->
                val reason = cause.message ?: "Could not delete ${row.name}."
                _uiState.update { it.copy(error = reason, transientMessage = reason) }
            }
        }
    }

    /**
     * Acknowledges the one-shot message.
     *
     * Called by the screen after it has shown the Toast, so a recomposition or a rotation cannot
     * show the same failure twice.
     */
    fun onMessageShown() {
        _uiState.update { if (it.transientMessage == null) it else it.copy(transientMessage = null) }
    }

    /**
     * Applies a keystroke and drops every verdict about the previous draft.
     *
     * A failure line, its Toast and a SAVED confirmation all describe text the user has just
     * changed; leaving any of them up would be the one way this screen could actively mislead.
     */
    private inline fun editDraft(transform: (CustomProviderUiState) -> CustomProviderUiState) {
        _uiState.update { state ->
            transform(state).copy(error = null, transientMessage = null, savedName = null)
        }
    }

    /** True when the draft still holds the values a reply was requested for. */
    private fun CustomProviderUiState.draftMatches(
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
    ): Boolean = this.name.trim() == name &&
        this.baseUrl.trim() == baseUrl &&
        this.apiKey.trim() == apiKey &&
        this.modelId.trim() == modelId

    companion object {
        /**
         * Builds the production dependency graph.
         *
         * Only the application context is captured, so the factory can be called from a composable
         * without leaking an Activity into a ViewModel that outlives it.
         */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CustomProviderViewModel(CustomProviderRepository.of(context.applicationContext))
            }
        }
    }
}

/**
 * Cheap scheme check, run on every keystroke through [CustomProviderUiState.canSubmit].
 *
 * `android.net.Uri`/`java.net.URI` would each allocate a parser per keystroke and can throw, and the
 * only thing the form needs to know is whether a Test has a chance of reaching an
 * OpenAI-compatible endpoint.
 */
private fun isSupportedBaseUrl(value: String): Boolean {
    val trimmed = value.trim()
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://"
        trimmed.startsWith("http://", ignoreCase = true) -> "http://"
        else -> return false
    }
    return trimmed.length > scheme.length
}

/**
 * Replaces the row that names the same provider, or appends the new one.
 *
 * The repository's own emission is the authority on list order; this exists only so a successful
 * save cannot look like it did nothing in the window before that emission arrives. Matching on the
 * id when there is one and on name-plus-url when there is not keeps a re-save of the same endpoint
 * from listing the provider twice.
 */
private fun List<CustomProvider>.upsert(row: CustomProvider): List<CustomProvider> {
    val retained = if (row.id != 0L) {
        filterNot { it.id == row.id }
    } else {
        filterNot { it.name.equals(row.name, ignoreCase = true) && it.baseUrl == row.baseUrl }
    }
    return (retained + row).sortedBy { it.createdAt }
}

/** Drops one row for the optimistic delete; an id-less row falls back to value equality. */
private fun List<CustomProvider>.without(row: CustomProvider): List<CustomProvider> =
    if (row.id != 0L) filterNot { it.id == row.id } else this - row
