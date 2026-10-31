package com.seema.assistant.ui.jarvis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared HUD chrome.
 *
 * Glass here is faked rather than blurred: a translucent fill, a 1dp edge-light border and a
 * faint top sheen. A real blur layer would have to sample the animated code stream behind it on
 * every frame, which is exactly the wrong thing to do on a phone that is already running the
 * wake word engine.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = HUD.GlassEdge,
    content: @Composable BoxScope.() -> Unit
) {
    val sheen = Brush.verticalGradient(
        colors = listOf(HUD.SheenTop, androidx.compose.ui.graphics.Color.Transparent)
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(HUD.Glass)
            .background(sheen)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(14.dp),
        content = content
    )
}

/** Section caption: tiny, wide-tracked, dim red, with a hairline under it. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = HUD.BloodDim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HUD.Hairline))
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
    }
}

/**
 * Bottom-navigation glyphs, hand-drawn.
 *
 * Material icons are deliberately not used: their shapes read as a consumer app. These are
 * drawn from primitives so the navigation matches the instrument language of the HUD and the
 * app does not need the icons-extended dependency.
 */
enum class NavGlyph { SETTINGS, FILES, CODING, TERMINAL, PROFILE }

@Composable
fun NavBar(
    selected: NavGlyph?,
    onSelect: (NavGlyph) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HUD.Black.copy(alpha = 0.92f))
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavGlyph.entries.forEach { glyph ->
            NavItem(glyph = glyph, active = selected == glyph, onClick = { onSelect(glyph) })
        }
    }
}

@Composable
private fun NavItem(glyph: NavGlyph, active: Boolean, onClick: () -> Unit) {
    val color = if (active) HUD.Blood else HUD.BloodDim
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val u = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val stroke = 1.3f * u
            when (glyph) {
                NavGlyph.SETTINGS -> {
                    drawCircle(color, radius = h * 0.26f, center = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    for (i in 0 until 8) {
                        val a = i * 45f * (Math.PI.toFloat() / 180f)
                        drawLine(
                            color,
                            androidx.compose.ui.geometry.Offset(w / 2f + kotlin.math.cos(a) * h * 0.34f, h / 2f + kotlin.math.sin(a) * h * 0.34f),
                            androidx.compose.ui.geometry.Offset(w / 2f + kotlin.math.cos(a) * h * 0.48f, h / 2f + kotlin.math.sin(a) * h * 0.48f),
                            stroke
                        )
                    }
                }
                NavGlyph.FILES -> {
                    drawRoundRect(
                        color,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.22f),
                        size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.56f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
                    )
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.5f), stroke * 0.8f)
                }
                NavGlyph.CODING -> {
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.28f), androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.5f), stroke)
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.72f), stroke)
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.28f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.5f), stroke)
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.72f), stroke)
                }
                NavGlyph.TERMINAL -> {
                    drawRoundRect(
                        color,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.22f),
                        size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.56f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
                    )
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.44f), androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.56f), stroke)
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.56f), androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.68f), stroke)
                }
                NavGlyph.PROFILE -> {
                    drawCircle(color, radius = h * 0.16f, center = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.34f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawArc(
                        color,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.56f),
                        size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.5f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
                    )
                }
            }
        }
    }
}