package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00262B),
    primaryContainer = ElectricCyanDim,
    onPrimaryContainer = ElectricCyan,
    secondary = NeonEmerald,
    onSecondary = Color(0xFF002915),
    secondaryContainer = NeonEmeraldDim,
    onSecondaryContainer = NeonEmerald,
    tertiary = AmberPulse,
    onTertiary = Color(0xFF2B1C00),
    tertiaryContainer = AmberPulseDim,
    onTertiaryContainer = AmberPulse,
    error = CrimsonAlert,
    onError = Color(0xFF360004),
    errorContainer = CrimsonAlertDim,
    onErrorContainer = CrimsonAlert,
    background = CyberBackground,
    onBackground = TextPrimary,
    surface = CyberSurface,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = CyberSurfaceElevated
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberDarkColorScheme,
        typography = Typography,
        content = content
    )
}
