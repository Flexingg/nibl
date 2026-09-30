package com.bytelog.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ByteTomato,
    onPrimary = ByteCream,
    primaryContainer = ByteTomatoDark,
    onPrimaryContainer = ByteCream,
    secondary = ByteCream,
    onSecondary = ByteInkDark,
    background = ByteInkDark,
    onBackground = ByteCream,
    surface = ByteSurfaceDark,
    onSurface = ByteCream,
    surfaceVariant = ByteSurfaceVariantDark,
    onSurfaceVariant = ByteCreamMuted,
    outline = ByteOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = ByteTomato,
    onPrimary = Color.White,
    primaryContainer = ByteTomatoLight,
    onPrimaryContainer = ByteInkDark,
    secondary = ByteTomatoDark,
    onSecondary = Color.White,
    background = ByteBackgroundLight,
    onBackground = ByteInkDark,
    surface = ByteSurfaceLight,
    onSurface = ByteInkDark,
    surfaceVariant = ByteSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF6B5E57),
    outline = ByteOutlineLight
)

@Composable
fun ByteLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
