package com.seema.assistant.apikeys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seema.assistant.apikeys.data.ApiProvider
import com.seema.assistant.apikeys.data.ProviderStatus
import com.seema.assistant.apikeys.data.chipLabel
import com.seema.assistant.apikeys.data.detail
import com.seema.assistant.apikeys.data.httpCode
import com.seema.assistant.ui.jarvis.HUD

/**
 * API Key Manager: one instrument pane per provider, each with a masked key field and a live probe.
 *
 * This is the vault door, not a settings list. The screen never claims a key works until a probe
 * says so, so the only green on the page is a chip the user earned by pressing TEST.
 *
 * The stateful entry point owns nothing but the ViewModel; every pixel below it is drawn by the
 * stateless [ApiKeyManagerContent], which is what the preview renders and what a Compose test can
 * drive without an Android context or a real vault.
 */
@Composable
fun ApiKeyManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApiKeyManagerViewModel = viewModel(
        factory = ApiKeyManagerViewModel.factory(LocalContext.current)
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ApiKeyManagerContent(
        state = state,
        onBack = onBack,
        onKeyChange = viewModel::onKeyChange,
        onToggleReveal = viewModel::onToggleReveal,
        onTest = viewModel::onTest,
        onSave = viewModel::onSave,
        onClear = viewModel::onClear,
        modifier = modifier,
    )
}

/**
 * Stateless screen body.
 *
 * Kept separate and free of viewModel/context lookups so the preview and any Compose test can
 * render a fixed [ApiKeyManagerUiState] and assert on what the user would actually see.
 */
