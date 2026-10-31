package com.seema.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.seema.jarvis.ui.theme.GlassBottom
import com.seema.jarvis.ui.theme.GlassTop
import com.seema.jarvis.ui.theme.NeonCyan
import com.seema.jarvis.ui.theme.TextSecondary

/**
 * Translucent "glass" panel with a glowing edge and a titled header. [content] fills the body;
 * by default it shows that no data source is connected yet.
 */
@Composable
fun GlassPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = { NoDataLink() }
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GlassTop, GlassBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(NeonCyan.copy(alpha = 0.6f), NeonCyan.copy(alpha = 0.1f))), shape)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(NeonCyan, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = NeonCyan)
        }
        Box(
            Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(NeonCyan.copy(alpha = 0.25f))
        )
        content()
    }
}

/** Placeholder body until a real data feed is attached to the panel. */
@Composable
fun ColumnScope.NoDataLink() {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text("— NO DATA LINK —", style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.5f))
    }
}
