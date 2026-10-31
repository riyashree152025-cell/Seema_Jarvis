package com.seema.assistant.customprovider.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seema.assistant.customprovider.data.CustomProvider
import com.seema.assistant.ui.jarvis.HUD

/**
 * Custom Providers: a form that adds any OpenAI-compatible endpoint, plus the list already stored.
 *
 * The screen is the thin half of the feature. It owns nothing but a ViewModel and never touches the
 * repository, the credential store or the network: the only action that leaves this file is
 * [CustomProviderViewModel.onTestAndSave], and the form is cleared only after the repository has
 * reported that the endpoint actually answered.
 *
 * The Toast is fired here rather than inside [CustomProviderContent] because it is the one part of
 * the screen that needs a live Android context; keeping it out of the stateless body is what lets
 * the previews below render an error state without a device.
 */
@Composable
fun CustomProviderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomProviderViewModel = viewModel(
        factory = CustomProviderViewModel.factory(LocalContext.current)
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read once per composition so the effect body cannot disagree with the key it was launched
    // with; the ViewModel clears this value inside the effect, which is what makes it one-shot.
    val transientMessage = state.transientMessage

    // Keyed on the message itself, so the Toast fires exactly once per message and a recomposition
    // that changes nothing else does not repeat it. Acknowledging it immediately after showing it
    // is what keeps a rotation from replaying a failure the user has already read.
    LaunchedEffect(transientMessage) {
        if (transientMessage == null) return@LaunchedEffect
        Toast.makeText(context, transientMessage, Toast.LENGTH_LONG).show()
        viewModel.onMessageShown()
    }

    CustomProviderContent(
        state = state,
        onBack = onBack,
        onNameChange = viewModel::onNameChange,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onApiKeyChange = viewModel::onApiKeyChange,
        onModelIdChange = viewModel::onModelIdChange,
        onRevealToggle = viewModel::onRevealToggle,
        onTestAndSave = viewModel::onTestAndSave,
        onDelete = viewModel::onDelete,
        modifier = modifier,
    )
}

/**
 * Stateless screen body.
 *
 * Kept free of ViewModel, repository and Toast lookups so the previews and any Compose test can
 * render a fixed [CustomProviderUiState] and assert on what the user would actually see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomProviderContent(
    state: CustomProviderUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onRevealToggle: () -> Unit,
    onTestAndSave: () -> Unit,
    onDelete: (CustomProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = HUD.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Custom Providers",
                        style = MaterialTheme.typography.titleMedium,
                        color = HUD.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackChevron(onBack = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HUD.Black,
                    titleContentColor = HUD.TextPrimary,
                    navigationIconContentColor = HUD.Blood,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                // The 96dp tail clears the gesture bar so the last saved row is never trapped under
                // a floating navigation pill, matching the app's other scrolling screens.
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            SectionLabel(text = "NEW PROVIDER")
            Spacer(Modifier.height(10.dp))

            DraftField(
                value = state.name,
                onValueChange = onNameChange,
                label = "Name",
                placeholder = "My gateway",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                error = state.nameError,
            )
            Spacer(Modifier.height(10.dp))

            DraftField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                label = "Base URL",
                placeholder = "https://api.example.com/v1",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                error = state.baseUrlError,
            )
            Spacer(Modifier.height(10.dp))

            DraftField(
                value = state.apiKey,
                onValueChange = onApiKeyChange,
                label = "API Key",
                placeholder = "sk-...",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                error = state.apiKeyError,
                // Masked unless the user asked otherwise: a live credential left readable survives
                // in screenshots and screen shares long after the user has left the screen.
                visualTransformation = if (state.isKeyRevealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = { RevealButton(isRevealed = state.isKeyRevealed, onClick = onRevealToggle) },
            )
            Spacer(Modifier.height(10.dp))

            DraftField(
                value = state.modelId,
                onValueChange = onModelIdChange,
                label = "Model ID",
                placeholder = "gpt-4o-mini",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                error = state.modelIdError,
            )

            Spacer(Modifier.height(18.dp))

            TestAndSaveButton(state = state, onClick = onTestAndSave)

            // The outcome of the last Test, in text as well as in the one-shot Toast: the Toast is
            // gone after a few seconds, and the screen would otherwise show no reason at all for a
            // submission that failed.
            state.savedName?.let { savedName ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "SAVED: $savedName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = HUD.Online,
                )
            }
            state.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HUD.Danger,
                )
            }

            Spacer(Modifier.height(28.dp))

            SectionLabel(text = "SAVED PROVIDERS")
            Spacer(Modifier.height(10.dp))

            if (state.savedProviders.isEmpty()) {
                Text(
                    text = "Nothing stored yet. A provider appears here only after a test succeeds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HUD.TextMuted,
                )
            } else {
                // Driven by the state list, never by a hardcoded provider name: whatever the
                // repository reports is exactly what is listed.
                state.savedProviders.forEach { row ->
                    SavedProviderRow(row = row, onDelete = { onDelete(row) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------

/**
 * Back chevron for the app bar's navigation slot.
 *
 * A hand-drawn glyph and a hand-built clickable rather than [IconButton] with a Material icon: the
 * HUD ships no icon set and no vector assets, so the single-guillemet chevron is what every other
 * screen in the app already uses for "back".
 */
