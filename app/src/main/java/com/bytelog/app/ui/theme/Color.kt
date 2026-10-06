package com.bytelog.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * ByteLog's colour names, now resolved from the current theme so every screen follows Material You
 * (the device's wallpaper colours on Android 12+) and light or dark mode. The fallback scheme, used
 * when device colours are off or unavailable, is generated from ByteLog's tomato seed.
 *
 * The names are kept so the many screens that use them keep working; each maps to a Material role.
 */

/** Fixed brand colours, for the launch splash only. */
val BrandTomato = Color(0xFFF2542D)
val BrandCream = Color(0xFFFFF4E6)

/** Brand accent: progress, highlights, primary actions. Text on it uses [ByteOnTomato]. */
val ByteTomato: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val ByteOnTomato: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
val ByteTomatoSubtle: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

/** Main text and icons. */
val ByteCream: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
/** Secondary text and icons. */
val ByteCreamMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val ByteTextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Page background. */
val ByteInkDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
/** Cards. */
val ByteSurfaceDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
val ByteCardDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
/** Tracks, chips and buttons sitting on cards. */
val ByteSurfaceVariantDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
val ByteOutlineDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant

/** Fixed meaning colours (macros, water), tuned per light/dark so they stay readable on both. */
@Immutable
data class ByteExtraColors(
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val water: Color,
    val waterContainer: Color,
    val onWaterContainer: Color,
    val success: Color
)

val DarkExtraColors = ByteExtraColors(
    protein = Color(0xFF8CCDFF),
    carbs = Color(0xFFF5C46B),
    fat = Color(0xFFFFB1C1),
    water = Color(0xFF8CCDFF),
    waterContainer = Color(0xFF004B70),
    onWaterContainer = Color(0xFFCDE5FF),
    success = Color(0xFF7DDC9A)
)

val LightExtraColors = ByteExtraColors(
    protein = Color(0xFF00639B),
    carbs = Color(0xFF7C5800),
    fat = Color(0xFFA8344E),
    water = Color(0xFF00639B),
    waterContainer = Color(0xFFCDE5FF),
    onWaterContainer = Color(0xFF001D32),
    success = Color(0xFF1B6C3A)
)

val LocalByteExtraColors = staticCompositionLocalOf { DarkExtraColors }

val MacroProtein: Color @Composable @ReadOnlyComposable get() = LocalByteExtraColors.current.protein
val MacroCarbs: Color @Composable @ReadOnlyComposable get() = LocalByteExtraColors.current.carbs
val MacroFat: Color @Composable @ReadOnlyComposable get() = LocalByteExtraColors.current.fat
val MacroCalories: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

// ---------- fallback scheme from the tomato seed (#F2542D) ----------

internal object SeedTones {
    // dark
    val dPrimary = Color(0xFFFFB4A1); val dOnPrimary = Color(0xFF5F1500)
    val dPrimaryContainer = Color(0xFF862200); val dOnPrimaryContainer = Color(0xFFFFDBD1)
    val dSecondary = Color(0xFFE7BDB2); val dOnSecondary = Color(0xFF442A22)
    val dSecondaryContainer = Color(0xFF5D3F37); val dOnSecondaryContainer = Color(0xFFFFDBD1)
    val dTertiary = Color(0xFFD8C58D); val dOnTertiary = Color(0xFF3B2F05)
    val dTertiaryContainer = Color(0xFF534619); val dOnTertiaryContainer = Color(0xFFF5E1A7)
    val dSurface = Color(0xFF1A110F); val dOnSurface = Color(0xFFF1DFDA)
    val dSurfaceVariant = Color(0xFF53433F); val dOnSurfaceVariant = Color(0xFFD8C2BC)
    val dOutline = Color(0xFFA08C87); val dOutlineVariant = Color(0xFF53433F)
    val dLowest = Color(0xFF140C0A); val dLow = Color(0xFF231917); val dContainer = Color(0xFF271D1B)
    val dHigh = Color(0xFF322825); val dHighest = Color(0xFF3D3230); val dBright = Color(0xFF423734)

    // light
    val lPrimary = Color(0xFFA33A1E); val lOnPrimary = Color(0xFFFFFFFF)
    val lPrimaryContainer = Color(0xFFFFDBD1); val lOnPrimaryContainer = Color(0xFF3B0900)
    val lSecondary = Color(0xFF77574E); val lOnSecondary = Color(0xFFFFFFFF)
    val lSecondaryContainer = Color(0xFFFFDBD1); val lOnSecondaryContainer = Color(0xFF2C150F)
    val lTertiary = Color(0xFF6B5E2F); val lOnTertiary = Color(0xFFFFFFFF)
    val lTertiaryContainer = Color(0xFFF5E1A7); val lOnTertiaryContainer = Color(0xFF231B00)
    val lSurface = Color(0xFFFFF8F6); val lOnSurface = Color(0xFF231917)
    val lSurfaceVariant = Color(0xFFF5DED8); val lOnSurfaceVariant = Color(0xFF53433F)
    val lOutline = Color(0xFF85736E); val lOutlineVariant = Color(0xFFD8C2BC)
    val lLowest = Color(0xFFFFFFFF); val lLow = Color(0xFFFFF1ED); val lContainer = Color(0xFFFCEAE5)
    val lHigh = Color(0xFFF7E4E0); val lHighest = Color(0xFFF1DFDA); val lDim = Color(0xFFE8D6D2)
}
