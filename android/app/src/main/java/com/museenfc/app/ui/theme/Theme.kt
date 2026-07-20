package com.museenfc.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Même identité visuelle que l'écran de supervision web : encre profonde + accent laiton doré.
val Ink = Color(0xFF0B0F1A)
val InkPanel = Color(0xFF141A2B)
val Gold = Color(0xFFD4AF37)
val Green = Color(0xFF2ECC71)
val Orange = Color(0xFFF5A623)
val Red = Color(0xFFE74C3C)
val TextDim = Color(0xFF9AA4BD)

private val MuseeColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    background = Ink,
    surface = InkPanel,
    onBackground = Color(0xFFEEF1F7),
    onSurface = Color(0xFFEEF1F7),
    error = Red,
)

@Composable
fun MuseeNfcTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MuseeColorScheme,
        content = content,
    )
}
