package io.github.calendaralarm.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

val DarkColorScheme = darkColorScheme(
    primary = CalPurple,
    onPrimary = CalWhite,
    primaryContainer = CalDarkPurple,
    secondary = CalRed,
    onSecondary = CalWhite,
    background = CalDark,
    onBackground = CalWhite,
    surface = CalDarkSurface,
    onSurface = CalWhite,
    surfaceVariant = CalDark,
    onSurfaceVariant = CalGrey,
    error = CalRed,
    onError = CalWhite
)

val LightColorScheme = lightColorScheme(
    primary = CalPurple,
    onPrimary = CalWhite,
    primaryContainer = CalPurple.copy(alpha = 0.2f),
    secondary = CalRed,
    onSecondary = CalWhite,
    background = CalLight,
    onBackground = CalDark,
    surface = CalWhite,
    onSurface = CalDark,
    surfaceVariant = CalLight,
    onSurfaceVariant = CalGrey,
    error = CalRed,
    onError = CalWhite
)
