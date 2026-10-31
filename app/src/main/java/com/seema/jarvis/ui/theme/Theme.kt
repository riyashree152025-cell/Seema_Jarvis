package com.seema.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HudColors = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepSpace,
    secondary = TextSecondary,
    tertiary = TacticalAmber,
    background = DeepSpace,
    onBackground = NeonCyan,
    surface = DeepSpace,
    onSurface = TextSecondary
)

@Composable
fun SeemaJarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HudColors, typography = HudTypography, content = content)
}
