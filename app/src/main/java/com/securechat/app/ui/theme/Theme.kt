package com.securechat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

enum class ThemeSelection {
    LIGHT, DARK, AUTO
}

val LocalThemeSelection = compositionLocalOf { mutableStateOf(ThemeSelection.AUTO) }

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = Slate500,
    tertiary = PrimaryBlueHover,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = Slate200,
    onSurface = Slate200,
    surfaceVariant = DarkSurfaceHover,
    onSurfaceVariant = Slate400,
    outline = DarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = Slate500,
    tertiary = PrimaryBlueHover,
    background = Slate50,
    surface = Color.White,
    onBackground = Slate900,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    outline = Slate200
)

@Composable
fun SecureChatTheme(
    content: @Composable () -> Unit
) {
    val themeSelection = remember { mutableStateOf(ThemeSelection.AUTO) }
    
    val darkTheme = when (themeSelection.value) {
        ThemeSelection.LIGHT -> false
        ThemeSelection.DARK -> true
        ThemeSelection.AUTO -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalThemeSelection provides themeSelection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
