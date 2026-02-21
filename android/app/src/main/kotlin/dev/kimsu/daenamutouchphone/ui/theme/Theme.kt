package dev.kimsu.daenamutouchphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BambuGreen,
    onPrimary = Color.White,
    primaryContainer = BambuGreenDark,
    onPrimaryContainer = Color.White,
    secondary = BambuLightGray,
    onSecondary = BambuBlack,
    background = BambuBlack,
    onBackground = BambuWhite,
    surface = BambuDarkGray,
    onSurface = BambuWhite,
    surfaceVariant = BambuMidGray,
    onSurfaceVariant = BambuWhite,
    error = StatusFailed,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = BambuGreen,
    onPrimary = Color.White,
    primaryContainer = BambuGreenDark,
    onPrimaryContainer = Color.White,
    secondary = BambuLightGray,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = BambuBlack,
    surface = Color(0xFFF5F5F5),
    onSurface = BambuBlack,
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = BambuBlack,
    error = StatusFailed,
    onError = Color.White,
)

@Composable
fun DaenamutouchphoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
