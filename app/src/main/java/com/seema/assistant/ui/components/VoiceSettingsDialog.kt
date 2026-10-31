package com.seema.assistant.ui.components

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.seema.assistant.AiSettings
import com.seema.assistant.BuildConfig
import com.seema.assistant.GeminiKeyCheck
import com.seema.assistant.SeemaState
import com.seema.assistant.VoiceSettings
import com.seema.assistant.ui.theme.AlertRed
import com.seema.assistant.ui.theme.DeepSpace
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.PanelSurface
import com.seema.assistant.ui.theme.SpeakingGreen
import com.seema.assistant.ui.theme.TextSecondary
import com.seema.assistant.ui.theme.ThinkingAmber
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * All of SEEMA's settings: AI (Gemini key, model, weather city), listening language, wake word
 * sensitivity with a live meter, and voice (TTS voice, pitch, speed, JARVIS preset).
 * Saved values apply to the next command; nothing needs a restart.
 */
@Composable
fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val voice = remember { VoiceSettings(context) }
    val ai = remember { AiSettings(context) }
    val scope = rememberCoroutineScope()

    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    val preview = remember { VoicePreview(context) { voices = it } }
    DisposableEffect(Unit) { onDispose { preview.shutdown() } }

    // AI
    var apiKey by remember { mutableStateOf(ai.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(ai.model) }
    var homeCity by remember { mutableStateOf(ai.homeCity) }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    // Language and wake word
    var language by remember { mutableStateOf(voice.speechLanguage) }
    var threshold by remember { mutableFloatStateOf(voice.wakeThreshold) }
    var muteBeep by remember { mutableStateOf(voice.muteRecognitionBeep) }
    var preferOffline by remember { mutableStateOf(voice.preferOfflineRecognition) }
    // Voice
    var selected by remember { mutableStateOf(voice.voiceName) }
    var pitch by remember { mutableFloatStateOf(voice.pitch) }
    var rate by remember { mutableFloatStateOf(voice.speechRate) }

    val wakeWord = remember {
        if (context.assets.list("")?.contains("seema.onnx") == true) "SEEMA" else "HEY JARVIS"
    }
    val liveScore by SeemaState.wakeScore.collectAsState()

    val shape = RoundedCornerShape(8.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
                .background(PanelSurface, shape)
                .border(1.dp, JarvisCyan.copy(alpha = 0.5f), shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("SETTINGS", style = MaterialTheme.typography.headlineSmall, color = JarvisCyan)

            // ------------------------------------------------------------- AI
            SectionTitle("AI · GEMINI")
            HudTextField(
                value = apiKey,
                onValueChange = { apiKey = it; keyStatus = null },
                label = "Gemini API key",
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password
            )
            Hint(
                if (apiKey.isBlank() && BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                    "Empty = using the key built in from local.properties."
                } else {
                    "Free key: aistudio.google.com/apikey → Create API key."
                }
            )
            HudTextField(
                value = model,
                onValueChange = { model = it; keyStatus = null },
                label = "Model (empty = ${BuildConfig.SEEMA_GEMINI_MODEL})"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODEL_PRESETS.forEach { preset -> SmallButton(preset) { model = preset; keyStatus = null } }
            }
            Hint("If one model's free daily quota runs out, switch to another and CHECK.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(if (showKey) "HIDE KEY" else "SHOW KEY") { showKey = !showKey }
                DialogButton("CHECK KEY") {
                    keyStatus = "Checking…"
                    val key = apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }
                    val chosen = model.ifBlank { BuildConfig.SEEMA_GEMINI_MODEL }
                    scope.launch { keyStatus = GeminiKeyCheck.check(key, chosen) }
                }
            }
            keyStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        status.startsWith("✓") -> SpeakingGreen
                        status.startsWith("⚠") -> ThinkingAmber
                        status.startsWith("✗") -> AlertRed
                        else -> TextSecondary
                    }
                )
            }
            HudTextField(
                value = homeCity,
                onValueChange = { homeCity = it },
                label = "Home city for weather (empty = ${BuildConfig.SEEMA_HOME_CITY.ifBlank { "use location" }})"
            )

            // ------------------------------------------------------- language
            SectionTitle("LISTENING LANGUAGE")
            VoiceSettings.SPEECH_LANGUAGES.forEach { (tag, label) ->
                OptionRow(label, language == tag) { language = tag }
            }
            Hint("SEEMA replies in the language you speak.")
            SwitchRow("Always use offline speech recognition", preferOffline) { preferOffline = it }

            // ------------------------------------------------------ wake word
            SectionTitle("WAKE WORD · \"$wakeWord\"")
            Text(
                "SENSITIVITY ${"%.3f".format(Locale.ROOT, threshold)}   (lower = wakes more easily)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            HudSlider(threshold, VoiceSettings.MIN_WAKE_THRESHOLD..VoiceSettings.MAX_WAKE_THRESHOLD) { threshold = it }
            WakeMeter(liveScore, threshold)
            Hint(
                "Say \"$wakeWord\": the bar should cross the line. If she does not wake, move the slider " +
                    "left; if she wakes by mistake, move it right. Live score ${"%.3f".format(Locale.ROOT, liveScore)}."
            )
            SwitchRow("Silence the beep when listening starts", muteBeep) { muteBeep = it }

            // ----------------------------------------------------------- voice
            SectionTitle("VOICE")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(DeepSpace.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                item { OptionRow("Automatic (by language)", selected == null) { selected = null } }
                items(voices, key = { it.name }) { v ->
                    OptionRow(voiceLabel(v), selected == v.name) { selected = v.name }
                }
            }
            if (voices.isEmpty()) {
                Hint("Loading voices… Install more in Settings → System → Languages → Text-to-speech.")
            }
            Text("PITCH  ${"%.2f".format(Locale.ROOT, pitch)}   (lower = deeper)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            HudSlider(pitch, VoiceSettings.MIN..VoiceSettings.MAX) { pitch = it }
            Text("SPEED  ${"%.2f".format(Locale.ROOT, rate)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            HudSlider(rate, VoiceSettings.MIN..VoiceSettings.MAX) { rate = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("JARVIS PRESET") {
                    preview.jarvisVoice(voices)?.let { selected = it.name }
                    pitch = VoiceSettings.JARVIS_PITCH
                    rate = VoiceSettings.JARVIS_RATE
                }
                DialogButton("▶ TEST") {
                    preview.speak("Good evening. All systems are online and at your service.", selected, pitch, rate)
                }
            }

            // ------------------------------------------------------------ save
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("CANCEL", onClick = onDismiss)
                DialogButton("SAVE") {
                    ai.apiKey = apiKey
                    ai.model = model
                    ai.homeCity = homeCity
                    voice.speechLanguage = language
                    voice.wakeThreshold = threshold
                    voice.muteRecognitionBeep = muteBeep
                    voice.preferOfflineRecognition = preferOffline
                    voice.voiceName = selected
                    voice.pitch = pitch
                    voice.speechRate = rate
                    onDismiss()
                }
            }
        }
    }
}

