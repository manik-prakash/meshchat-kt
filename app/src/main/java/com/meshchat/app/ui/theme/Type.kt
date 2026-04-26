package com.meshchat.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MeshTypography = Typography(
    bodyLarge  = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary),
    bodySmall  = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primary),
    titleMedium= TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted),
)
