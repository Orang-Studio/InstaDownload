package com.vakarux.instadownload

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFE91E63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E4),
    onPrimaryContainer = Color(0xFF3E001A),

    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFC7F7EC),
    onSecondaryContainer = Color(0xFF003D35),

    tertiary = Color(0xFF7C5295),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD6FF),
    onTertiaryContainer = Color(0xFF2D1B2E),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFFFB1C8),
    surfaceDim = Color(0xFFDDD8DD),
    surfaceBright = Color(0xFFFFFBFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2F7),
    surfaceContainer = Color(0xFFF1ECF1),
    surfaceContainerHigh = Color(0xFFEBE6EB),
    surfaceContainerHighest = Color(0xFFE6E1E5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF5E1139),
    primaryContainer = Color(0xFF7D2951),
    onPrimaryContainer = Color(0xFFFFD8E4),

    secondary = Color(0xFF4DDAC6),
    onSecondary = Color(0xFF00504A),
    secondaryContainer = Color(0xFF00695E),
    onSecondaryContainer = Color(0xFFC7F7EC),

    tertiary = Color(0xFFD1B3E6),
    onTertiary = Color(0xFF432C56),
    tertiaryContainer = Color(0xFF5A426D),
    onTertiaryContainer = Color(0xFFEDD4FF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF131316),
    onBackground = Color(0xFFE6E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E1E6),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFFE91E63),
    surfaceDim = Color(0xFF131316),
    surfaceBright = Color(0xFF39353C),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1E),
    surfaceContainer = Color(0xFF1F1F22),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF353438)
)

@Composable
fun InstaDownloadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}