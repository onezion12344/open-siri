package com.opensiri.agent.bootstrap.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF1A1721)
val Surface = Color(0xFF252232)
val Primary = Color(0xFF6750A4)
val OnPrimary = Color.White
val OnSurface = Color(0xFFE8DEF8)
val OnSurfaceVariant = Color(0x80FFFFFF)
val Outline = Color(0x1AFFFFFF)
val Success = Color(0xFF4CAF50)
val SuccessLight = Color(0xFF81C784)
val Error = Color(0xFFEF5350)
val Warn = Color(0xFFFFB74D)
val ActiveGlow = Color(0x806750A4)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    surface = Surface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
)

@Composable
fun OneZionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