@Composable
private fun BackChevron(onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = HUD.Blood.copy(alpha = 0.30f)),
                onClickLabel = "Back",
                role = Role.Button,
                onClick = onBack,
            )
            .semantics { contentDescription = "Back to settings" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\u2039",
            fontSize = 29.sp,
            fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
            color = HUD.Blood,
        )
    }
}

/** Uppercase rule that separates the form from the stored list, in the HUD's label weight. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            fontSize = 12.sp,
        ),
        color = HUD.BloodDim,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------------------------
// Form
// ---------------------------------------------------------------------------------------------

/**
 * One draft field, wired to the HUD palette.
 *
 * The supporting text is the field's own validation verdict. Passing `null` rather than an empty
 * string when there is nothing to say keeps the field from reserving a permanent blank line under
 * every input, which would push the button off a short screen for no information.
 */
@Composable
private fun DraftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    error: String?,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    // Annotated explicitly: the inferred type of a lambda behind `?.let` is not a composable lambda,
    // and the slot below accepts only a @Composable one.
    val supporting: (@Composable () -> Unit)? = error?.let { message ->
        {
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = HUD.Danger,
            )
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = label) },
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = HUD.TextMuted.copy(alpha = 0.55f),
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        isError = error != null,
        supportingText = supporting,
        singleLine = true,
        visualTransformation = visualTransformation,
        // Autocorrect stays off for every field: a provider name, a hostname and a model id are all
        // identifiers, and the keyboard's dictionary offering to "fix" them is pure noise.
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(4.dp),
        // Material's default outlined field is a light surface with a purple focus ring; both would
        // import a foreign surface into the black HUD, so every colour role is re-pointed here.
        colors = hudFieldColors(),
    )
}

/**
 * SHOW/HIDE control in the key field's trailing slot.
 *
 * A word rather than an eye icon for the same reason as [BackChevron]: there is no icon set to draw
 * from, and the word is unambiguous at a glance. The semantics carry the real contract for TalkBack,
 * including the current state, because "SHOW" alone does not say what is visible right now.
 */
@Composable
private fun RevealButton(isRevealed: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = if (isRevealed) "Hide API key" else "Show API key"
            stateDescription = if (isRevealed) "Key visible" else "Key masked"
        },
    ) {
        Text(
            text = if (isRevealed) "HIDE" else "SHOW",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = if (isRevealed) HUD.Blood else HUD.BloodDim,
            maxLines = 1,
        )
    }
}

/**
 * The single submit action.
 *
 * One button, not a Test button plus a Save button: the endpoint must answer before anything is
 * stored, so a "save without testing" path would let a typo into the provider list and a "test
 * without saving" path would throw away a credential the user just proved works.
 */
