package com.ani.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ani.assistant.data.settings.ThemePreference

private val DarkColors = darkColorScheme(
    primary = AniPalette.Violet80,
    onPrimary = AniPalette.Violet20,
    primaryContainer = AniPalette.Violet30,
    onPrimaryContainer = AniPalette.Violet90,
    secondary = AniPalette.Slate80,
    onSecondary = AniPalette.Slate20,
    secondaryContainer = AniPalette.Slate30,
    onSecondaryContainer = AniPalette.Slate90,
    tertiary = AniPalette.Amber80,
    onTertiary = AniPalette.Amber30,
    tertiaryContainer = AniPalette.Amber30,
    onTertiaryContainer = AniPalette.Amber90,
    error = AniPalette.Red80,
    onError = AniPalette.Red30,
    errorContainer = AniPalette.Red30,
    onErrorContainer = AniPalette.Red90,
    background = AniPalette.Ink,
    onBackground = AniPalette.OnInk,
    surface = AniPalette.Ink,
    onSurface = AniPalette.OnInk,
    surfaceVariant = AniPalette.InkSoft,
    onSurfaceVariant = AniPalette.Slate80,
    outline = AniPalette.Slate40
)

private val LightColors = lightColorScheme(
    primary = AniPalette.Violet40,
    onPrimary = AniPalette.PaperSoft,
    primaryContainer = AniPalette.Violet90,
    onPrimaryContainer = AniPalette.Violet10,
    secondary = AniPalette.Slate40,
    onSecondary = AniPalette.PaperSoft,
    secondaryContainer = AniPalette.Slate90,
    onSecondaryContainer = AniPalette.Slate10,
    tertiary = AniPalette.Amber40,
    onTertiary = AniPalette.PaperSoft,
    tertiaryContainer = AniPalette.Amber90,
    onTertiaryContainer = AniPalette.Amber30,
    error = AniPalette.Red40,
    onError = AniPalette.PaperSoft,
    errorContainer = AniPalette.Red90,
    onErrorContainer = AniPalette.Red30,
    background = AniPalette.Paper,
    onBackground = AniPalette.OnPaper,
    surface = AniPalette.Paper,
    onSurface = AniPalette.OnPaper,
    surfaceVariant = AniPalette.PaperSoft,
    onSurfaceVariant = AniPalette.Slate40,
    outline = AniPalette.Slate80
)

/**
 * The app theme.
 *
 * Dynamic colour is honoured when the user leaves it on, because a phone-native assistant
 * that matches the wallpaper feels like part of the system rather than a visitor. The
 * hand-built scheme is the fallback and the identity.
 */
@Composable
fun AniTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useDynamicColor && dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AniTypography,
        content = content
    )
}
