package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RetroDarkColorScheme = darkColorScheme(
    primary = RetroAmberGold,
    onPrimary = RetroGraphite,
    primaryContainer = RetroBrushedMid,
    onPrimaryContainer = TextPrimaryLight,
    secondary = ClassicGlossBlue,
    onSecondary = ChromeHighlight,
    secondaryContainer = RetroBrushedTop,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = RecordCrimson,
    onTertiary = ChromeHighlight,
    background = RetroGraphite,
    onBackground = TextPrimaryLight,
    surface = RetroCharcoal,
    onSurface = TextPrimaryLight,
    surfaceVariant = RetroBrushedMid,
    onSurfaceVariant = TextSecondaryMuted,
    outline = ChromeShadow,
    error = RecordCrimson,
    onError = ChromeHighlight,
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RetroDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
