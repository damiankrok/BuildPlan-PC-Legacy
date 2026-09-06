package com.buildplan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app is dark-only by design; it does not follow the system light theme and
 * does not use dynamic colour. Revisit if a light theme is ever requested.
 */
private val BuildPlanColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = PanelHigh,
    onPrimaryContainer = Ink,
    secondary = InkMuted,
    onSecondary = Canvas,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    onError = OnDanger,
)

@Composable
fun BuildPlanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BuildPlanColorScheme,
        typography = BuildPlanTypography,
        shapes = BuildPlanShapes,
        content = content,
    )
}