@Composable
private fun TestAndSaveButton(
    state: CustomProviderUiState,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        // `canSubmit` is the whole gate — non-blank fields, a usable URL scheme and nothing already
        // in flight — so the button and the validator can never disagree.
        enabled = state.canSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { if (state.isTesting) stateDescription = "Testing provider" },
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HUD.Blood,
            contentColor = HUD.Black,
            disabledContainerColor = HUD.HairlineSoft,
            disabledContentColor = HUD.TextMuted.copy(alpha = 0.45f),
        ),
    ) {
        if (state.isTesting) {
            // The spinner replaces the label rather than sitting beside it: the button keeps its
            // size, and there is no moment where two labels suggest two different actions.
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = HUD.Black,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = "Test & Save",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stored providers
// ---------------------------------------------------------------------------------------------

/**
 * One stored provider: name, model and host, with the only destructive action on the screen.
 *
 * The row deliberately shows the host rather than the full base URL. The endpoint is the part a user
 * can recognise at a glance, and the row is not the place to reprint a configuration that lives in
 * the store. Nothing here can render the API key: [CustomProvider] carries only an alias for it,
 * which is not the secret and is therefore not shown either.
 */
@Composable
private fun SavedProviderRow(row: CustomProvider, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HUD.Glass)
            .border(1.dp, HUD.Hairline, shape)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = HUD.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "MODEL ${row.modelId}",
                style = MaterialTheme.typography.labelSmall,
                color = HUD.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = baseUrlHost(row.baseUrl),
                style = MaterialTheme.typography.bodyMedium,
                color = HUD.BloodDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = HUD.Danger),
            modifier = Modifier.semantics { contentDescription = "Delete ${row.name}" },
        ) {
            Text(
                text = "DELETE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Host portion of a stored base URL.
 *
 * Parsed with plain string surgery instead of `android.net.Uri` so the previews and any JVM test can
 * render the row without an Android framework; the result is exactly what a user would type into a
 * browser to reach the endpoint.
 */
private fun baseUrlHost(baseUrl: String): String {
    val withoutScheme = baseUrl.substringAfter("://", baseUrl)
    return withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { baseUrl }
}

// ---------------------------------------------------------------------------------------------
// Field colours
// ---------------------------------------------------------------------------------------------

/**
 * The HUD's field palette, in one place.
 *
 * `OutlinedTextFieldDefaults.colors()` is a composable that allocates a colour object per call, so it
 * is built once here and shared by all four fields rather than repeated at each call site.
 */
@Composable
private fun hudFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = HUD.TextPrimary,
    unfocusedTextColor = HUD.TextPrimary,
    errorTextColor = HUD.TextPrimary,
    focusedContainerColor = HUD.Void.copy(alpha = 0.35f),
    unfocusedContainerColor = HUD.Void.copy(alpha = 0.35f),
    errorContainerColor = HUD.Void.copy(alpha = 0.35f),
    cursorColor = HUD.Blood,
    errorCursorColor = HUD.Danger,
    focusedBorderColor = HUD.Blood,
    unfocusedBorderColor = HUD.Hairline,
    errorBorderColor = HUD.Danger,
    focusedLabelColor = HUD.BloodDim,
    unfocusedLabelColor = HUD.TextMuted,
    errorLabelColor = HUD.Danger,
    focusedPlaceholderColor = HUD.TextMuted.copy(alpha = 0.55f),
    unfocusedPlaceholderColor = HUD.TextMuted.copy(alpha = 0.55f),
    errorPlaceholderColor = HUD.TextMuted.copy(alpha = 0.55f),
    focusedTrailingIconColor = HUD.Blood,
    unfocusedTrailingIconColor = HUD.BloodDim,
    errorTrailingIconColor = HUD.Danger,
    focusedSupportingTextColor = HUD.Danger,
    unfocusedSupportingTextColor = HUD.Danger,
    errorSupportingTextColor = HUD.Danger,
)

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

/** The untouched form: masked key, no hints, disabled button, empty stored list. */
@Preview(
    name = "Custom Providers - Empty",
    showBackground = true,
    backgroundColor = 0xFF241016,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun CustomProviderEmptyPreview() {
    CustomProviderContent(
        state = CustomProviderUiState(),
        onBack = {},
        onNameChange = {},
        onBaseUrlChange = {},
        onApiKeyChange = {},
        onModelIdChange = {},
        onRevealToggle = {},
        onTestAndSave = {},
        onDelete = {},
    )
}

/** A ready-to-submit draft, revealed key and one stored row. */
@Preview(
    name = "Custom Providers - Filled",
    showBackground = true,
    backgroundColor = 0xFF241016,
    widthDp = 420,
    heightDp = 1100,
)
@Composable
private fun CustomProviderFilledPreview() {
    CustomProviderContent(
        state = CustomProviderUiState(
            name = "My gateway",
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-preview-placeholder",
            modelId = "gpt-4o-mini",
            isKeyRevealed = true,
            savedProviders = listOf(
                CustomProvider(
                    id = 1L,
                    name = "Groq",
                    baseUrl = "https://api.groq.com/openai/v1",
                    modelId = "llama-3.3-70b-versatile",
                    apiKeyAlias = "custom_provider_1",
                    createdAt = 1_700_000_000_000L,
                )
            ),
        ),
        onBack = {},
        onNameChange = {},
        onBaseUrlChange = {},
        onApiKeyChange = {},
        onModelIdChange = {},
        onRevealToggle = {},
        onTestAndSave = {},
        onDelete = {},
    )
}

/** The state after a refused endpoint: per-field hints plus the repository's failure reason. */
@Preview(
    name = "Custom Providers - Error",
    showBackground = true,
    backgroundColor = 0xFF241016,
    widthDp = 420,
    heightDp = 1000,
)
@Composable
private fun CustomProviderErrorPreview() {
    CustomProviderContent(
        state = CustomProviderUiState(
            name = "Local model",
            baseUrl = "localhost:11434/v1",
            apiKey = "ollama",
            modelId = "",
            error = "HTTP 401: the endpoint rejected the API key.",
        ),
        onBack = {},
        onNameChange = {},
        onBaseUrlChange = {},
        onApiKeyChange = {},
        onModelIdChange = {},
        onRevealToggle = {},
        onTestAndSave = {},
        onDelete = {},
    )
}