@Composable
internal fun ApiKeyManagerContent(
    state: ApiKeyManagerUiState,
    onBack: () -> Unit,
    onKeyChange: (ApiProvider, String) -> Unit,
    onToggleReveal: (ApiProvider) -> Unit,
    onTest: (ApiProvider) -> Unit,
    onSave: (ApiProvider) -> Unit,
    onClear: (ApiProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Built once and reused every frame: a gradient constructed inside drawBehind would allocate a
    // shader per draw while the user scrolls the provider list.
    val backdrop = remember {
        Brush.verticalGradient(
            colors = listOf(
                HUD.Blood.copy(alpha = 0.38f),
                Color.Transparent,
                Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HUD.Black)
            .drawBehind { drawRect(brush = backdrop) }
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Same top/bottom rhythm as SystemSettingsScreen: the 96dp tail clears the gesture
                // bar area so the footer is never trapped under a floating navigation pill.
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            HeaderRow(onBack = onBack)
            Spacer(Modifier.height(14.dp))

            // Driven by the state list, never by a hardcoded provider name: adding a provider to
            // ApiProvider.entries must be enough to make it appear here.
            state.entries.forEach { entry ->
                ProviderPanel(
                    entry = entry,
                    isSaved = state.savedProvider == entry.provider,
                    onKeyChange = { value -> onKeyChange(entry.provider, value) },
                    onToggleReveal = { onToggleReveal(entry.provider) },
                    onTest = { onTest(entry.provider) },
                    onSave = { onSave(entry.provider) },
                    onClear = { onClear(entry.provider) },
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(6.dp))
            FooterNote()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------

/** Back chevron over the title and its one-line subtitle, sharing the HUD's left edge. */
@Composable
private fun HeaderRow(onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = HUD.Blood.copy(alpha = 0.30f)),
                        onClickLabel = "Back",
                        role = Role.Button,
                        onClick = onBack
                    )
                    .semantics { contentDescription = "Back to settings" },
                contentAlignment = Alignment.Center
            ) {
                // Drawn as a glyph rather than a Material icon: the HUD has no icon set, and the
                // single-guillemet chevron matches the one SystemSettingsScreen already uses.
                Text(
                    text = "\u2039",
                    fontSize = 29.sp,
                    fontFamily = HudFont,
                    color = HUD.BloodDim
                )
            }

            Spacer(Modifier.width(6.dp))

            Text(
                text = "API KEY MANAGER",
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                fontFamily = HudFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = HUD.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(3.dp))

        Text(
            text = "PROVIDER CREDENTIALS / LIVE PROBE",
            modifier = Modifier.padding(start = 38.dp),
            fontSize = 13.sp,
            fontFamily = HudFont,
            letterSpacing = 1.2.sp,
            color = HUD.BloodDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Explains the vault's local-only contract; the encryption claim is what makes save meaningful. */
@Composable
private fun FooterNote() {
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HUD.Glass)
            .border(1.dp, HUD.Hairline, shape)
            .padding(14.dp)
    ) {
        Text(
            text = "KEY HYGIENE",
            fontSize = 12.sp,
            fontFamily = HudFont,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = HUD.BloodDim
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Keys are encrypted on this device (AES-256, Android keystore) and are sent " +
                "only to their own provider. Gemini and Groq are also mirrored into the app's " +
                "older plaintext store, which the rest of SEEMA still reads - a temporary " +
                "duplicate, not a design. Nothing is written until TEST or SAVE, and a stored " +
                "key starts untested: only a live probe turns a chip green.",
            fontSize = 12.sp,
            fontFamily = HudFont,
            color = HUD.TextMuted,
            letterSpacing = 0.4.sp,
            lineHeight = 17.sp
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Provider pane
// ---------------------------------------------------------------------------------------------

/**
 * One provider: name, live status, masked key field, console link and the three actions.
 *
 * The pane is frosted glass with a single accent — the accent lives in the chip and the buttons,
 * never in a container, so a row of four panes still reads as one instrument rather than four
 * competing alerts.
 */
@Composable
private fun ProviderPanel(
    entry: ApiKeyEntry,
    isSaved: Boolean,
    onKeyChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val provider = entry.provider
    val uriHandler = LocalUriHandler.current
    val panelShape = RoundedCornerShape(4.dp)
    val linkInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(HUD.Glass)
            .border(1.dp, HUD.GlassEdge, panelShape)
            .padding(14.dp)
    ) {
        // Header: name + chip (+ SAVED). The name is the only flexible element, so a long status
        // caption can never squeeze the chip off the row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = provider.displayName,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 20.sp,
                fontFamily = HudFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = HUD.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.width(8.dp))

            StatusChip(providerName = provider.displayName, status = entry.status)

            if (isSaved) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SAVED",
                    fontSize = 12.sp,
                    fontFamily = HudFont,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = HUD.Online,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Caption + console jump. GET KEY sits on the caption line so the row above stays reserved
        // for the provider name and its verdict.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "API KEY",
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                fontFamily = HudFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                color = HUD.TextMuted
            )

            Text(
                text = "GET KEY \u2197",
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(
                        interactionSource = linkInteraction,
                        indication = ripple(color = HUD.Blood.copy(alpha = 0.25f)),
                        onClickLabel = "Open ${provider.displayName} key console",
                        role = Role.Button,
                        // openUri throws when no browser can take the URI; a vault screen should
                        // never crash because a phone is missing a browser.
                        onClick = { runCatching { uriHandler.openUri(provider.consoleUrl) } }
                    )
                    .semantics {
                        contentDescription = "Get a ${provider.displayName} API key in the browser"
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontFamily = HudFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = HUD.Blood,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = entry.key,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            // Masked unless the user explicitly asked to see it: a live credential on screen
            // survives in screenshots and screen shares long after the user has left.
            visualTransformation = if (entry.isRevealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            placeholder = {
                Text(
                    text = provider.keyHint,
                    fontSize = 15.sp,
                    fontFamily = HudFont,
                    color = HUD.TextMuted.copy(alpha = 0.55f)
                )
            },
            singleLine = true,
            // Password keyboard keeps the IME out of the autocorrect/suggestion path, where a key
            // would otherwise be offered to (and potentially learned by) the keyboard's dictionary.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false
            ),
            shape = RoundedCornerShape(3.dp),
            // Material's default outlined field is a white-ish surface with a purple focus ring;
            // both would import a foreign surface into the black HUD, so every colour role is
            // re-pointed at the palette here.
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HUD.TextPrimary,
                unfocusedTextColor = HUD.TextPrimary,
                focusedContainerColor = HUD.Void.copy(alpha = 0.35f),
                unfocusedContainerColor = HUD.Void.copy(alpha = 0.35f),
                cursorColor = HUD.Blood,
                focusedBorderColor = HUD.Blood,
                unfocusedBorderColor = HUD.Hairline,
                focusedPlaceholderColor = HUD.TextMuted.copy(alpha = 0.55f),
                unfocusedPlaceholderColor = HUD.TextMuted.copy(alpha = 0.55f),
                focusedTrailingIconColor = HUD.Blood,
                unfocusedTrailingIconColor = HUD.BloodDim
            ),
            trailingIcon = {
                IconButton(
                    onClick = onToggleReveal,
                    // A SHOW/HIDE word instead of an eye icon: the HUD ships no icon set, and the
                    // word is unambiguous even at a glance. The semantics below are the real
                    // accessibility contract for TalkBack.
                    modifier = Modifier.semantics {
                        contentDescription = if (entry.isRevealed) {
                            "Hide ${provider.displayName} API key"
                        } else {
                            "Show ${provider.displayName} API key"
                        }
                        // No click label here: IconButton owns the click action, and the
                        // accessibility contract is the description plus the state below.
                        stateDescription = if (entry.isRevealed) "Key visible" else "Key masked"
                    }
                ) {
                    Text(
                        text = if (entry.isRevealed) "HIDE" else "SHOW",
                        fontSize = 12.sp,
                        fontFamily = HudFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (entry.isRevealed) HUD.Blood else HUD.BloodDim,
                        maxLines = 1
                    )
                }
            }
        )

        // Probe telemetry: the HTTP code and the failure reason behind the chip, in muted text so
        // they inform without competing with the verdict.
        val code = entry.status.httpCode
        val reason = entry.status.detail
        if (code != null || reason != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (code != null) {
                    Text(
                        text = "HTTP $code",
                        fontSize = 11.sp,
                        fontFamily = HudFont,
                        letterSpacing = 0.8.sp,
                        color = HUD.TextMuted,
                        maxLines = 1
                    )
                }
                if (code != null && reason != null) {
                    Spacer(Modifier.width(8.dp))
                }
                if (reason != null) {
                    Text(
                        text = reason,
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        fontFamily = HudFont,
                        letterSpacing = 0.4.sp,
                        color = HUD.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // TEST is the point of the screen, SAVE is the quiet commit, CLEAR is deliberately the
        // faintest control on the pane: it is the only destructive one, so it must never be the
        // thing a thumb finds by accident.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudButton(
                text = "TEST",
                emphasis = HudButtonEmphasis.PRIMARY,
                onClick = onTest,
                busy = entry.isTesting,
                a11yLabel = "Test ${provider.displayName} API key",
                modifier = Modifier.weight(1f)
            )
            HudButton(
                text = "SAVE",
                emphasis = HudButtonEmphasis.OUTLINE,
                onClick = onSave,
                a11yLabel = "Save ${provider.displayName} API key",
                modifier = Modifier.weight(1f)
            )
            HudButton(
                text = "CLEAR",
                emphasis = HudButtonEmphasis.GHOST,
                onClick = onClear,
                // Nothing stored and nothing typed means there is nothing to clear; a disabled
                // button is the cheapest guard against a mis-tap wiping a working key.
                enabled = entry.hasKey,
                a11yLabel = "Clear ${provider.displayName} API key",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------------------------

/** Colour of a verdict: green only for a live 2xx, amber for "check me", red for a rejection. */
private fun ProviderStatus.chipColor(): Color = when (this) {
    is ProviderStatus.Active -> HUD.Online
    is ProviderStatus.Invalid -> HUD.Danger
    is ProviderStatus.QuotaLow -> HUD.Warn
    is ProviderStatus.ProviderError -> HUD.Warn
    is ProviderStatus.Unreachable -> HUD.TextMuted
    ProviderStatus.Missing -> HUD.TextMuted
    ProviderStatus.Untested -> HUD.TextMuted
    ProviderStatus.Testing -> HUD.BloodDim
}

/**
 * Verdict pill next to the provider name.
 *
 * Not a Material chip: AssistChip/FilterChip own their label and container colours and paint a
 * tonal surface, which would put a light-material slab inside the HUD. This is the same hand-built
 * dot-and-caption that the Groq status row already uses, so the two screens agree.
 */
@Composable
private fun StatusChip(providerName: String, status: ProviderStatus) {
    val label = status.chipLabel
    val color = status.chipColor()
    val shape = RoundedCornerShape(3.dp)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            // Merged so TalkBack announces one coherent verdict ("Gemini status: ... Active")
            // instead of a stray dot plus the caption, and so the label is a single queryable node.
            .semantics(mergeDescendants = true) {
                contentDescription = "$providerName status: $label"
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = HudFont,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            color = color,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

/** Visual weight of a HUD button; CLEAR must lose to SAVE, which must lose to TEST. */
private enum class HudButtonEmphasis { PRIMARY, OUTLINE, GHOST }

/**
 * Hand-drawn HUD button.
 *
 * Material's Button/FilledTonalButton bring their own container, ripple and elevation roles, so
 * this reproduces the pattern SystemSettingsScreen established: a bordered rectangle whose accent
 * is the only thing that changes with emphasis.
 *
 * [busy] additionally disables the action and swaps the caption for a spinner, which is how the
 * TEST button reports an in-flight probe without the button jumping size.
 */
@Composable
private fun HudButton(
    text: String,
    emphasis: HudButtonEmphasis,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    a11yLabel: String = text,
) {
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !busy

    val border = when {
        !active -> HUD.HairlineSoft
        emphasis == HudButtonEmphasis.PRIMARY -> HUD.Blood
        emphasis == HudButtonEmphasis.OUTLINE -> HUD.BloodDim
        else -> HUD.HairlineSoft
    }
    val fill = when {
        !active -> Color.Transparent
        emphasis == HudButtonEmphasis.PRIMARY -> HUD.Blood.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val caption = when {
        !active -> HUD.TextMuted.copy(alpha = 0.45f)
        emphasis == HudButtonEmphasis.PRIMARY -> HUD.TextPrimary
        emphasis == HudButtonEmphasis.OUTLINE -> HUD.TextPrimary
        else -> HUD.TextMuted
    }

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = HUD.Blood.copy(alpha = 0.35f)),
                enabled = active,
                // The spoken label lives in the semantics block below instead of here: setting both
                // makes TalkBack announce the same phrase twice.
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                contentDescription = a11yLabel
                if (busy) stateDescription = "Testing"
            },
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = HUD.BloodDim,
                strokeWidth = 1.5.dp
            )
        } else {
            Text(
                text = text,
                fontSize = 15.sp,
                fontFamily = HudFont,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = caption,
                maxLines = 1
            )
        }
    }
}

/** One monospaced family for the whole screen; the HUD has no proportional text. */
private val HudFont = FontFamily.Monospace

// ---------------------------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------------------------

/**
 * Renders the full screen from a fixed state, with no ViewModel, context or vault involved.
 *
 * The sample deliberately covers the interesting verdicts (a green Active, an amber quota warning,
 * a missing key, an unreachable host) so a layout regression in the chip row is visible at a glance.
 */
@Preview(
    name = "API Key Manager",
    showBackground = true,
    backgroundColor = 0xFF241016,
    widthDp = 420,
    heightDp = 1300
)
@Composable
private fun ApiKeyManagerContentPreview() {
    val previewState = ApiKeyManagerUiState(
        entries = listOf(
            ApiKeyEntry(
                provider = ApiProvider.GEMINI,
                key = "AIzaSyExampleKey",
                status = ProviderStatus.Active(200)
            ),
            ApiKeyEntry(
                provider = ApiProvider.GROQ,
                key = "gsk_exampleKey",
                status = ProviderStatus.QuotaLow(429)
            ),
            ApiKeyEntry(
                provider = ApiProvider.CEREBRAS,
                key = "",
                status = ProviderStatus.Missing
            ),
            ApiKeyEntry(
                provider = ApiProvider.OPENROUTER,
                key = "sk-or-exampleKey",
                status = ProviderStatus.Unreachable("DNS lookup failed for openrouter.ai")
            )
        ),
        savedProvider = ApiProvider.GEMINI
    )

    ApiKeyManagerContent(
        state = previewState,
        onBack = {},
        onKeyChange = { _, _ -> },
        onToggleReveal = {},
        onTest = {},
        onSave = {},
        onClear = {}
    )
}
