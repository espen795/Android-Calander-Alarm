package io.github.calendaralarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, color = CalWhite),
    displayMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, color = CalWhite),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = CalWhite),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = CalWhite),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = CalWhite),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, color = CalWhite),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = CalGrey),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CalWhite)
)

@Composable
fun CalendarAlarmTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        content = content
    )
}
