package com.seema.assistant.ui.jarvis

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/**
 * MARK-III: data matrix.
 *
 * Falling glyph columns behind a translucent server-status block. The rain is rendered as a
 * single translated column of pre-generated monospaced lines rather than as per-frame canvas
 * text: one graphics-layer offset per frame instead of hundreds of text draws, which is what
 * keeps this screen from competing with the wake word engine for CPU.
 */
@Composable
fun MatrixTerminalScreen(
    logLines: List<String>,
    telemetry: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "matrix")
    val fall by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "fall"
    )

    val rain = remember { buildRain() }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        // ---- glyph rain, scrolled by a single translation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = fall * 720f - 360f }
        ) {
            Column(Modifier.fillMaxSize()) {
                rain.forEach { line ->
                    Text(
                        text = line,
                        color = HUD.Blood.copy(alpha = 0.22f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Header()

            Spacer(Modifier.height(10.dp))
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    telemetry.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                color = HUD.TextMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = value,
                                color = HUD.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            GlassPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "STDOUT",
                        color = HUD.BloodDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.4.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(HUD.Hairline))
                    Spacer(Modifier.height(8.dp))
                    val visible = if (logLines.isEmpty()) listOf("awaiting input") else logLines.takeLast(8)
                    visible.forEachIndexed { index, line ->
                        val newest = index == visible.lastIndex
                        Text(
                            text = "$ " + line + if (newest) " ▌" else "",
                            color = if (newest) HUD.TextPrimary else HUD.TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("MARK-III", color = HUD.BloodDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
        Text("DATA MATRIX", color = HUD.BloodDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
    }
}

/**
 * 34 fixed-width glyph lines. Deterministic seed so the same phone shows the same rain across
 * launches — random noise that reshuffles on every recomposition looks like flicker, not data.
 */
private fun buildRain(): List<String> {
    val alphabet = "0123456789ABCDEF:/+>_".toCharArray()
    val random = Random(0x5E3A)
    return List(34) {
        val width = 48
        val chars = CharArray(width) { _ ->
            if (random.nextInt(100) < 38) ' ' else alphabet[random.nextInt(alphabet.size)]
        }
        String(chars)
    }
}