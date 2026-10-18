package com.tauru.astrbotphoneagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF357A9E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E9F3),
    onPrimaryContainer = Color(0xFF214C64),
    secondary = Color(0xFF7C8E87),
    onSecondary = Color.White,
    background = Color(0xFFE8F2F4),
    onBackground = Color(0xFF172D39),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF172D39),
    surfaceVariant = Color(0xFFDCE9ED),
    onSurfaceVariant = Color(0xFF536A75),
    outline = Color(0xFF7C969F),
    outlineVariant = Color(0xFFCCDDE1),
    error = Color(0xFFAC4858),
    onError = Color.White,
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF96CAE3),
    onPrimary = Color(0xFF123344),
    primaryContainer = Color(0xFF294F64),
    onPrimaryContainer = Color(0xFFD2EDFA),
    secondary = Color(0xFFB7CBC0),
    onSecondary = Color(0xFF20342B),
    background = Color(0xFF122530),
    onBackground = Color(0xFFECF4F7),
    surface = Color(0xFF203642),
    onSurface = Color(0xFFECF4F7),
    surfaceVariant = Color(0xFF2B4552),
    onSurfaceVariant = Color(0xFFADC2CD),
    outline = Color(0xFF7D99A7),
    outlineVariant = Color(0xFF49616D),
    error = Color(0xFFFFAFBC),
    onError = Color(0xFF571D2A),
)

// Expressive-ish geometry: noticeably rounder than the M3 defaults, which is
// what makes a phone UI read as a system app rather than a utility.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun PhoneAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        // Material You: follow the wallpaper palette on Android 12+.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
