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
import androidx.compose.ui.unit.dp

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

/** The Living Flow corner family (see [FluidRadius]). */
val ExpressiveShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(FluidRadius.xs),
    small = androidx.compose.foundation.shape.RoundedCornerShape(FluidRadius.sm),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(FluidRadius.md),
    large = androidx.compose.foundation.shape.RoundedCornerShape(FluidRadius.lg),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(FluidRadius.xl)
)

/**
 * Living Flow surfaces: a calm neutral base (soft grey in light, true black in dark), opaque white or lifted-grey
 * cards, and the accent colours left as they are (tomato, or the wallpaper's accent with device colours on).
 */
fun fluidize(scheme: ColorScheme, dark: Boolean): ColorScheme = if (dark) scheme.copy(
    background = Color(0xFF000000), surface = Color(0xFF000000), surfaceDim = Color(0xFF000000), surfaceBright = Color(0xFF2A2A2D),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF121214), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2A2A2D), surfaceContainerHighest = Color(0xFF36363A),
    onBackground = Color(0xFFEBEBEB), onSurface = Color(0xFFEBEBEB), onSurfaceVariant = Color(0xFFA1A1A6),
    surfaceVariant = Color(0xFF2A2A2D), outline = Color(0xFF6E6E73), outlineVariant = Color(0x1AFFFFFF),
    inverseSurface = Color(0xFFEBEBEB), inverseOnSurface = Color(0xFF1C1C1E), scrim = Color(0xFF000000)
) else scheme.copy(
    background = Color(0xFFF2F3F5), surface = Color(0xFFF2F3F5), surfaceDim = Color(0xFFE6E7EA), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F3F5), surfaceContainerHighest = Color(0xFFE8E9EC),
    onBackground = Color(0xFF1A1A1C), onSurface = Color(0xFF1A1A1C), onSurfaceVariant = Color(0xFF5E5F63),
    surfaceVariant = Color(0xFFE8E9EC), outline = Color(0xFF8E8E93), outlineVariant = Color(0x14000000),
    inverseSurface = Color(0xFF2A2A2D), inverseOnSurface = Color(0xFFF2F3F5), scrim = Color(0xFF000000)
)

/** Tomato accents tuned for the neutral Living Flow surfaces (contrast checked against white and #F2F3F5). */
private val FluidSeedLight get() = fluidize(SeedLight.copy(
    primary = Color(0xFFCC3B17), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3D9), onPrimaryContainer = Color(0xFF5A1500),
    secondaryContainer = Color(0xFFFCE5DC), onSecondaryContainer = Color(0xFF4A1D10)
), dark = false)

private val FluidSeedDark get() = fluidize(SeedDark.copy(
    primary = Color(0xFFFF7A55), onPrimary = Color(0xFF2B0A00),
    primaryContainer = Color(0xFF5A2010), onPrimaryContainer = Color(0xFFFFDBD0),
    secondaryContainer = Color(0xFF3A2620), onSecondaryContainer = Color(0xFFFFDBD0)
), dark = true)

/** Light, dark or follow the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** True when the device can supply its own (wallpaper) colours. */
val supportsDeviceColors: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun byteColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    return byteColorSchemeFor(context, darkTheme, dynamicColor)
}

/** The app's colours without a composition (home screen widgets): device colours when allowed, else the seed theme. */
fun byteColorSchemeFor(context: android.content.Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme = when {
    // The wallpaper supplies the accent; the surfaces stay Living Flow neutrals
    dynamicColor && supportsDeviceColors -> fluidize(if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), darkTheme)
    darkTheme -> FluidSeedDark
    else -> FluidSeedLight
}

@Composable
fun ByteLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    reduceTransparency: Boolean = false,
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
        LocalByteExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors,
        LocalFluid provides if (darkTheme) DarkFluid else LightFluid,
        LocalReduceTransparency provides reduceTransparency
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ExpressiveShapes,
            content = content
        )
    }
}
