package com.sustream.tv.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Semantic tokens that Material's own [androidx.tv.material3.ColorScheme] cannot express — focus
 * treatment, the IPTV accent, status colours, and the surface ladder taken from the prototype.
 * Provided alongside the Material theme rather than instead of it, so `tv-material` components
 * still get correct defaults.
 */
val LocalSuStreamColours: ProvidableCompositionLocal<SuStreamColours> =
    staticCompositionLocalOf { SuStreamColours() }

val LocalSuStreamShapes: ProvidableCompositionLocal<SuStreamShapes> =
    staticCompositionLocalOf { SuStreamShapes() }

/**
 * The app theme. Dark only, by design: see [SuStreamColours].
 *
 * @param colours override for previews and screenshot tests.
 */
@Composable
fun SuStreamTheme(
    colours: SuStreamColours = SuStreamColours(),
    shapes: SuStreamShapes = SuStreamShapes(),
    content: @Composable () -> Unit,
) {
    // Map our semantic tokens onto the tv-material scheme so stock components inherit the brand
    // instead of Material's purple defaults.
    val colorScheme = darkColorScheme(
        primary = colours.primary,
        onPrimary = colours.onPrimary,
        primaryContainer = colours.primaryMuted,
        onPrimaryContainer = colours.accent,
        secondary = colours.iptvAccent,
        onSecondary = colours.onIptvAccent,
        background = colours.background,
        onBackground = colours.textPrimary,
        surface = colours.surface,
        onSurface = colours.textPrimary,
        surfaceVariant = colours.surfaceRaised,
        onSurfaceVariant = colours.textSecondary,
        border = colours.border,
        borderVariant = colours.borderStrong,
        error = colours.danger,
        onError = colours.onPrimary,
    )

    CompositionLocalProvider(
        LocalSuStreamColours provides colours,
        LocalSuStreamShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SuStreamTypography,
        ) {
            // A single opaque root so no screen can accidentally render on a transparent window.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colours.background),
            ) {
                content()
            }
        }
    }
}

/**
 * Short-hand accessors. `SuStreamTheme.colours.textSecondary` reads better at call sites than
 * `LocalSuStreamColours.current.textSecondary`, and keeps the token vocabulary discoverable.
 */
object SuStreamTheme {
    val colours: SuStreamColours
        @Composable @ReadOnlyComposable get() = LocalSuStreamColours.current

    val shapes: SuStreamShapes
        @Composable @ReadOnlyComposable get() = LocalSuStreamShapes.current

    val typography: androidx.tv.material3.Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}

/** Convenience for the very common "secondary body text" pairing. */
@Composable
@ReadOnlyComposable
fun secondaryBodyStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(color = LocalSuStreamColours.current.textSecondary)

/** Convenience for metadata rows ("2025 · Sci-Fi · 2h 18m"). */
@Composable
@ReadOnlyComposable
fun metadataStyle(): TextStyle =
    MaterialTheme.typography.labelMedium.copy(color = LocalSuStreamColours.current.textTertiary)

/** Alias so callers can reach a shape without importing the shapes class. */
@Composable
@ReadOnlyComposable
fun cardShape(): Shape = LocalSuStreamShapes.current.card
