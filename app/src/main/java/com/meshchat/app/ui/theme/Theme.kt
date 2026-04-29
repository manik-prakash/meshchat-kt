package com.meshchat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary          = Primary,
    onPrimary        = PrimaryInk,
    secondary        = Accent,
    onSecondary      = PrimaryInk,
    background       = Background,
    onBackground     = TextPrimary,
    surface          = Surface,
    onSurface        = TextPrimary,
    error            = ErrorColor,
    onError          = Background,
)

@Composable
fun MeshChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = MeshTypography,
        content     = content
    )
}
