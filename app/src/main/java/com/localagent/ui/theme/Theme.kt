package com.localagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val Mint = Color(0xFF34D399)
private val Teal = Color(0xFF14B8A6)
private val Slate950 = Color(0xFF020617)
private val Slate900 = Color(0xFF0F172A)
private val Slate100 = Color(0xFFF1F5F9)

private val DarkColors =
    darkColorScheme(
        primary = Mint,
        onPrimary = Slate950,
        secondary = Teal,
        onSecondary = Slate950,
        tertiary = Mint,
        background = Slate950,
        surface = Slate900,
        onSurface = Slate100,
        surfaceVariant = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFFCBD5E1),
    )

private val LightColors =
    lightColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        secondary = Mint,
        onSecondary = Slate950,
        tertiary = Teal,
        background = Slate100,
        surface = Color.White,
        onSurface = Slate950,
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF475569),
    )

private val AgentShapes = Shapes()

@Composable
fun LocalAgentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = Typography()
    val typography =
        base.copy(
            bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
            bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
            titleLarge = base.titleLarge.copy(lineHeight = 28.sp),
            labelSmall = base.labelSmall.copy(lineHeight = 16.sp),
        )
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = typography,
        shapes = AgentShapes,
        content = content,
    )
}