/** Live wake word score as a bar, with the current threshold as a line across it. */
@Composable
private fun WakeMeter(score: Float, threshold: Float) {
    val max = VoiceSettings.MAX_WAKE_THRESHOLD
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(DeepSpace, RoundedCornerShape(3.dp))
    ) {
        val fill = (score / max).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill)
                .background(if (score >= threshold) SpeakingGreen else JarvisCyan.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
        )
        Box(
            Modifier
                .offset(x = maxWidth * (threshold / max).coerceIn(0f, 1f))
                .width(2.dp)
                .fillMaxHeight()
                .background(ThinkingAmber)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = JarvisCyan)
    Box(Modifier.fillMaxWidth().height(1.dp).background(JarvisCyan.copy(alpha = 0.3f)))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.8f))
}

@Composable
private fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisCyan,
            unfocusedBorderColor = JarvisCyan.copy(alpha = 0.4f),
            focusedTextColor = JarvisCyan,
            unfocusedTextColor = TextSecondary,
            focusedLabelColor = JarvisCyan,
            unfocusedLabelColor = TextSecondary,
            cursorColor = JarvisCyan
        )
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) JarvisCyan else TextSecondary)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = JarvisCyan, checkedTrackColor = JarvisCyan.copy(alpha = 0.4f))
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun HudSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
    )
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = JarvisCyan,
        modifier = Modifier
            .border(1.dp, JarvisCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** "English (United Kingdom) · en-gb-x-rjs-local", marked when it needs the internet. */
private fun voiceLabel(voice: Voice): String =
    "${voice.locale.getDisplayName(Locale.ENGLISH)} · ${voice.name}" +
        if (voice.isNetworkConnectionRequired) "  (online)" else ""

private val MODEL_PRESETS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

/** A private TextToSpeech used only to list voices and play the test line. */
private class VoicePreview(context: Context, onReady: (List<Voice>) -> Unit) {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) onReady(installedVoices())
    }

    /** English and Hindi voices that are actually installed, grouped by language. */
    fun installedVoices(): List<Voice> =
        tts.voices.orEmpty()
            .filter { it.locale.language in LANGUAGES }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.isNetworkConnectionRequired }, { it.name }))

    /** Best match for the JARVIS preset: a male British voice, preferring offline ones. */
    fun jarvisVoice(voices: List<Voice>): Voice? =
        VoiceSettings.JARVIS_VOICE_PREFIXES.firstNotNullOfOrNull { prefix ->
            voices.filter { it.name.startsWith(prefix) }.minByOrNull { it.isNetworkConnectionRequired }
        } ?: voices.firstOrNull { it.locale.country == "GB" && !it.isNetworkConnectionRequired }

    fun speak(text: String, voiceName: String?, pitch: Float, rate: Float) {
        val voice = voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.forLanguageTag("en-IN"))
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seema_preview")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        val LANGUAGES = setOf("en", "hi")
    }
}
