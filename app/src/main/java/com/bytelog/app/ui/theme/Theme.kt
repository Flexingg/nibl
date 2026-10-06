package com.bytelog.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SeedDark = with(SeedTones) {
    darkColorScheme(
        primary = dPrimary, onPrimary = dOnPrimary, primaryContainer = dPrimaryContainer, onPrimaryContainer = dOnPrimaryContainer,
        inversePrimary = lPrimary,
        secondary = dSecondary, onSecondary = dOnSecondary, secondaryContainer = dSecondaryContainer, onSecondaryContainer = dOnSecondaryContainer,
        tertiary = dTertiary, onTertiary = dOnTertiary, tertiaryContainer = dTertiaryContainer, onTertiaryContainer = dOnTertiaryContainer,
        background = dSurface, onBackground = dOnSurface, surface = dSurface, onSurface = dOnSurface,
        surfaceVariant = dSurfaceVariant, onSurfaceVariant = dOnSurfaceVariant, surfaceTint = dPrimary,
        inverseSurface = dOnSurface, inverseOnSurface = Color(0xFF392E2B),
        outline = dOutline, outlineVariant = dOutlineVariant,
        surfaceBright = dBright, surfaceDim = dSurface,
        surfaceContainerLowest = dLowest, surfaceContainerLow = dLow, surfaceContainer = dContainer,
        surfaceContainerHigh = dHigh, surfaceContainerHighest = dHighest
    )
}

private val SeedLight = with(SeedTones) {
    lightColorScheme(
        primary = lPrimary, onPrimary = lOnPrimary, primaryContainer = lPrimaryContainer, onPrimaryContainer = lOnPrimaryContainer,
        inversePrimary = dPrimary,
        secondary = lSecondary, onSecondary = lOnSecondary, secondaryContainer = lSecondaryContainer, onSecondaryContainer = lOnSecondaryContainer,
        tertiary = lTertiary, onTertiary = lOnTertiary, tertiaryContainer = lTertiaryContainer, onTertiaryContainer = lOnTertiaryContainer,
        background = lSurface, onBackground = lOnSurface, surface = lSurface, onSurface = lOnSurface,
        surfaceVariant = lSurfaceVariant, onSurfaceVariant = lOnSurfaceVariant, surfaceTint = lPrimary,
        inverseSurface = Color(0xFF392E2B), inverseOnSurface = Color(0xFFFFEDE8),
        outline = lOutline, outlineVariant = lOutlineVariant,
        surfaceBright = lSurface, surfaceDim = lDim,
        surfaceContainerLowest = lLowest, surfaceContainerLow = lLow, surfaceContainer = lContainer,
        surfaceContainerHigh = lHigh, surfaceContainerHighest = lHighest
    )
}

/** Light, dark or follow the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** True when the device can supply its own (wallpaper) colours. */
val supportsDeviceColors: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun byteColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && supportsDeviceColors -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> SeedDark
        else -> SeedLight
    }
}

@Composable
fun ByteLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = byteColorScheme(darkTheme, dynamicColor)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalReduceMotion provides rememberReduceMotion(),
        LocalByteExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
