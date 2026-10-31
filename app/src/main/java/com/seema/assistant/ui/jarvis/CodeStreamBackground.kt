package com.seema.assistant.ui.jarvis

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live code stream rendered behind the HUD.
 *
 * The purpose is atmospheric: it makes the device look like it is running an agent even when it
 * is idle. It scrolls continuously and accelerates while the agent is processing, so the motion
 * is tied to real state rather than being a constant loop.
 *
 * Text is measured once (remembered) and then only drawn, so a frame costs one drawText per
 * visible line and no layout or allocation.
 */
@Composable
fun CodeStreamBackground(
    fast: Boolean,
    transparency: Float,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val style = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
    val layouts = remember(measurer) {
        CODE_LINES.map { measurer.measure(it, style) }
    }

    val transition = rememberInfiniteTransition(label = "codeStream")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (fast) 11_000 else 34_000,
                easing = LinearEasing
            )
        ),
        label = "scroll"
    )

    // More transparency means a fainter stream: the setting is "glass transparency", so it has
    // to affect what sits behind the glass as well.
    val alpha = (0.34f - transparency * 0.12f).coerceIn(0.14f, 0.34f)

    Canvas(modifier = modifier) {
        if (layouts.isEmpty()) return@Canvas
        val lineHeight = 15.dp.toPx()
        val total = lineHeight * layouts.size
        val scroll = progress * total
        val left = 10.dp.toPx()

        layouts.forEachIndexed { index, layout ->
            // Each line wraps individually so the stream never shows a gap at the seam.
            var y = (index * lineHeight - scroll) % total
            if (y < 0f) y += total
            y -= lineHeight
            if (y > -lineHeight && y < size.height) {
                drawText(
                    textLayoutResult = layout,
                    color = HUD.Blood.copy(alpha = alpha),
                    topLeft = Offset(left, y)
                )
            }
        }
    }
}

/**
 * A small corpus of real-looking lines rather than lorem ipsum: the brain, the shell and the
 * build are all things this project actually does, so the stream reads as plausible activity.
 */
private val CODE_LINES: List<String> = listOf(
    "import com.google.adk.kt.agents.LlmAgent",
    "val agent = LlmAgent(name = \"Seema\", model = gemini)",
    "// routing intent -> tool",
    "when (intent) { TORCH -> setFlashlight(true) }",
    "seema.remember(\"user\", \"timezone\", \"Asia/Kolkata\")",
    "fun speak(text: String) = tts.say(text)",
    "println(\"core online\")",
    "",
    "class SeemaVoiceService : Service() {",
    "  override fun onStartCommand(): Int {",
    "    enterForeground(); initEngine(); return START_STICKY",
    "  }",
    "}",
    "",
    "@Composable",
    "fun HudScreen() { Reactor(state, level) }",
    "val score = wakeWord.evaluate(frame)",
    "if (score > threshold) dispatchWakeWord(score)",
    "",
    "const cache = new Map();",
    "async function deploy(target) {",
    "  const build = await compile(target);",
    "  return publish(build);",
    "}",
    "",
    "<div class=\"hud\">",
    "  <canvas id=\"reactor\"></canvas>",
    "  <span class=\"status\">ONLINE</span>",
    "</div>",
    "",
    "$ ./gradlew assembleDebug",
    "> Task :app:compileDebugKotlin",
    "> Task :app:packageDebug",
    "BUILD SUCCESSFUL in 1m 12s",
    "",
    "SELECT * FROM memories ORDER BY saved_at DESC;",
    "// 42 rows in set",
    "",
    "fn main() -> Result<()> {",
    "    let hud = Hud::boot()?;",
    "    hud.run()",
    "}",
    "",
    "navigator.geolocation.getCurrentPosition(ok)",
    "socket.on('telemetry', (data) => render(data));",
    "const REACTOR_RPM = 3600;",
    "// MARK-II telemetry link established",
    "// MARK-III matrix stream attached",
    "",
    "def summarise(notes: list[str]) -> str:",
    "    return \"\\n\".join(notes[-10:])",
    "",
    "git commit -m \"hud: reactor protocol\"",
    "git push origin main",
    "",
    "adb install -r app-debug.apk",
    "Success",
    "",
    "val telemetry = mapOf(\"sig\" to 0.98, \"link\" to \"SECURE\")",
    "override fun onStateChanged(state: AgentState) {",
    "    reactor.animateTo(state.color)",
    "}",
    "",
    "// token budget: 8600 in / 180 out",
    "// latency budget: 900 ms to first audio byte",
    "",
    "curl -X POST \$GEMINI/live -d @frame.pcm",
    "200 OK",
    "",
    "fun rememberConfig(): SeemaConfig = store.load()",
    "config.commit(config.copy(glowIntensity = 0.7f))"
)